package com.the1mason.geckonomy.domain.model

import java.math.BigDecimal

/**
 * Definition of a currency: loaded from config (CONFIGURATION.md §1) and held by
 * [com.the1mason.geckonomy.domain.port.CurrencyRegistry].
 *
 * Immutable, and rebuilt wholesale on `/geckonomy reload` rather than mutated, so a currency cannot
 * change shape underneath an in-flight operation.
 *
 * @property code stable machine id; the storage key.
 * @property singular display name for an amount of one ("Coin").
 * @property plural display name for any other amount ("Coins").
 * @property symbol short display marker ("$").
 * @property fractionalDigits decimal places; `0` means whole units only. All amounts are rounded to
 *   this scale before persistence (SPEC.md FR-B8).
 * @property startingBalance seeded into a new account (SPEC.md FR-A6).
 * @property isDefault whether this is the currency used when a caller names none. Exactly one
 *   currency in the registry has this set — validated at config load (DOMAIN_MODEL.md §4).
 * @property scope how balances are keyed: shared across servers on one database, or private to this
 *   server instance. The domain knows only the scope; infrastructure turns it into a concrete key
 *   (ARCHITECTURE.md §3).
 * @property transferable whether players may `/pay` this currency. A hard rule, checked on top of
 *   permissions, not instead of them (SPEC.md §7).
 * @property checkableOthers whether players may view another player's balance in this currency. Own
 *   balance stays visible either way.
 * @property showInBaltop whether this currency appears in `/baltop`.
 * @property format display template; see LOCALIZATION.md.
 */
data class Currency(
    val code: CurrencyCode,
    val singular: String,
    val plural: String,
    val symbol: String,
    val fractionalDigits: Int,
    val startingBalance: BigDecimal,
    val isDefault: Boolean,
    val scope: CurrencyScope,
    val transferable: Boolean,
    val checkableOthers: Boolean,
    val showInBaltop: Boolean,
    val format: String,
) {

    /**
     * [singular] for exactly one, [plural] for anything else — "1 Gem", "5 Gems", "0 Gems".
     *
     * Lives here rather than in the two places that render names (`FormatMoney`'s `<currency>` and
     * M5's `Placeholders.currency`) because it is one rule about what a currency is called, and two
     * copies of it would eventually disagree about a balance of exactly one.
     *
     * Compares with [BigDecimal.compareTo], not `equals`: `1.00` and `1` are the same amount of money
     * but not equal objects (see [Money]), and a balance of one coin must not read "1.00 Coins".
     */
    fun nameFor(amount: BigDecimal): String =
        if (amount.compareTo(BigDecimal.ONE) == 0) singular else plural
}

/**
 * Whether a currency's balances are shared between servers on the same database, or belong to one
 * server instance alone.
 *
 * The domain deliberately stops here: the concrete scope key (`@global` or the configured server id)
 * is infrastructure's business, so no domain or application code ever handles a server id
 * (DOMAIN_MODEL.md §6).
 */
enum class CurrencyScope {
    /** One balance, shared by every server on the database. */
    NETWORK,

    /** A balance per server instance; servers sharing a database stay independent. */
    SERVER,
}
