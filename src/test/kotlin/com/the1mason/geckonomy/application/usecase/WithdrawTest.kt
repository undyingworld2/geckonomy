package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.Attribution
import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.Money
import com.the1mason.geckonomy.domain.model.Transaction
import com.the1mason.geckonomy.domain.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.SQLException

class WithdrawTest {

    private val coins = TestCurrencies.COINS.code

    private fun withdrawWith(fixture: EconomyFixture) =
        Withdraw(fixture.unitOfWork, fixture.amounts, fixture.transactions, fixture.guard)

    private suspend fun EconomyFixture.givenAliceHolds(amount: String) {
        givenAccount(ALICE)
        balances.set(ALICE, TestCurrencies.COINS, BigDecimal(amount))
    }

    @Test
    fun `debits the account and answers with the new balance`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenAliceHolds("75.00")

        val result = withdrawWith(fixture)(ALICE, BigDecimal("25.00"), coins)

        assertEquals(BigDecimal("50.00"), (result as Outcome.Success).value.amount)
        assertEquals(0, BigDecimal("50.00").compareTo(fixture.balances.get(ALICE, TestCurrencies.COINS)))
    }

    @Test
    fun `writes one ledger row with a negative delta`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenAliceHolds("75.00")

        withdrawWith(fixture)(ALICE, BigDecimal("25.00"), coins, Attribution("ShopPlugin"))

        assertEquals(
            listOf(
                Transaction(
                    id = EconomyFixture.txId(1),
                    accountId = ALICE,
                    currency = coins,
                    delta = BigDecimal("-25.00"),
                    resultingBalance = BigDecimal("50.0000"),
                    type = TransactionType.WITHDRAW,
                    sourcePlugin = "ShopPlugin",
                    counterparty = null,
                    createdAt = EconomyFixture.NOW,
                ),
            ),
            fixture.log.entries,
        )
    }

    @Test
    fun `empties an account exactly`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenAliceHolds("50.00")

        val result = withdrawWith(fixture)(ALICE, BigDecimal("50.00"), coins)

        assertEquals(BigDecimal("0.00"), (result as Outcome.Success).value.amount)
    }

    @Test
    fun `refuses to overdraw, without moving anything`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenAliceHolds("49.99")

        val result = withdrawWith(fixture)(ALICE, BigDecimal("50.00"), coins)

        assertEquals(
            // The name is carried so the message can address a player rather than a UUID.
            EconomyError.InsufficientFunds(ALICE, Money(BigDecimal("50.00"), TestCurrencies.COINS), "Player"),
            (result as Outcome.Failure).error,
        )
        assertEquals(0, BigDecimal("49.99").compareTo(fixture.balances.get(ALICE, TestCurrencies.COINS)))
        assertTrue(fixture.log.entries.isEmpty(), "a refused withdrawal is not a ledger event")
    }

    @Test
    fun `overdraws when the policy allows it`() = runBlocking {
        val fixture = EconomyFixture(allowOverdraft = true)
        fixture.givenAliceHolds("10.00")

        val result = withdrawWith(fixture)(ALICE, BigDecimal("50.00"), coins)

        assertEquals(BigDecimal("-40.00"), (result as Outcome.Success).value.amount)
    }

    @Test
    fun `reports a missing account`() = runBlocking {
        val fixture = EconomyFixture()

        val result = withdrawWith(fixture)(BOB, BigDecimal("25.00"), coins)

        assertEquals(EconomyError.AccountNotFound(BOB), (result as Outcome.Failure).error)
    }

    @Test
    fun `rejects a zero or negative amount`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenAliceHolds("75.00")

        assertInstanceOf(Outcome.Failure::class.java, withdrawWith(fixture)(ALICE, BigDecimal.ZERO, coins))
        assertInstanceOf(Outcome.Failure::class.java, withdrawWith(fixture)(ALICE, BigDecimal("-1.00"), coins))
        assertEquals(0, BigDecimal("75.00").compareTo(fixture.balances.get(ALICE, TestCurrencies.COINS)))
    }

    @Test
    fun `does not move money when the ledger write fails`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenAliceHolds("75.00")
        fixture.log.failWith = SQLException("ledger is down")

        val result = withdrawWith(fixture)(ALICE, BigDecimal("25.00"), coins)

        assertInstanceOf(Outcome.Failure::class.java, result)
        assertEquals(0, BigDecimal("75.00").compareTo(fixture.balances.get(ALICE, TestCurrencies.COINS)))
    }
}
