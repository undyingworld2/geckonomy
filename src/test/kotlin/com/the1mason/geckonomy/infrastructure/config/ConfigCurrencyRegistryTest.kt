package com.the1mason.geckonomy.infrastructure.config

import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConfigCurrencyRegistryTest {

    private val registry = ConfigCurrencyRegistry(listOf(TestCurrencies.COINS, TestCurrencies.GEMS))

    @Test
    fun `holds every configured currency`() {
        assertEquals(listOf(TestCurrencies.COINS, TestCurrencies.GEMS), registry.all().toList())
    }

    @Test
    fun `finds the currency marked default`() {
        assertEquals(TestCurrencies.COINS, registry.default())
    }

    @Test
    fun `finds a currency by code`() {
        assertEquals(TestCurrencies.GEMS, registry.byCode(CurrencyCode("gems")))
    }

    @Test
    fun `finds it however the caller cased the code`() {
        assertEquals(TestCurrencies.GEMS, registry.byCode(CurrencyCode("GEMS")))
    }

    @Test
    fun `reports an unconfigured code as absent rather than guessing`() {
        assertNull(registry.byCode(CurrencyCode("coinz")))
    }

    /**
     * The point of the whole design: M4's use cases capture this port once at wiring
     * (ARCHITECTURE.md §7), so a reload must reach them through the reference they already hold.
     */
    @Test
    fun `a reference taken before a reload sees the currencies from after it`() {
        val held: CurrencyRegistry = registry
        val renamedGems = TestCurrencies.GEMS.copy(plural = "Gemstones")

        registry.replaceWith(listOf(TestCurrencies.COINS, renamedGems))

        assertEquals("Gemstones", held.byCode(CurrencyCode("gems"))?.plural)
    }

    @Test
    fun `a currency dropped by a reload stops resolving`() {
        registry.replaceWith(listOf(TestCurrencies.COINS))

        assertNull(registry.byCode(CurrencyCode("gems")))
        assertEquals(listOf(TestCurrencies.COINS), registry.all().toList())
    }

    @Test
    fun `a reload can move the default to another currency`() {
        registry.replaceWith(listOf(TestCurrencies.COINS.copy(isDefault = false), TestCurrencies.GEMS.copy(isDefault = true)))

        assertEquals(CurrencyCode("gems"), registry.default().code)
    }

    /**
     * Only [ConfigLoader] output belongs here, and it guarantees a default. Failing loudly at the
     * swap beats handing back a registry whose `default()` throws later, in the middle of someone's
     * transaction.
     */
    @Test
    fun `refuses a currency set with no default, at the moment it is handed over`() {
        assertThrows<NoSuchElementException> {
            ConfigCurrencyRegistry(listOf(TestCurrencies.GEMS))
        }
    }
}
