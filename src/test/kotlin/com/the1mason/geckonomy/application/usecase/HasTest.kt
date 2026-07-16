package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.CurrencyCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class HasTest {

    private val fixture = EconomyFixture()
    private val has = Has(
        GetBalance(fixture.accounts, fixture.balances, fixture.amounts, fixture.guard),
        fixture.amounts,
    )

    private val coins = TestCurrencies.COINS.code

    private suspend fun givenAliceHolds(amount: String) {
        fixture.givenAccount(ALICE)
        fixture.balances.set(ALICE, TestCurrencies.COINS, BigDecimal(amount))
    }

    @Test
    fun `says yes when the balance is more than asked`() = runBlocking {
        givenAliceHolds("75.00")

        assertEquals(Outcome.Success(true), has(ALICE, BigDecimal("50.00"), coins))
    }

    @Test
    fun `says yes when the balance is exactly the amount`() = runBlocking {
        givenAliceHolds("50.00")

        assertEquals(Outcome.Success(true), has(ALICE, BigDecimal("50.00"), coins))
    }

    @Test
    fun `is not confused by scale`() = runBlocking {
        // 50.0000 from storage vs 50 asked for: the same money. Comparing with equals would say no.
        givenAliceHolds("50.0000")

        assertEquals(Outcome.Success(true), has(ALICE, BigDecimal("50"), coins))
    }

    @Test
    fun `says no when the balance is short`() = runBlocking {
        givenAliceHolds("49.99")

        assertEquals(Outcome.Success(false), has(ALICE, BigDecimal("50.00"), coins))
    }

    @Test
    fun `everyone has zero`() = runBlocking {
        givenAliceHolds("0.00")

        assertEquals(Outcome.Success(true), has(ALICE, BigDecimal.ZERO, coins))
    }

    @Test
    fun `rejects a negative amount`() = runBlocking {
        givenAliceHolds("75.00")

        val result = has(ALICE, BigDecimal("-1.00"), coins)

        assertEquals(
            EconomyError.InvalidAmount(BigDecimal("-1.00"), "amount must not be negative"),
            (result as Outcome.Failure).error,
        )
    }

    @Test
    fun `reports a missing account`() = runBlocking {
        val result = has(BOB, BigDecimal("1.00"), coins)

        assertEquals(EconomyError.AccountNotFound(BOB), (result as Outcome.Failure).error)
    }

    @Test
    fun `reports an unknown currency`() = runBlocking {
        fixture.givenAccount(ALICE)
        val unknown = CurrencyCode("unobtainium")

        val result = has(ALICE, BigDecimal("1.00"), unknown)

        assertEquals(EconomyError.UnknownCurrency(unknown), (result as Outcome.Failure).error)
    }
}
