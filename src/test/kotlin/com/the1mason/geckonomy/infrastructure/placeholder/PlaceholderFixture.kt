package com.the1mason.geckonomy.infrastructure.placeholder

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.infrastructure.balance.OfflineBalanceCache
import com.the1mason.geckonomy.infrastructure.balance.OnlineBalanceMirror
import kotlinx.coroutines.CoroutineScope
import java.math.BigDecimal
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [PlaceholderResolver] over a **real** [com.the1mason.geckonomy.application.service.EconomyService]
 * on in-memory ports, wired the way `PlaceholderRegistration` wires it.
 *
 * A real service rather than a mocked one, per the `EconomyFixture` pattern: the placeholders'
 * whole job is rendering what the economy actually says, and a mock would let the two agree on
 * something false.
 */
internal class PlaceholderFixture(
    val economy: EconomyFixture = EconomyFixture(),
    scope: CoroutineScope,
    ttl: () -> Duration = { 60.seconds },
    private val nanos: () -> Long = { 0L },
    var fallback: String = "0",
    var baltopSize: Int = 10,
) {

    val mirror = OnlineBalanceMirror()

    val offline = OfflineBalanceCache(
        economy = economy.service,
        scope = scope,
        ttl = ttl,
        logger = silent(),
        nanos = { nanos() },
    )

    val baltop = BaltopSnapshot(
        economy = economy.service,
        currencies = economy.currencies,
        size = { baltopSize },
        interval = { 60.seconds },
        logger = silent(),
    )

    val resolver = PlaceholderResolver(
        currencies = economy.currencies,
        mirror = mirror,
        offline = offline,
        format = economy.format,
        baltop = baltop,
        fallback = { fallback },
    )

    fun resolve(params: String, id: AccountId? = ALICE): String? = resolver.resolve(id, params)

    private val mirrored = mutableMapOf<AccountId, MutableMap<CurrencyCode, BigDecimal>>()

    /**
     * Puts [id] in the mirror, which is what "the player is online" means to a placeholder.
     *
     * Accumulates rather than replacing: `hydrate` overwrites everything held for an account, so two
     * calls naming different currencies would leave only the second — the fixture would silently
     * disagree with production, where a player is hydrated once with every currency at a time.
     */
    fun online(id: AccountId, currency: CurrencyCode, amount: String) {
        val balances = mirrored.getOrPut(id) { mutableMapOf() }
        balances[currency] = BigDecimal(amount)
        mirror.hydrate(id, balances)
    }

    companion object {
        val ALICE = EconomyFixture.ALICE
        val BOB = EconomyFixture.BOB
        val CAROL = EconomyFixture.CAROL

        val COINS = CurrencyCode("coins")

        fun silent(): Logger = Logger.getAnonymousLogger().apply { level = Level.OFF }
    }
}
