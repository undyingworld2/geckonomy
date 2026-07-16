package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.Attribution
import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.application.EconomyFixture.Companion.CAROL
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.Money
import com.the1mason.geckonomy.domain.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.SQLException

/**
 * M4's headline acceptance criteria (`tasks/M4-application.md`): success moves funds; insufficient
 * funds fails without mutation; a simulated storage failure rolls back.
 */
class TransferTest {

    private val coins = TestCurrencies.COINS.code

    private fun transferWith(fixture: EconomyFixture) =
        Transfer(fixture.unitOfWork, fixture.amounts, fixture.transactions, fixture.guard)

    private suspend fun EconomyFixture.givenTwoFundedAccounts() {
        givenAccount(ALICE, "Alice")
        givenAccount(BOB, "Bob")
        balances.set(ALICE, TestCurrencies.COINS, BigDecimal("100.00"))
        balances.set(BOB, TestCurrencies.COINS, BigDecimal("20.00"))
    }

    private suspend fun EconomyFixture.coinsOf(id: AccountId) = balances.get(id, TestCurrencies.COINS)

    @Test
    fun `moves funds and answers with both balances`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenTwoFundedAccounts()

        val result = transferWith(fixture)(ALICE, BOB, BigDecimal("30.00"), coins)

        val moved = (result as Outcome.Success).value
        assertEquals(BigDecimal("70.00"), moved.payerBalance.amount)
        assertEquals(BigDecimal("50.00"), moved.payeeBalance.amount)
        assertEquals(0, BigDecimal("70.00").compareTo(fixture.coinsOf(ALICE)))
        assertEquals(0, BigDecimal("50.00").compareTo(fixture.coinsOf(BOB)))
    }

    @Test
    fun `writes one ledger row per side, each naming the other`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenTwoFundedAccounts()

        transferWith(fixture)(ALICE, BOB, BigDecimal("30.00"), coins, Attribution("TradePlugin"))

        val (out, into) = fixture.log.entries
        assertEquals(2, fixture.log.entries.size)

        assertEquals(ALICE, out.accountId)
        assertEquals(TransactionType.TRANSFER_OUT, out.type)
        assertEquals(BigDecimal("-30.00"), out.delta)
        assertEquals(BigDecimal("70.0000"), out.resultingBalance)
        assertEquals(BOB, out.counterparty)
        assertEquals("TradePlugin", out.sourcePlugin)

        assertEquals(BOB, into.accountId)
        assertEquals(TransactionType.TRANSFER_IN, into.type)
        assertEquals(BigDecimal("30.00"), into.delta)
        assertEquals(BigDecimal("50.0000"), into.resultingBalance)
        assertEquals(ALICE, into.counterparty)
    }

    @Test
    fun `insufficient funds fails without moving anything`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenTwoFundedAccounts()

        val result = transferWith(fixture)(ALICE, BOB, BigDecimal("500.00"), coins)

        assertEquals(
            EconomyError.InsufficientFunds(ALICE, Money(BigDecimal("500.00"), TestCurrencies.COINS)),
            (result as Outcome.Failure).error,
        )
        assertEquals(0, BigDecimal("100.00").compareTo(fixture.coinsOf(ALICE)))
        assertEquals(0, BigDecimal("20.00").compareTo(fixture.coinsOf(BOB)))
        assertTrue(fixture.log.entries.isEmpty())
    }

    /**
     * A thrown storage exception unwinds the transaction on its own, so this proves the
     * `UnitOfWork` wrapping — not the `Abort` mechanism. See
     * `a refusal rolls back the row adjust seeded` for that one.
     */
    @Test
    fun `a failure after both legs have moved rolls both back`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenTwoFundedAccounts()
        // The ledger write comes after both balances have already changed, so this is the failure
        // that has the most to undo.
        fixture.log.failWith = SQLException("ledger is down")

        val result = transferWith(fixture)(ALICE, BOB, BigDecimal("30.00"), coins)

        assertInstanceOf(EconomyError.StorageFailure::class.java, (result as Outcome.Failure).error)
        assertEquals(0, BigDecimal("100.00").compareTo(fixture.coinsOf(ALICE)), "the debit must roll back")
        assertEquals(0, BigDecimal("20.00").compareTo(fixture.coinsOf(BOB)), "the credit must roll back")
        assertTrue(fixture.log.entries.isEmpty())
    }

    /**
     * The `Abort` mechanism, pinned.
     *
     * `adjust` seeds a zero row *before* it applies the guard, so by the time it refuses, it has
     * already written. Throwing unwinds that; `return@transaction Outcome.Failure(...)` would commit
     * it — and this is the only test that can tell the two apart, because every other refusal happens
     * before any write. Swap the throw for a return and this fails, which is the point of it.
     *
     * The stakes are higher than one stray row: the same substitution would commit a *debit* if the
     * credit ever failed, and the reason this test looks small is only that the credit cannot.
     */
    @Test
    fun `a refusal rolls back the row adjust seeded`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenAccount(ALICE, "Alice")
        fixture.givenAccount(BOB, "Bob")
        // No GEMS row for either: adjust will seed Alice at zero, then refuse to take 5 from it.

        val result = transferWith(fixture)(ALICE, BOB, BigDecimal("5"), TestCurrencies.GEMS.code)

        assertInstanceOf(EconomyError.InsufficientFunds::class.java, (result as Outcome.Failure).error)
        assertNull(
            fixture.balances.get(ALICE, TestCurrencies.GEMS),
            "the seeded row must roll back with the refusal",
        )
    }

    @Test
    fun `reports a missing payer`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenAccount(BOB, "Bob")

        val result = transferWith(fixture)(ALICE, BOB, BigDecimal("30.00"), coins)

        assertEquals(EconomyError.AccountNotFound(ALICE), (result as Outcome.Failure).error)
    }

    @Test
    fun `reports a missing payee without touching the payer`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenAccount(ALICE, "Alice")
        fixture.balances.set(ALICE, TestCurrencies.COINS, BigDecimal("100.00"))

        val result = transferWith(fixture)(ALICE, CAROL, BigDecimal("30.00"), coins)

        assertEquals(EconomyError.AccountNotFound(CAROL), (result as Outcome.Failure).error)
        assertEquals(0, BigDecimal("100.00").compareTo(fixture.coinsOf(ALICE)))
    }

    @Test
    fun `refuses to pay yourself`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenTwoFundedAccounts()

        val result = transferWith(fixture)(ALICE, ALICE, BigDecimal("30.00"), coins)

        assertEquals(
            EconomyError.InvalidAmount(BigDecimal("30.00"), "payer and payee are the same account"),
            (result as Outcome.Failure).error,
        )
        assertEquals(0, BigDecimal("100.00").compareTo(fixture.coinsOf(ALICE)))
        assertTrue(fixture.log.entries.isEmpty())
    }

    @Test
    fun `rejects a zero or negative amount`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenTwoFundedAccounts()

        assertInstanceOf(Outcome.Failure::class.java, transferWith(fixture)(ALICE, BOB, BigDecimal.ZERO, coins))
        assertInstanceOf(Outcome.Failure::class.java, transferWith(fixture)(ALICE, BOB, BigDecimal("-30.00"), coins))
        assertEquals(0, BigDecimal("100.00").compareTo(fixture.coinsOf(ALICE)))
    }

    @Test
    fun `reports an unknown currency`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenTwoFundedAccounts()
        val unknown = CurrencyCode("unobtainium")

        val result = transferWith(fixture)(ALICE, BOB, BigDecimal("30.00"), unknown)

        assertEquals(EconomyError.UnknownCurrency(unknown), (result as Outcome.Failure).error)
    }

    @Test
    fun `moves the payer's whole balance`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenTwoFundedAccounts()

        val result = transferWith(fixture)(ALICE, BOB, BigDecimal("100.00"), coins)

        assertEquals(BigDecimal("0.00"), (result as Outcome.Success).value.payerBalance.amount)
    }

    @Test
    fun `lets a payer go negative when overdraft is on`() = runBlocking {
        val fixture = EconomyFixture(allowOverdraft = true)
        fixture.givenTwoFundedAccounts()

        val result = transferWith(fixture)(ALICE, BOB, BigDecimal("150.00"), coins)

        assertEquals(BigDecimal("-50.00"), (result as Outcome.Success).value.payerBalance.amount)
        assertEquals(BigDecimal("170.00"), result.value.payeeBalance.amount)
    }
}
