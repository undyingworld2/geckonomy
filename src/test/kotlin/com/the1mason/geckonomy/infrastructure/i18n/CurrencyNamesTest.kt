package com.the1mason.geckonomy.infrastructure.i18n

import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.NameRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Lang override wins per key; `config.yml` (via [TestCurrencies]) is the fallback (SPEC.md FR-L5). */
class CurrencyNamesTest {

    @Test
    fun `an override for both keys wins over config`() {
        val names = CurrencyNames { code, key ->
            if (code == "gems" && key == "singular") "Gemstone"
            else if (code == "gems" && key == "plural") "Gemstones"
            else null
        }

        assertEquals("Gemstone", names.of(TestCurrencies.GEMS, NameRole.SINGULAR))
        assertEquals("Gemstones", names.of(TestCurrencies.GEMS, NameRole.PLURAL))
    }

    @Test
    fun `an override for only one key falls back to config for the other`() {
        val names = CurrencyNames { code, key -> if (code == "gems" && key == "singular") "Gemstone" else null }

        assertEquals("Gemstone", names.of(TestCurrencies.GEMS, NameRole.SINGULAR))
        assertEquals("Gems", names.of(TestCurrencies.GEMS, NameRole.PLURAL), "no plural override; config wins")
    }

    @Test
    fun `a currency absent from the override entirely falls back to config for both keys`() {
        val names = CurrencyNames { code, _ -> if (code == "coins") "Doubloon" else null }

        assertEquals("Gem", names.of(TestCurrencies.GEMS, NameRole.SINGULAR))
        assertEquals("Gems", names.of(TestCurrencies.GEMS, NameRole.PLURAL))
    }

    @Test
    fun `no override at all reads straight from config`() {
        val names = CurrencyNames { _, _ -> null }

        assertEquals("Coin", names.of(TestCurrencies.COINS, NameRole.SINGULAR))
        assertEquals("Coins", names.of(TestCurrencies.COINS, NameRole.PLURAL))
    }

    @Test
    fun `of(amount) derives the role from Currency roleFor`() {
        val names = CurrencyNames { _, _ -> null }

        assertEquals("Gem", names.of(TestCurrencies.GEMS, BigDecimal.ONE))
        assertEquals("Gems", names.of(TestCurrencies.GEMS, BigDecimal("2")))
        assertEquals("Gems", names.of(TestCurrencies.GEMS, BigDecimal.ZERO))
        assertEquals("Gems", names.of(TestCurrencies.GEMS, BigDecimal("-1")))
        // BigDecimal("1.00") != BigDecimal("1") under equals; both are one Gem.
        assertEquals("Gem", names.of(TestCurrencies.GEMS, BigDecimal("1.00")))
    }
}
