package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.Attribution
import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.Transaction
import com.the1mason.geckonomy.domain.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SetBalanceTest {

    private val coins = TestCurrencies.COINS.code

    private fun setBalanceWith(fixture: EconomyFixture) = SetBalance(
        fixture.unitOfWork,
        fixture.amounts,
        fixture.overdraft,
        fixture.transactions,
        fixture.guard,
    )

    private suspend fun EconomyFixture.givenAliceHolds(amount: String) {
        givenAccount(ALICE)
        balances.set(ALICE, TestCurrencies.COINS, BigDecimal(amount))
    }

    @Test
    fun `replaces the balance`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenAliceHolds("75.00")

        val result = setBalanceWith(fixture)(ALICE, BigDecimal("10.00"), coins)

        assertEquals(BigDecimal("10.00"), (result as Outcome.Success).value.amount)
        assertEquals(0, BigDecimal("10.00").compareTo(fixture.balances.get(ALICE, TestCurrencies.COINS)))
    }

    @Test
    fun `records the signed change, not the new amount`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenAliceHolds("75.00")

        setBalanceWith(fixture)(ALICE, BigDecimal("10.00"), coins, Attribution("AdminTool"))

        // delta = new - previous = -65. A SET row with delta = 0 would make the ledger useless for
        // reconciling an account's history.
        assertEquals(
            listOf(
                Transaction(
                    id = EconomyFixture.txId(1),
                    accountId = ALICE,
                    currency = coins,
                    delta = BigDecimal("-65.0000"),
                    resultingBalance = BigDecimal("10.00"),
                    type = TransactionType.SET,
                    sourcePlugin = "AdminTool",
                    counterparty = null,
                    createdAt = EconomyFixture.NOW,
                ),
            ),
            fixture.log.entries,
        )
    }

    @Test
    fun `treats a missing row as a previous balance of zero`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenAccount(ALICE)

        setBalanceWith(fixture)(ALICE, BigDecimal("5"), TestCurrencies.GEMS.code)

        assertEquals(BigDecimal("5"), fixture.log.entries.single().delta)
    }

    @Test
    fun `sets a balance to zero`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenAliceHolds("75.00")

        val result = setBalanceWith(fixture)(ALICE, BigDecimal.ZERO, coins)

        assertEquals(BigDecimal("0.00"), (result as Outcome.Success).value.amount)
    }

    @Test
    fun `refuses a negative balance while overdraft is off`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenAliceHolds("75.00")

        val result = setBalanceWith(fixture)(ALICE, BigDecimal("-1.00"), coins)

        // InvalidAmount, not InsufficientFunds: nothing is being paid for, the number is just not one
        // this server permits.
        assertEquals(
            EconomyError.InvalidAmount(BigDecimal("-1.00"), "negative balances are not allowed"),
            (result as Outcome.Failure).error,
        )
        assertEquals(0, BigDecimal("75.00").compareTo(fixture.balances.get(ALICE, TestCurrencies.COINS)))
        assertTrue(fixture.log.entries.isEmpty())
    }

    @Test
    fun `allows a negative balance when overdraft is on`() = runBlocking {
        val fixture = EconomyFixture(allowOverdraft = true)
        fixture.givenAliceHolds("75.00")

        val result = setBalanceWith(fixture)(ALICE, BigDecimal("-1.00"), coins)

        assertEquals(BigDecimal("-1.00"), (result as Outcome.Success).value.amount)
    }

    @Test
    fun `rounds before storing`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenAliceHolds("0.00")

        val result = setBalanceWith(fixture)(ALICE, BigDecimal("1.005"), coins)

        assertEquals(BigDecimal("1.01"), (result as Outcome.Success).value.amount)
    }

    @Test
    fun `reports a missing account`() = runBlocking {
        val fixture = EconomyFixture()

        val result = setBalanceWith(fixture)(BOB, BigDecimal("10.00"), coins)

        assertEquals(EconomyError.AccountNotFound(BOB), (result as Outcome.Failure).error)
    }
}
