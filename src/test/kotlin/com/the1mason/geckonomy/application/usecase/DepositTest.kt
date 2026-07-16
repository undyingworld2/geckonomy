package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.Attribution
import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.Transaction
import com.the1mason.geckonomy.domain.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.SQLException

class DepositTest {

    private val fixture = EconomyFixture()
    private val deposit = Deposit(fixture.unitOfWork, fixture.amounts, fixture.transactions, fixture.guard)

    private val coins = TestCurrencies.COINS.code

    private suspend fun givenAliceHolds(amount: String) {
        fixture.givenAccount(ALICE)
        fixture.balances.set(ALICE, TestCurrencies.COINS, BigDecimal(amount))
    }

    @Test
    fun `credits the account and answers with the new balance`() = runBlocking {
        givenAliceHolds("75.00")

        val result = deposit(ALICE, BigDecimal("25.00"), coins)

        assertEquals(BigDecimal("100.00"), (result as Outcome.Success).value.amount)
        assertEquals(0, BigDecimal("100.00").compareTo(fixture.balances.get(ALICE, TestCurrencies.COINS)))
    }

    @Test
    fun `writes exactly one ledger row`() = runBlocking {
        givenAliceHolds("75.00")

        deposit(ALICE, BigDecimal("25.00"), coins, Attribution("ShopPlugin"))

        // The whole row at once — the fixture fixes the clock and the id, so there is nothing here
        // that has to be matched loosely.
        assertEquals(
            listOf(
                Transaction(
                    id = EconomyFixture.txId(1),
                    accountId = ALICE,
                    currency = coins,
                    delta = BigDecimal("25.00"),
                    resultingBalance = BigDecimal("100.0000"),
                    type = TransactionType.DEPOSIT,
                    sourcePlugin = "ShopPlugin",
                    counterparty = null,
                    createdAt = EconomyFixture.NOW,
                ),
            ),
            fixture.log.entries,
        )
    }

    @Test
    fun `attributes to geckonomy by default`() = runBlocking {
        givenAliceHolds("75.00")

        deposit(ALICE, BigDecimal("25.00"), coins)

        assertEquals("geckonomy", fixture.log.entries.single().sourcePlugin)
    }

    @Test
    fun `deposits into a currency the account has no row for`() = runBlocking {
        // GEMS was added to config after Alice existed. adjust seeds at zero, so this must work.
        fixture.givenAccount(ALICE)

        val result = deposit(ALICE, BigDecimal("3"), TestCurrencies.GEMS.code)

        assertEquals(BigDecimal("3"), (result as Outcome.Success).value.amount)
    }

    @Test
    fun `rounds before storing`() = runBlocking {
        givenAliceHolds("0.00")

        val result = deposit(ALICE, BigDecimal("1.005"), coins)

        assertEquals(BigDecimal("1.01"), (result as Outcome.Success).value.amount)
        assertEquals(BigDecimal("1.01"), fixture.log.entries.single().delta)
    }

    @Test
    fun `reports a missing account`() = runBlocking {
        val result = deposit(BOB, BigDecimal("25.00"), coins)

        assertEquals(EconomyError.AccountNotFound(BOB), (result as Outcome.Failure).error)
        assertTrue(fixture.log.entries.isEmpty())
    }

    @Test
    fun `rejects a zero or negative amount`() = runBlocking {
        givenAliceHolds("75.00")

        assertInstanceOf(Outcome.Failure::class.java, deposit(ALICE, BigDecimal.ZERO, coins))
        assertInstanceOf(Outcome.Failure::class.java, deposit(ALICE, BigDecimal("-1.00"), coins))
        assertEquals(0, BigDecimal("75.00").compareTo(fixture.balances.get(ALICE, TestCurrencies.COINS)))
    }

    @Test
    fun `reports an unknown currency`() = runBlocking {
        fixture.givenAccount(ALICE)
        val unknown = CurrencyCode("unobtainium")

        val result = deposit(ALICE, BigDecimal("1.00"), unknown)

        assertEquals(EconomyError.UnknownCurrency(unknown), (result as Outcome.Failure).error)
    }

    /** See `AccountQueriesTest.maps a storage failure on every query` for the explicit `: Unit`. */
    @Test
    fun `maps a storage failure`(): Unit = runBlocking {
        givenAliceHolds("75.00")
        fixture.balances.failWith = SQLException("connection reset")

        val result = deposit(ALICE, BigDecimal("25.00"), coins)

        assertInstanceOf(EconomyError.StorageFailure::class.java, (result as Outcome.Failure).error)
    }

    @Test
    fun `does not move money when the ledger write fails`() = runBlocking {
        // Why this runs in a transaction at all, against ARCHITECTURE.md §5's bare sequence: a
        // credit with no ledger row makes FR-B7 silently false.
        givenAliceHolds("75.00")
        fixture.log.failWith = SQLException("ledger is down")

        val result = deposit(ALICE, BigDecimal("25.00"), coins)

        assertInstanceOf(Outcome.Failure::class.java, result)
        assertEquals(0, BigDecimal("75.00").compareTo(fixture.balances.get(ALICE, TestCurrencies.COINS)))
    }
}
