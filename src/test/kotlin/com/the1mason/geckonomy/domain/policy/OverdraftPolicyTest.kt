package com.the1mason.geckonomy.domain.policy

import com.the1mason.geckonomy.domain.coins
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

class OverdraftPolicyTest {

    private val denying = OverdraftPolicy(allowOverdraft = false)
    private val allowing = OverdraftPolicy(allowOverdraft = true)

    @Nested
    inner class Denying {

        @ParameterizedTest
        @ValueSource(strings = ["0.01", "100", "0.00", "-0.00"])
        fun `permits a balance at or above zero`(amount: String) {
            assertTrue(denying.permits(BigDecimal(amount)))
        }

        @ParameterizedTest
        @ValueSource(strings = ["-0.01", "-100"])
        fun `refuses a balance below zero`(amount: String) {
            assertFalse(denying.permits(BigDecimal(amount)))
        }
    }

    @Nested
    inner class Allowing {

        @ParameterizedTest
        @ValueSource(strings = ["-100", "-0.01", "0.00", "0.01", "100"])
        fun `permits any balance`(amount: String) {
            assertTrue(allowing.permits(BigDecimal(amount)))
        }
    }

    @Nested
    inner class Defaults {

        @Test
        fun `defaults to denying, matching config`() {
            assertFalse(OverdraftPolicy().permits(BigDecimal("-0.01")))
        }
    }

    @Nested
    inner class MoneyOverload {

        @Test
        fun `agrees with the BigDecimal overload`() {
            assertFalse(denying.permits("-0.01".coins))
            assertTrue(denying.permits("0.00".coins))
            assertTrue(allowing.permits("-0.01".coins))
        }
    }
}
