package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.domain.model.Money
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
 * @param locale decides digit grouping only, and is read per call rather than captured. M5 wires it to
 *   `Locale.forLanguageTag(settings.language)`, which is reloadable: `ConfigService.restartWarnings`
 *   deliberately says nothing about `settings.language`, and that silence is a promise that
 *   `/geckonomy reload` applies it. A captured locale would quietly break that promise — the reload
 *   would report success and keep formatting the old way. It is a supplier for the same reason
 *   `RoundingPolicy` is one in the composition root.
 */
class FormatMoney(private val locale: () -> Locale = { Locale.getDefault() }) {

    /** [money] as `$100.00`, `5 Gems`, `1 Gem` — whatever its currency's template asks for. */
    operator fun invoke(money: Money): String {
        val currency = money.currency
        val amount = amount(money)

        // One pass over the template, not a chain of replace() calls. Chained, each replacement would
        // re-scan text an earlier one inserted, so a currency whose symbol contained "<amount>" would
        // have it substituted — a config typo turning into gibberish. One pass makes inserted text inert.
        return PLACEHOLDER.replace(currency.format) { match ->
            when (match.value) {
                "<amount>" -> amount
                "<symbol>" -> currency.symbol
                else -> currency.nameFor(money.amount)
            }
        }
    }

    /**
     * Just the grouped digits — `1,000.00` — with no template around them.
     *
     * Split out for the `_commas` placeholder, which wants the number alone. It shares [invoke]'s
     * one `NumberFormat` on purpose: a second one built elsewhere would drift from the currency's
     * `fractionalDigits` and the reloadable locale, and the two would disagree about `1,000.00` in
     * ways nobody would trace back to two formatters.
     */
    fun amount(money: Money): String =
        NumberFormat.getNumberInstance(locale()).apply {
            // Both bounds, so a 2-digit currency shows 100.00 rather than NumberFormat's default 100.
            minimumFractionDigits = money.currency.fractionalDigits
            maximumFractionDigits = money.currency.fractionalDigits
            isGroupingUsed = true
        }.format(money.amount)

    private companion object {
        /** `NumberFormat` is not thread-safe, so it is built per call; this is the only shareable part. */
        val PLACEHOLDER = Regex("<amount>|<symbol>|<currency>")
    }
}
