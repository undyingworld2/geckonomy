package com.the1mason.geckonomy.domain.policy

import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CurrencyValidationTest {

    /**
     * Hand-written rather than mocked: the port is three functions, and a real map reads better than
     * stubbing would (CODING_STANDARDS.md §6 — prefer deterministic, obvious tests).
     */
    private class FakeCurrencyRegistry(private val currencies: List<Currency>) : CurrencyRegistry {
        override fun all(): Collection<Currency> = currencies
        override fun default(): Currency = currencies.first(Currency::isDefault)
        override fun byCode(code: CurrencyCode): Currency? = currencies.find { it.code == code }
    }

    private val validation = CurrencyValidation(
        FakeCurrencyRegistry(listOf(TestCurrencies.COINS, TestCurrencies.GEMS)),
    )


    @Test
    fun `resolves a configured code to its currency`() {
        assertEquals(
            CurrencyResolution.Resolved(TestCurrencies.COINS),
            validation.resolve(CurrencyCode("coins")),
        )
    }

    @Test
    fun `resolves the non-default currency too`() {
        assertEquals(
            CurrencyResolution.Resolved(TestCurrencies.GEMS),
            validation.resolve(CurrencyCode("gems")),
        )
    }

    @Test
    fun `reports an unconfigured code as Unknown, naming the code`() {
        assertEquals(
            CurrencyResolution.Unknown(CurrencyCode("coinz")),
            validation.resolve(CurrencyCode("coinz")),
        )
    }

    @Test
    fun `resolves regardless of the case the code was written in`() {
        assertEquals(
            CurrencyResolution.Resolved(TestCurrencies.COINS),
            validation.resolve(CurrencyCode("COINS")),
        )
    }

    @Test
    fun `an empty registry resolves nothing`() {
        val empty = CurrencyValidation(FakeCurrencyRegistry(emptyList()))

        assertEquals(CurrencyResolution.Unknown(CurrencyCode("coins")), empty.resolve(CurrencyCode("coins")))
    }
}
