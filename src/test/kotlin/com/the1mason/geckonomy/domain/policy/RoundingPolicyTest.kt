package com.the1mason.geckonomy.domain.policy

import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.coins
import com.the1mason.geckonomy.domain.gems
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

class RoundingPolicyTest {

    private val halfUp = RoundingPolicy(RoundingMode.HALF_UP)
    private val down = RoundingPolicy(RoundingMode.DOWN)

    @Nested
    inner class DefaultMode {

        @Test
        fun `defaults to HALF_UP, matching config`() {
            assertEquals(
                halfUp.round(BigDecimal("1.005"), TestCurrencies.COINS),
                RoundingPolicy().round(BigDecimal("1.005"), TestCurrencies.COINS),
            )
        }
    }

    @Nested
    inner class FractionalCurrency {

        @Test
        fun `scales to the currency's digits`() {
            assertEquals(BigDecimal("1.23"), halfUp.round(BigDecimal("1.2345"), TestCurrencies.COINS))
        }

        @Test
        fun `pads a short amount out to scale`() {
            assertEquals(BigDecimal("1.50"), halfUp.round(BigDecimal("1.5"), TestCurrencies.COINS))
        }

        /** The .5 boundary is the only place HALF_UP and DOWN disagree — so it is the test worth having. */
        @Test
        fun `HALF_UP and DOWN split on an exact half`() {
            assertEquals(BigDecimal("1.25"), halfUp.round(BigDecimal("1.245"), TestCurrencies.COINS))
            assertEquals(BigDecimal("1.24"), down.round(BigDecimal("1.245"), TestCurrencies.COINS))
        }

        @Test
        fun `DOWN truncates toward zero on both signs`() {
            assertEquals(BigDecimal("-1.24"), down.round(BigDecimal("-1.249"), TestCurrencies.COINS))
            assertEquals(BigDecimal("1.24"), down.round(BigDecimal("1.249"), TestCurrencies.COINS))
        }

        @Test
        fun `HALF_UP rounds an exact half away from zero`() {
            assertEquals(BigDecimal("-1.25"), halfUp.round(BigDecimal("-1.245"), TestCurrencies.COINS))
        }
    }

    @Nested
    inner class ZeroDigitCurrency {

        @Test
        fun `rounds to whole units`() {
            assertEquals(BigDecimal("3"), halfUp.round(BigDecimal("2.5"), TestCurrencies.GEMS))
            assertEquals(BigDecimal("2"), down.round(BigDecimal("2.9"), TestCurrencies.GEMS))
        }

        @Test
        fun `leaves no decimal places`() {
            assertEquals(0, halfUp.round(BigDecimal("2.5"), TestCurrencies.GEMS).scale())
        }
    }

    @Nested
    inner class MoneyOverload {

        @Test
        fun `rounds money to its own currency's digits`() {
            assertEquals("1.25".coins, halfUp.round("1.245".coins))
            assertEquals("3".gems, halfUp.round("2.5".gems))
        }

        @Test
        fun `agrees with the BigDecimal overload`() {
            assertEquals(
                halfUp.round(BigDecimal("1.245"), TestCurrencies.COINS),
                halfUp.round("1.245".coins).amount,
            )
        }
    }
}
