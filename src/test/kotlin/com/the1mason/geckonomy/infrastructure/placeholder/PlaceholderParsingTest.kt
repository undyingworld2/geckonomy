package com.the1mason.geckonomy.infrastructure.placeholder

import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.infrastructure.config.ConfigCurrencyRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

/**
 * Splitting `params` into variant, argument and currency.
 *
 * **The point of M9.** A currency code may contain `_` (`CurrencyCode`'s pattern is `[a-z0-9_-]+`),
 * so `balance_formatted_my_currency` cannot be split on `_` — greedy and lazy are each wrong in one
 * direction. These cases are the ones that catch it.
 */
class PlaceholderParsingTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    /** A code with an underscore in it — the whole reason the parser cannot split naively. */
    private val myCurrency = currency("my_currency", symbol = "M", format = "<symbol><amount>")

    /** A code that *is* a keyword, for the shadowing rules. */
    private val formatted = currency("formatted", symbol = "F", format = "<symbol><amount>")

    private fun fixture(vararg extra: Currency) = PlaceholderFixture(
        economy = com.the1mason.geckonomy.application.EconomyFixture(
            currencies = ConfigCurrencyRegistry(listOf(TestCurrencies.COINS, TestCurrencies.GEMS) + extra),
        ),
        scope = scope,
    )

    private fun currency(code: String, symbol: String, format: String) = Currency(
        code = CurrencyCode(code),
        singular = "Unit",
        plural = "Units",
        symbol = symbol,
        fractionalDigits = 2,
        startingBalance = BigDecimal.ZERO,
        isDefault = false,
        scope = TestCurrencies.COINS.scope,
        transferable = true,
        checkableOthers = true,
        showInBaltop = true,
        format = format,
    )

    @Test
    fun `an underscored code is not eaten by the variant keyword`() {
        val f = fixture(myCurrency)
        f.online(PlaceholderFixture.ALICE, myCurrency.code, "1234.56")

        // The trap: a greedy split reads the variant as `balance` and the currency as
        // `formatted_my_currency`, which does not exist, so this would be null.
        assertEquals("M1,234.56", f.resolve("balance_formatted_my_currency"))
    }

    @Test
    fun `an underscored code resolves through every variant`() {
        val f = fixture(myCurrency)
        f.online(PlaceholderFixture.ALICE, myCurrency.code, "1234.56")

        assertEquals("1234.56", f.resolve("balance_my_currency"))
        assertEquals("1234.56", f.resolve("balance_raw_my_currency"))
        assertEquals("M1,234.56", f.resolve("balance_formatted_my_currency"))
        assertEquals("1,234.56", f.resolve("balance_commas_my_currency"))
        assertEquals("1234", f.resolve("balance_fixed_my_currency"))
        assertEquals("Units", f.resolve("balance_name_my_currency"))
        assertEquals("M", f.resolve("symbol_my_currency"))
        assertEquals("Unit", f.resolve("name_my_currency"))
        assertEquals("Units", f.resolve("name_plural_my_currency"))
        assertEquals("2", f.resolve("digits_my_currency"))
        assertEquals("M9.99", f.resolve("format_9.99_my_currency"))
    }

    /** The doc's parsing table, verbatim. `arg` and `code` are what each row must decompose to. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "balance,                       '1234.56'",
        "balance_formatted_my_currency, 'M1,234.56'",
        "format_1234.56_my_currency,    'M1,234.56'",
        "baltop_player_3_my_currency,   '0'",
        "baltop_player_3,               '0'",
        "baltop_balance_formatted_2,    '0'",
    )
    fun `the parsing table`(params: String, expected: String) {
        val f = fixture(myCurrency)
        f.online(PlaceholderFixture.ALICE, PlaceholderFixture.COINS, "1234.56")
        f.online(PlaceholderFixture.ALICE, myCurrency.code, "1234.56")

        assertEquals(expected, f.resolve(params.trim()))
    }

    @Test
    fun `a currency coded as a keyword is shadowed but reachable`() {
        val f = fixture(formatted)
        f.online(PlaceholderFixture.ALICE, PlaceholderFixture.COINS, "10")
        f.online(PlaceholderFixture.ALICE, formatted.code, "77")

        // Shadowed: longest keyword wins, so this is the *formatted default* balance...
        assertEquals("$10.00", f.resolve("balance_formatted"))
        // ...and the explicit spelling is what reaches the currency. Nothing is unreachable.
        assertEquals("77", f.resolve("balance_raw_formatted"))
    }

    @Test
    fun `name_singular reaches a currency coded name`() {
        val named = currency("name", symbol = "N", format = "<amount>")
        val f = fixture(named)

        // `name` alone is the keyword (the default currency's singular), not the currency `name`.
        assertEquals("Coin", f.resolve("name"))
        assertEquals("Unit", f.resolve("name_singular_name"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "nonsense",
            "balancex",
            "balance_nope",
            "format_notanumber",
            "format_1.5_nope",
            "baltop_player_abc",
            "baltop_player_0",
            "baltop_player_-1",
            "format",
            "baltop_player",
        ],
    )
    fun `unresolvable renders null so PAPI leaves the text alone`(params: String) {
        assertNull(fixture().resolve(params))
    }

    @Test
    fun `an uppercase placeholder resolves`() {
        val f = fixture()
        f.online(PlaceholderFixture.ALICE, PlaceholderFixture.COINS, "5")

        assertEquals("5", f.resolve("BALANCE_COINS"))
    }
}
