package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.TestCurrencies
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CanWithdrawTest {

    private val coins = TestCurrencies.COINS.code

    private fun canWithdrawWith(fixture: EconomyFixture) = CanWithdraw(
        GetBalance(fixture.accounts, fixture.balances, fixture.amounts, fixture.guard),
        fixture.amounts,
        fixture.overdraft,
    )

    private suspend fun EconomyFixture.givenAliceHolds(amount: String) {
        givenAccount(ALICE)
        balances.set(ALICE, TestCurrencies.COINS, BigDecimal(amount))
    }

    @Test
    fun `says yes when the balance covers it`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenAliceHolds("75.00")

        assertEquals(Outcome.Success(true), canWithdrawWith(fixture)(ALICE, BigDecimal("50.00"), coins))
    }

    @Test
    fun `says yes when it empties the account exactly`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenAliceHolds("50.00")

        // Ending at zero is allowed; the guard refuses below zero, not at it.
        assertEquals(Outcome.Success(true), canWithdrawWith(fixture)(ALICE, BigDecimal("50.00"), coins))
    }

    @Test
    fun `says no when it would overdraw`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenAliceHolds("49.99")

        assertEquals(Outcome.Success(false), canWithdrawWith(fixture)(ALICE, BigDecimal("50.00"), coins))
    }

    @Test
    fun `says yes to an overdraft when the policy allows one`() = runBlocking {
        // The reason this is not just `has`: with overdraft on, a player holding nothing can still
        // withdraw. `has(50)` is false here while `canWithdraw(50)` is true, and both are right.
        val fixture = EconomyFixture(allowOverdraft = true)
        fixture.givenAliceHolds("0.00")

        assertEquals(Outcome.Success(true), canWithdrawWith(fixture)(ALICE, BigDecimal("50.00"), coins))
    }

    @Test
    fun `reports a missing account`() = runBlocking {
        val result = canWithdrawWith(EconomyFixture())(BOB, BigDecimal("1.00"), coins)

        assertEquals(EconomyError.AccountNotFound(BOB), (result as Outcome.Failure).error)
    }

    @Test
    fun `rejects a negative amount`() = runBlocking {
        val fixture = EconomyFixture()
        fixture.givenAliceHolds("75.00")

        val result = canWithdrawWith(fixture)(ALICE, BigDecimal("-1.00"), coins)

        assertEquals(
            EconomyError.InvalidAmount(BigDecimal("-1.00"), "amount must not be negative"),
            (result as Outcome.Failure).error,
        )
    }
}
