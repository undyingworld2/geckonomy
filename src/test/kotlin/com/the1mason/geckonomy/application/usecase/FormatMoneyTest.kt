package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Locale

class FormatMoneyTest {

    // Fixed, not the JVM default: grouping separators differ by locale, and a suite that passed in
    // en-US and failed in de-DE would be testing the machine, not the code.
    private val format = FormatMoney { Locale.US }

    @Test
    fun `renders the symbol-first template`() {
        assertEquals("$100.00", format(Money(BigDecimal("100.00"), TestCurrencies.COINS)))
    }

    @Test
    fun `pads to the currency's fractional digits`() {
        // format: "<symbol><amount>" — a 2-digit currency shows both, even for a whole number.
        assertEquals("$5.00", format(Money(BigDecimal("5"), TestCurrencies.COINS)))
    }

    @Test
    fun `groups thousands`() {
        assertEquals("$1,234,567.89", format(Money(BigDecimal("1234567.89"), TestCurrencies.COINS)))
    }

    @Test
    fun `uses the plural name for an amount that is not one`() {
        assertEquals("5 Gems", format(Money(BigDecimal("5"), TestCurrencies.GEMS)))
    }

    @Test
    fun `uses the singular name for exactly one`() {
        assertEquals("1 Gem", format(Money(BigDecimal("1"), TestCurrencies.GEMS)))
    }

    @Test
    fun `picks the singular by value, not by scale`() {
        // BigDecimal("1.00") != BigDecimal("1") under equals; both are one Coin.
        val coins = TestCurrencies.COINS.copy(format = "<amount> <currency>")

        assertEquals("1.00 Coin", format(Money(BigDecimal("1.00"), coins)))
    }

    @Test
    fun `renders zero and negatives with the plural`() {
        val coins = TestCurrencies.COINS.copy(format = "<amount> <currency>")

        assertEquals("0.00 Coins", format(Money(BigDecimal("0"), coins)))
        assertEquals("-1.00 Coins", format(Money(BigDecimal("-1.00"), coins)))
    }

    @Test
    fun `renders a template using every placeholder`() {
        val coins = TestCurrencies.COINS.copy(format = "<symbol><amount> <currency>")

        assertEquals("$2.50 Coins", format(Money(BigDecimal("2.50"), coins)))
    }

    @Test
    fun `leaves text it substituted alone`() {
        // The reason for one regex pass instead of chained replace(): a symbol containing a
        // placeholder must be inserted literally, not rescanned and substituted again.
        val odd = TestCurrencies.COINS.copy(symbol = "<amount>", format = "<symbol><amount>")

        assertEquals("<amount>7.00", format(Money(BigDecimal("7.00"), odd)))
    }

    @Test
    fun `leaves a template with no placeholders alone`() {
        val free = TestCurrencies.COINS.copy(format = "free")

        assertEquals("free", format(Money(BigDecimal("7.00"), free)))
    }

    @Test
    fun `groups by the locale it is given`() {
        // M5 wires this to settings.language, so a German server reads German text *and* German
        // grouping rather than one of each.
        val german = FormatMoney { Locale.GERMANY }

        assertEquals("$1.234.567,89", german(Money(BigDecimal("1234567.89"), TestCurrencies.COINS)))
    }

    @Test
    fun `reads the locale per call, so a reload can change it`() {
        // The supplier is not decoration: settings.language is reloadable, and a captured locale would
        // make /geckonomy reload report success while formatting the old way forever.
        var locale = Locale.US
        val reloadable = FormatMoney { locale }
        assertEquals("$1,000.00", reloadable(Money(BigDecimal("1000.00"), TestCurrencies.COINS)))

        locale = Locale.GERMANY

        assertEquals("$1.000,00", reloadable(Money(BigDecimal("1000.00"), TestCurrencies.COINS)))
    }
}
