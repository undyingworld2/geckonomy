package com.the1mason.geckonomy.infrastructure.i18n

import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.Money
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import java.math.BigDecimal

/**
 * Turns a MiniMessage template into a [Component] (LOCALIZATION.md §2).
 *
 * Thin on purpose: MiniMessage does the work, and this exists so that the rest of the plugin talks to
 * one object it can hand a fake of, instead of every caller reaching for `MiniMessage.miniMessage()`.
 *
 * **Pure and thread-safe** — no IO, no Bukkit, no state. Rendering may happen on any thread; only
 * *sending* the result is main-thread business (CODING_STANDARDS.md §3).
 *
 * @param miniMessage injectable for tests; the default is the shared instance Paper bundles.
 */
class MiniMessageRenderer(private val miniMessage: MiniMessage = MiniMessage.miniMessage()) {

    /**
     * [template] with [resolvers] applied.
     *
     * Tags the template names but [resolvers] does not supply are left as literal text rather than
     * throwing: a language file is edited by server owners, and a typo there should look wrong, not
     * kill the command that rendered it.
     */
    fun render(template: String, resolvers: TagResolver): Component =
        miniMessage.deserialize(template, resolvers)
}

/**
 * Builders for the placeholders LOCALIZATION.md §3 defines.
 *
 * Two kinds of value, two rules. Player-supplied text (a name, mostly) is always
 * [Placeholder.unparsed] — a template author never wrote it, and parsing it would let a player author
 * markup in someone else's chat. Currency-owned values (`<symbol>`, `<currency>`, `<formatted>`) are
 * owner-authored MiniMessage (config or lang file); [FormatMoney] renders each to a self-contained
 * `Component` and this inserts it via [Placeholder.component] — already-finished, so it styles only
 * itself and cannot bleed into the surrounding message (SPEC.md FR-L4). Centralizing both here means a
 * caller cannot get either wrong by forgetting; it is only wrong if it deliberately bypasses these.
 */
object Placeholders {

    /**
     * A player-supplied string — a name, mostly.
     *
     * The security-relevant one: a player called `<red>Notch</red>` renders as those literal
     * characters. Without this, players would author markup in other players' chat by renaming
     * themselves (LOCALIZATION.md §3).
     */
    fun text(tag: String, value: String): TagResolver = Placeholder.unparsed(tag, value)

    /** A count — `<rank>`, mostly. */
    fun number(tag: String, value: Number): TagResolver = Placeholder.unparsed(tag, value.toString())

    /** An amount, rendered through its currency's own template — `$100.00`, `5 Gems` — as a component. */
    fun money(tag: String, money: Money, format: FormatMoney): TagResolver =
        Placeholder.component(tag, format(money))

    /**
     * The currency's own names: `<symbol>`, and `<currency>` as singular or plural to suit [amount].
     *
     * For messages that name a currency without an amount attached — `error.unknown-currency`,
     * `baltop.header` — where there is no [Money] to format. [amount] defaults to zero, which reads as
     * the plural, because that is what a currency named in the abstract wants ("Top balances (Coins)").
     * [format] is threaded through rather than adding a second renderer/resolver pair to every call
     * site: every caller already holds a [FormatMoney].
     */
    fun currency(currency: Currency, format: FormatMoney, amount: BigDecimal = BigDecimal.ZERO): TagResolver =
        TagResolver.resolver(
            Placeholder.component("symbol", format.symbol(currency)),
            Placeholder.component("currency", format.name(currency, amount)),
        )

    /** Several resolvers as one, so callers do not each import [TagResolver]. */
    fun of(vararg resolvers: TagResolver): TagResolver = TagResolver.resolver(*resolvers)
}
