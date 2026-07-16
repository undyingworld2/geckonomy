package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.domain.model.Money
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/**
 * Renders [Money] as the string a player reads, per its currency's `format` template
 * (LOCALIZATION.md §4).
 *
 * Returns a plain [String], not an Adventure `Component`: `application` may not import Adventure
 * (CODING_STANDARDS.md §2), and M5's renderer inserts this as `<formatted>` — as **unparsed** text,
 * so a currency's symbol can never smuggle MiniMessage tags into a message.
 *
 * @param locale decides digit grouping only. `Locale.getDefault()` in v1: `settings.language` names a
 *   file, not a locale, so there is nothing better to derive one from yet. M5 may inject one when
 *   per-player language lands.
 */
class FormatMoney(private val locale: Locale = Locale.getDefault()) {

    /** [money] as `$100.00`, `5 Gems`, `1 Gem` — whatever its currency's template asks for. */
    operator fun invoke(money: Money): String {
        val currency = money.currency
        val amount = NumberFormat.getNumberInstance(locale).apply {
            // Both bounds, so a 2-digit currency shows 100.00 rather than NumberFormat's default 100.
            minimumFractionDigits = currency.fractionalDigits
            maximumFractionDigits = currency.fractionalDigits
            isGroupingUsed = true
        }.format(money.amount)

        // One pass over the template, not a chain of replace() calls. Chained, each replacement would
        // re-scan text an earlier one inserted, so a currency whose symbol contained "<amount>" would
        // have it substituted — a config typo turning into gibberish. One pass makes inserted text inert.
        return PLACEHOLDER.replace(currency.format) { match ->
            when (match.value) {
                "<amount>" -> amount
                "<symbol>" -> currency.symbol
                // compareTo, not equals: BigDecimal("1.00") != BigDecimal("1") (Money's KDoc), and a
                // balance of exactly one coin must not read "1.00 Coins".
                else -> if (money.amount.compareTo(BigDecimal.ONE) == 0) currency.singular else currency.plural
            }
        }
    }

    private companion object {
        /** `NumberFormat` is not thread-safe, so it is built per call; this is the only shareable part. */
        val PLACEHOLDER = Regex("<amount>|<symbol>|<currency>")
    }
}
