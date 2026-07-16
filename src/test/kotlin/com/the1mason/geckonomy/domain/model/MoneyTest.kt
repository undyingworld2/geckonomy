package com.the1mason.geckonomy.domain.model

import com.the1mason.geckonomy.domain.CurrencyMismatch
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.coins
import com.the1mason.geckonomy.domain.gems
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.RoundingMode

class MoneyTest {

    @Nested
    inner class Arithmetic {

        @Test
        fun `adds amounts of the same currency`() {
            assertAmountEquals("3.75", "1.25".coins + "2.50".coins)
        }

        @Test
        fun `subtracts amounts of the same currency`() {
            assertAmountEquals("1.25", "3.75".coins - "2.50".coins)
        }

        @Test
        fun `subtraction may cross zero — whether that is allowed is OverdraftPolicy's call`() {
            assertAmountEquals("-2.50", "1.00".coins - "3.50".coins)
        }

        @Test
        fun `keeps the currency of the left operand`() {
            assertEquals(TestCurrencies.COINS, ("1.00".coins + "2.00".coins).currency)
        }
    }

    @Nested
    inner class CrossCurrencyGuard {

        @Test
        fun `adding a different currency throws CurrencyMismatch`() {
            val thrown = assertThrows<CurrencyMismatch> { "1.00".coins + "1".gems }

            assertEquals(CurrencyCode("coins"), thrown.left)
            assertEquals(CurrencyCode("gems"), thrown.right)
        }

        @Test
        fun `subtracting a different currency throws CurrencyMismatch`() {
            assertThrows<CurrencyMismatch> { "1.00".coins - "1".gems }
        }
    }

    @Nested
    inner class IsNegative {

        @Test
        fun `a below-zero amount is negative`() = assertTrue("-0.01".coins.isNegative())

        @Test
        fun `a positive amount is not negative`() = assertFalse("0.01".coins.isNegative())

        @Test
        fun `zero is not negative`() = assertFalse("0.00".coins.isNegative())

        @Test
        fun `negative zero is not negative`() = assertFalse("-0.00".coins.isNegative())
    }

    @Nested
    inner class Rounded {

        @Test
        fun `scales to the currency's fractional digits`() {
            assertAmountEquals("1.24", "1.2449".coins.rounded(RoundingMode.HALF_UP))
        }

        @Test
        fun `rounds a zero-digit currency to whole units`() {
            assertAmountEquals("3", "2.5".gems.rounded(RoundingMode.HALF_UP))
        }

        @Test
        fun `pads a short amount out to the currency's scale`() {
            assertEquals(2, "1.5".coins.rounded(RoundingMode.HALF_UP).amount.scale())
        }

        @Test
        fun `honours the mode it is given`() {
            assertAmountEquals("1.24", "1.245".coins.rounded(RoundingMode.DOWN))
            assertAmountEquals("1.25", "1.245".coins.rounded(RoundingMode.HALF_UP))
        }

        @Test
        fun `leaves the currency alone`() {
            assertEquals(TestCurrencies.GEMS, "2.5".gems.rounded(RoundingMode.HALF_UP).currency)
        }
    }

    @Nested
    inner class Equality {

        @Test
        fun `equal amounts at equal scale are equal`() {
            assertEquals("1.50".coins, "1.50".coins)
        }

        /** Guards the documented footgun: data-class equality inherits BigDecimal's scale sensitivity. */
        @Test
        fun `equal amounts at different scales are NOT equal — compare with compareTo`() {
            assertTrue("1.5".coins != "1.50".coins)
            assertEquals(0, "1.5".coins.amount.compareTo("1.50".coins.amount))
        }
    }

    private fun assertAmountEquals(expected: String, actual: Money) =
        assertEquals(
            0,
            BigDecimal(expected).compareTo(actual.amount),
            "expected $expected but was ${actual.amount}",
        )
}
