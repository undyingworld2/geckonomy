package com.the1mason.geckonomy.infrastructure.bukkit

import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import com.the1mason.geckonomy.infrastructure.i18n.MessageKey
import org.bukkit.permissions.Permissible

/**
 * What a player may do with a currency, and what to say when they may not (SPEC.md §7, FR-C6,
 * FR-CMD4).
 *
 * Two independent gates, which the spec is careful to keep apart:
 *
 * - **Config flag** — `transferable`, `balance-check-others`, `show-in-baltop`. A *hard* gate: no
 *   permission grants past it, because it describes what the currency is, not who the player is.
 * - **Permission** — the per-currency node, on top of the base node the command itself requires.
 *
 * One class rather than a check inlined per command, because tab completion asks the same question as
 * the handler: [permitted] is [refusal] read backwards over every currency. Offering a currency in
 * completion and then refusing it is a bug the player sees, and sharing the rule is what forecloses it.
 */
internal class CurrencyAccess(private val currencies: CurrencyRegistry) {

    /**
     * Something a player can want to do with a currency.
     *
     * @property flagRefusal what a *flag* refusal says. A permission refusal always says
     *   `no-currency-permission`; a flag refusal is more specific where a message exists for it, and
     *   telling a player they lack permission to pay a currency that nobody can pay would send them to
     *   an admin who cannot help.
     */
    enum class Action(
        private val suffix: String,
        private val allows: (Currency) -> Boolean,
        val flagRefusal: MessageKey,
    ) {
        /** Own balance. No flag gates it: a player may always see what they hold. */
        BALANCE("balance", { true }, MessageKey.ERROR_NO_CURRENCY_PERMISSION),
        BALANCE_OTHERS("balance.others", Currency::checkableOthers, MessageKey.ERROR_OTHERS_HIDDEN),
        PAY("pay", Currency::transferable, MessageKey.ERROR_NOT_TRANSFERABLE),

        /** `show-in-baltop: false` reads as "you can't use it here" — it is absent, not forbidden. */
        BALTOP("baltop", Currency::showInBaltop, MessageKey.ERROR_NO_CURRENCY_PERMISSION),
        ;

        /** Whether [currency]'s own config permits this at all. */
        fun allows(currency: Currency): Boolean = allows.invoke(currency)

        /** The base node the command requires before any currency is considered. */
        val base: String get() = "geckonomy.$suffix"

        /** `geckonomy.pay.coins` — the per-currency node. */
        fun node(currency: Currency): String = "$base.${currency.code.value}"

        /** `geckonomy.pay.*` — what a server grants to mean "every currency, for this action". */
        val wildcard: String get() = "$base.*"
    }

    /**
     * Why [who] may not use [currency] for [action], or `null` if they may.
     *
     * Flag first, permission second — see [Action.flagRefusal].
     */
    fun refusal(who: Permissible, action: Action, currency: Currency): MessageKey? = when {
        !action.allows(currency) -> action.flagRefusal
        !who.hasPermission(action.node(currency)) -> MessageKey.ERROR_NO_CURRENCY_PERMISSION
        else -> null
    }

    /** The currencies [who] may use for [action], default first — tab completion's list. */
    fun permitted(who: Permissible, action: Action): List<Currency> =
        currencies.all()
            .sortedByDescending { it.isDefault }
            .filter { refusal(who, action, it) == null }
}
