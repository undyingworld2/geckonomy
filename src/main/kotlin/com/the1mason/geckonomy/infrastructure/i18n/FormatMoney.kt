package com.the1mason.geckonomy.infrastructure.i18n

import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.Money
import com.the1mason.geckonomy.domain.model.NameRole
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/**
 * Renders [Money] as the `Component` a player reads, per its currency's `format` template
 * (LOCALIZATION.md §4, SPEC.md FR-L4).
 *
 * `symbol`/`singular`/`plural`/`format` are owner-authored MiniMessage source (config or lang file),
 * parsed here; player-supplied text never reaches this class. `<symbol>` and `<currency>` are inserted
 * into `format` as pre-rendered, self-contained components (`Placeholder.component`, not
 * `Placeholder.unparsed`) — a `<gradient>` symbol styles only itself and cannot bleed into the rest of
 * the message, because it is already a finished `Component` by the time `format`'s own markup runs.
 *
 * In `infrastructure`, not `application`: a `Component` is a framework type, and `application` may
 * import only `domain` (CODING_STANDARDS.md §2). `EconomyService` no longer holds this class at all —
 * every consumer (commands, Vault, PlaceholderAPI) is handed this instance directly, the same one, so
 * they cannot disagree (SPEC.md FR-P5).
 *
 * @param locale decides digit grouping only, read per call rather than captured — reloadable
 *   (`settings.language`), same reasoning as `RoundingPolicy` in the composition root.
 * @param names resolves a currency's effective singular/plural (lang override, else config) for the
 *   role [Currency.roleFor] selects.
 */
class FormatMoney(
    private val locale: () -> Locale = { Locale.getDefault() },
    private val names: CurrencyNames,
    private val renderer: MiniMessageRenderer = MiniMessageRenderer(),
) {

    /** [money] as `$100.00`, `5 Gems`, `1 Gem` — whatever its currency's template asks for. */
    operator fun invoke(money: Money): Component {
        val currency = money.currency
        val resolvers = TagResolver.resolver(
            Placeholder.unparsed("amount", amount(money)),
            Placeholder.component("symbol", symbol(currency)),
            Placeholder.component("currency", name(currency, money.amount)),
        )
        return renderer.render(currency.format, resolvers)
    }

    /**
     * Just the grouped digits — `1,000.00` — with no template around them.
     *
     * Split out for the `_commas` placeholder, which wants the number alone. It shares [invoke]'s one
     * `NumberFormat` on purpose: a second one built elsewhere would drift from the currency's
     * `fractionalDigits` and the reloadable locale, and the two would disagree about `1,000.00` in ways
     * nobody would trace back to two formatters. Never MiniMessage — a formatted number cannot carry
     * markup, so this stays a plain `String`.
     */
    fun amount(money: Money): String =
        NumberFormat.getNumberInstance(locale()).apply {
            // Both bounds, so a 2-digit currency shows 100.00 rather than NumberFormat's default 100.
            minimumFractionDigits = money.currency.fractionalDigits
            maximumFractionDigits = money.currency.fractionalDigits
            isGroupingUsed = true
        }.format(money.amount)

    /** [currency]'s symbol, MiniMessage-rendered and self-contained. */
    fun symbol(currency: Currency): Component = renderer.render(currency.symbol, TagResolver.empty())

    /** [currency]'s name for [role] — the effective string from [names], self-contained like [symbol]. */
    fun name(currency: Currency, role: NameRole): Component =
        renderer.render(names.of(currency, role), TagResolver.empty())

    /** [currency]'s name agreeing with [amount] ([Currency.roleFor]). */
    fun name(currency: Currency, amount: BigDecimal): Component = name(currency, currency.roleFor(amount))
}
