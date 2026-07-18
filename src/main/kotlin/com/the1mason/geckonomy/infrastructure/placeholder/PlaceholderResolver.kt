package com.the1mason.geckonomy.infrastructure.placeholder

import com.the1mason.geckonomy.application.usecase.TopBalance
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.Money
import com.the1mason.geckonomy.domain.model.NameRole
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import com.the1mason.geckonomy.infrastructure.balance.OfflineBalanceCache
import com.the1mason.geckonomy.infrastructure.balance.OnlineBalanceMirror
import com.the1mason.geckonomy.infrastructure.i18n.FormatMoney
import com.the1mason.geckonomy.infrastructure.i18n.toLegacyText
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Everything `%geckonomy_…%` means, with none of PlaceholderAPI's types (SPEC.md §4.7).
 *
 * Names no PAPI class on purpose — `GeckonomyExpansion` is the pass-through that does, and keeping
 * the rules on this side means they are testable with plain JUnit. It takes an [AccountId] rather
 * than an `OfflinePlayer` for the same reason, and because half the table needs no player at all.
 *
 * **Never touches the database** (`FR-P7`). Online balances come from the mirror, offline ones from
 * [OfflineBalanceCache] — which answers from memory and fills itself behind the render — and the
 * leaderboard from [BaltopSnapshot].
 *
 * **No flag or permission gating** (`FR-P9`). `transferable`, `checkableOthers` and `showInBaltop`
 * judge an actor or a viewer; PlaceholderAPI supplies neither, only the target. Reaching for
 * `CurrencyAccess` here would be inventing a subject to judge. The consequence is deliberate: a
 * `show-in-baltop: false` currency **is** reachable through `%geckonomy_baltop_*%`. That flag hides a
 * currency from `/baltop`, not from a hologram.
 *
 * ### Shadowing
 * A currency whose code *is* a keyword shadows that variant, because the longest-keyword match wins:
 * with a currency coded `formatted`, `%geckonomy_balance_formatted%` is the formatted default
 * balance, not the raw balance of `formatted`. This is why `_raw` and `_name_singular` exist as
 * explicit spellings — `%geckonomy_balance_raw_formatted%` reaches the shadowed currency, so nothing
 * is unreachable. Config load warns about the collision; it does not refuse it.
 *
 * @param fallback rendered when the shape is understood but the value is not yet known — an offline
 *   player whose first read has not landed, or a rank beyond `baltop-size`. Read per call:
 *   `placeholders.fallback` is reloadable.
 */
internal class PlaceholderResolver(
    private val currencies: CurrencyRegistry,
    private val mirror: OnlineBalanceMirror,
    private val offline: OfflineBalanceCache,
    private val format: FormatMoney,
    private val baltop: BaltopSnapshot,
    private val fallback: () -> String,
) {

    /**
     * [params] is everything after `geckonomy_`; [id] is the player PAPI was asked about, or `null`.
     *
     * `null` means "not ours" and PAPI leaves the text as it found it (`FR-P8`) — an unknown variant,
     * an unknown currency, an unparseable amount or rank. Never a fabricated `0`: a scoreboard
     * reading `$0` is indistinguishable from a real broke player.
     */
    fun resolve(id: AccountId?, params: String): String? {
        val request = parse(params.lowercase()) ?: return null
        return render(id, request)
    }

    private class Request(val variant: PlaceholderVariant, val argument: String, val currency: Currency)

    /**
     * Splits [params] into variant, positional argument and currency.
     *
     * The hard part, and the reason this class exists: **a currency code may contain `_`**
     * (`CurrencyCode`'s pattern is `[a-z0-9_-]+`), so `balance_formatted_my_currency` cannot be split
     * on `_` — greedily or lazily, it is wrong in one direction or the other.
     *
     * What makes it tractable is that the *arguments* cannot contain `_`: a `BigDecimal` literal is
     * digits, `.`, `-`, `+` and `E`, and a rank is digits. So after the keyword, the argument is
     * always exactly the first token and the code is unambiguously all the rest.
     */
    private fun parse(params: String): Request? {
        val variant = PlaceholderVariant.LONGEST_FIRST.firstOrNull { params.startsWith(it.keyword) } ?: return null
        var rest = params.removePrefix(variant.keyword)

        var argument = ""
        if (variant.takesArgument) {
            if (!rest.startsWith("_")) return null
            rest = rest.removePrefix("_")
            argument = rest.substringBefore("_")
            if (argument.isEmpty()) return null
            rest = rest.removePrefix(argument)
        }

        val currency = when {
            rest.isEmpty() -> currencies.default()
            // A trailing remainder that is not `_code` is not a currency and not ours: `balancex`
            // must not resolve as the default balance.
            !rest.startsWith("_") -> return null
            else -> CurrencyCode.parseOrNull(rest.removePrefix("_"))?.let(currencies::byCode) ?: return null
        }
        return Request(variant, argument, currency)
    }

    private fun render(id: AccountId?, request: Request): String? {
        val currency = request.currency
        return when (request.variant) {
            PlaceholderVariant.SYMBOL -> format.symbol(currency).toLegacyText()
            PlaceholderVariant.NAME, PlaceholderVariant.NAME_SINGULAR ->
                format.name(currency, NameRole.SINGULAR).toLegacyText()
            PlaceholderVariant.NAME_PLURAL -> format.name(currency, NameRole.PLURAL).toLegacyText()
            PlaceholderVariant.DIGITS -> currency.fractionalDigits.toString()

            PlaceholderVariant.FORMAT ->
                request.argument.toBigDecimalOrNull()?.let { format(Money(it, currency)).toLegacyText() }

            PlaceholderVariant.BALANCE, PlaceholderVariant.BALANCE_RAW ->
                balance(id, currency)?.toPlainString() ?: fallback()
            PlaceholderVariant.BALANCE_FORMATTED ->
                balance(id, currency)?.let { format(Money(it, currency)).toLegacyText() } ?: fallback()
            PlaceholderVariant.BALANCE_COMMAS ->
                balance(id, currency)?.let { format.amount(Money(it, currency)) } ?: fallback()
            // DOWN, not the configured rounding mode: this is Vault's `_fixed` — the whole units a
            // player actually has — and rounding 1.99 up to "2" would show money they cannot spend.
            PlaceholderVariant.BALANCE_FIXED ->
                balance(id, currency)?.setScale(0, RoundingMode.DOWN)?.toPlainString() ?: fallback()
            PlaceholderVariant.BALANCE_NAME ->
                balance(id, currency)?.let { format.name(currency, it).toLegacyText() } ?: fallback()

            PlaceholderVariant.BALTOP_PLAYER ->
                row(request) { it?.name }
            PlaceholderVariant.BALTOP_BALANCE ->
                row(request) { it?.balance?.amount?.toPlainString() }
            PlaceholderVariant.BALTOP_BALANCE_FORMATTED ->
                row(request) { it?.balance?.let { money -> format(money).toLegacyText() } }
            PlaceholderVariant.BALTOP_RANK ->
                id?.let { baltop.rankOf(currency.code, it) }?.toString() ?: fallback()
        }
    }

    /**
     * The mirror for an online player, the self-filling cache for anyone else.
     *
     * Order matters: an online player's balance is the mirror's business, and asking the cache first
     * would serve a value up to its TTL out of date for the players most likely to be looked at.
     */
    private fun balance(id: AccountId?, currency: Currency): BigDecimal? {
        if (id == null) return null
        return mirror.get(id, currency.code) ?: offline.get(id, currency.code)
    }

    /**
     * A leaderboard row, distinguishing **malformed** from merely **absent** — the two answers the
     * table's rank arms need, and the one place the spec had to be reconciled rather than followed.
     *
     * `baltop_player_abc` and `baltop_player_0` are ranks that cannot exist: nothing would ever fill
     * them, so they are `null` and PAPI leaves the text alone, the same as an unknown variant.
     *
     * A well-formed rank the snapshot does not hold is different — rank 3 of two accounts, or any
     * rank at all in the first minute before the timer's first refresh lands. Those are *not yet* or
     * *not currently* known, which is what [fallback] is for. Answering `null` there would print raw
     * `%geckonomy_baltop_player_1%` across every scoreboard for the first refresh interval of every
     * server start.
     */
    private fun row(request: Request, render: (TopBalance?) -> String?): String? {
        val rank = request.argument.toIntOrNull()?.takeIf { it >= 1 } ?: return null
        return render(baltop.at(request.currency.code, rank)) ?: fallback()
    }
}
