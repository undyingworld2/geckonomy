package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.policy.CurrencyValidation
import com.the1mason.geckonomy.domain.policy.RoundingPolicy
import com.the1mason.geckonomy.infrastructure.config.ConfigCurrencyRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

class AmountsTest {

    private val registry = ConfigCurrencyRegistry(listOf(TestCurrencies.COINS, TestCurrencies.GEMS))
    private var mode = RoundingMode.HALF_UP
    private val amounts = Amounts(CurrencyValidation(registry)) { RoundingPolicy(mode) }

    private val coins = TestCurrencies.COINS.code
    private val gems = TestCurrencies.GEMS.code

    @Test
    fun `resolves a configured currency`() {
        assertEquals(Outcome.Success(TestCurrencies.COINS), amounts.currency(coins))
    }

    @Test
    fun `reports an unknown currency`() {
        val unknown = CurrencyCode("unobtainium")

        assertEquals(Outcome.Failure(EconomyError.UnknownCurrency(unknown)), amounts.currency(unknown))
    }

    @Test
    fun `rounds a positive amount to the currency's scale`() {
        val result = amounts.positive(BigDecimal("1.005"), coins)

        assertEquals(BigDecimal("1.01"), (result as Outcome.Success).value.amount)
    }

    @Test
    fun `rounds to whole units for a currency with no fractional digits`() {
        val result = amounts.positive(BigDecimal("2.6"), gems)

        assertEquals(BigDecimal("3"), (result as Outcome.Success).value.amount)
    }

    @Test
    fun `reads the rounding mode per call so reload works`() {
        // settings.rounding-mode is reloadable — ConfigService does not warn that it needs a restart —
        // so the policy is supplied, not captured. This is the test that would fail if someone
        // "simplified" Amounts to take a RoundingPolicy.
        mode = RoundingMode.DOWN

        assertEquals(BigDecimal("1.00"), (amounts.positive(BigDecimal("1.009"), coins) as Outcome.Success).value.amount)
    }

    @Test
    fun `rejects a zero amount`() {
        assertInstanceOf(Outcome.Failure::class.java, amounts.positive(BigDecimal.ZERO, coins))
    }

    @Test
    fun `rejects a negative amount`() {
        val result = amounts.positive(BigDecimal("-5.00"), coins)

        assertEquals(
            EconomyError.InvalidAmount(BigDecimal("-5.00"), "amount must be greater than zero"),
            (result as Outcome.Failure).error,
        )
    }

    @Test
    fun `rejects an amount that rounds away to nothing`() {
        // Positive going in, zero going out. Accepting it would report a successful deposit that
        // moved no money.
        val result = amounts.positive(BigDecimal("0.004"), coins)

        assertEquals(
            EconomyError.InvalidAmount(BigDecimal("0.004"), "amount rounds to zero at this currency's scale"),
            (result as Outcome.Failure).error,
        )
    }

    @Test
    fun `reports an unknown currency before judging the amount`() {
        // Order matters: "-5 unobtainium" is two problems, and the currency is the one the player
        // can act on.
        val result = amounts.positive(BigDecimal("-5"), CurrencyCode("unobtainium"))

        assertInstanceOf(EconomyError.UnknownCurrency::class.java, (result as Outcome.Failure).error)
    }

    @Test
    fun `nonNegative permits zero but not less`() {
        assertInstanceOf(Outcome.Success::class.java, amounts.nonNegative(BigDecimal.ZERO, coins))
        assertInstanceOf(Outcome.Failure::class.java, amounts.nonNegative(BigDecimal("-0.01"), coins))
    }

    @Test
    fun `any permits a negative amount`() {
        // An admin setting a balance below zero is OverdraftPolicy's call, not this one's.
        val result = amounts.any(BigDecimal("-5.00"), coins)

        assertEquals(BigDecimal("-5.00"), (result as Outcome.Success).value.amount)
    }

    @Test
    fun `balance normalizes a stored amount to the currency's display scale`() {
        // Storage keeps scale 4; a 2-digit currency must not surface as 100.0000.
        val money = amounts.balance(BigDecimal("100.0000"), TestCurrencies.COINS)

        assertEquals(BigDecimal("100.00"), money.amount)
        assertEquals(TestCurrencies.COINS, money.currency)
    }
}
