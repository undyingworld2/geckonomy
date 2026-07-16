package com.the1mason.geckonomy.domain.model

import com.the1mason.geckonomy.domain.InvalidCurrencyCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CurrencyCodeTest {

    @Nested
    inner class Normalization {

        @ParameterizedTest
        @ValueSource(strings = ["coins", "COINS", "Coins", "cOiNs"])
        fun `lowercases on construction`(raw: String) {
            assertEquals("coins", CurrencyCode(raw).value)
        }

        @Test
        fun `codes differing only in case are the same code`() {
            assertEquals(CurrencyCode("COINS"), CurrencyCode("coins"))
        }
    }

    @Nested
    inner class Validation {

        @ParameterizedTest
        @ValueSource(strings = ["coins", "gems", "c", "premium_coins", "server-credits", "x9", "0"])
        fun `accepts letters, digits, underscore and hyphen`(raw: String) {
            assertEquals(raw, CurrencyCode(raw).value)
        }

        @ParameterizedTest
        @ValueSource(strings = ["", " ", "my coins", "coin$", "coins!", "café", "coins\n", "@global"])
        fun `rejects anything else`(raw: String) {
            val thrown = assertThrows<InvalidCurrencyCode> { CurrencyCode(raw) }

            assertEquals(raw, thrown.raw)
        }

        @Test
        fun `rejects a code that lowercasing cannot rescue`() {
            // 'İ' lowercases to 'i' plus a combining dot, which is not in [a-z0-9_-] — so
            // normalizing first must not be mistaken for making input valid.
            assertThrows<InvalidCurrencyCode> { CurrencyCode("COİNS") }
        }
    }

    @Nested
    inner class ParseOrNull {

        @Test
        fun `returns the normalized code for valid input`() {
            assertEquals(CurrencyCode("coins"), CurrencyCode.parseOrNull("Coins"))
        }

        @ParameterizedTest
        @ValueSource(strings = ["", " ", "my coins", "coin$"])
        fun `returns null instead of throwing for invalid input`(raw: String) {
            assertNull(CurrencyCode.parseOrNull(raw))
        }

        @Test
        fun `agrees with the throwing factory on what is valid`() {
            assertNotNull(CurrencyCode.parseOrNull("premium-coins_2"))
        }
    }
}
