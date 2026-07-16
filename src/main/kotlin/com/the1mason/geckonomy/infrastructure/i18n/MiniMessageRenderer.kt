package com.the1mason.geckonomy.infrastructure.i18n

import com.the1mason.geckonomy.application.usecase.FormatMoney
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
 * They exist for one rule: **every value inserted here is unparsed**. A value reaches a template from
 * a player's name, a config-authored currency symbol, or a formatted amount built from both — none of
 * which is markup the template's author wrote, and all of which would be parsed as MiniMessage if
 * handed over with [Placeholder.parsed]. Centralizing that here means a caller cannot get it wrong by
 * forgetting; it is only wrong if it deliberately bypasses these.
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

    /**
     * An amount, rendered through its currency's own template — `$100.00`, `5 Gems`.
     *
     * Unparsed like the rest, and here the reason is the currency `symbol`: it comes from `config.yml`,
     * so a symbol of `<rainbow>` would otherwise colour the rest of the message from inside what is
     * supposed to be a value.
     */
    fun money(tag: String, money: Money, format: FormatMoney): TagResolver =
        Placeholder.unparsed(tag, format(money))

    /**
     * The currency's own names: `<symbol>`, and `<currency>` as singular or plural to suit [amount].
     *
     * For messages that name a currency without an amount attached — `error.unknown-currency`,
     * `baltop.header` — where [money] has nothing to format. [amount] defaults to zero, which reads as
     * the plural, because that is what a currency named in the abstract wants ("Top balances (Coins)").
     */
    fun currency(currency: Currency, amount: BigDecimal = BigDecimal.ZERO): TagResolver =
        TagResolver.resolver(
            text("symbol", currency.symbol),
            text("currency", currency.nameFor(amount)),
        )

    /** Several resolvers as one, so callers do not each import [TagResolver]. */
    fun of(vararg resolvers: TagResolver): TagResolver = TagResolver.resolver(*resolvers)
}
