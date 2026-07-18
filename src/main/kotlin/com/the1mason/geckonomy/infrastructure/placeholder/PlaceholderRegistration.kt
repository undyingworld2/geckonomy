package com.the1mason.geckonomy.infrastructure.placeholder

import com.the1mason.geckonomy.application.service.EconomyService
import com.the1mason.geckonomy.infrastructure.i18n.FormatMoney
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import com.the1mason.geckonomy.infrastructure.balance.OfflineBalanceCache
import com.the1mason.geckonomy.infrastructure.balance.OnlineBalanceMirror
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.util.logging.Logger
import kotlin.time.Duration

/**
 * Builds the expansion and puts it in PlaceholderAPI (SPEC.md FR-P1).
 *
 * **The only class in the plugin that names a PlaceholderAPI type at wiring time**, mirroring
 * `VaultRegistration`: PAPI is a soft dependency, so its classes may be absent, and touching one when
 * it is means a `NoClassDefFoundError` during enable. Everything PAPI-shaped stays behind this class
 * so the composition root can check first and never load it.
 *
 * @param baltopRefresh how often the leaderboard snapshot rebuilds. A supplier: reloadable.
 */
internal class PlaceholderRegistration(
    economy: EconomyService,
    currencies: CurrencyRegistry,
    mirror: OnlineBalanceMirror,
    offline: OfflineBalanceCache,
    format: FormatMoney,
    baltopSize: () -> Int,
    baltopRefresh: () -> Duration,
    fallback: () -> String,
    private val scope: CoroutineScope,
    version: String,
    author: String,
    logger: Logger,
) : AutoCloseable {

    private val baltop = BaltopSnapshot(
        economy = economy,
        currencies = currencies,
        size = baltopSize,
        interval = baltopRefresh,
        logger = logger,
        // The sweep rides the snapshot's timer rather than starting a second one: it needs no
        // precision, only to happen, and one loop is one thing to cancel.
        onRefreshed = offline::sweep,
    )

    private val expansion = GeckonomyExpansion(
        resolver = PlaceholderResolver(
            currencies = currencies,
            mirror = mirror,
            offline = offline,
            format = format,
            baltop = baltop,
            fallback = fallback,
        ),
        version = version,
        author = author,
    )

    private var refresher: Job? = null

    fun register() {
        expansion.register()
        refresher = baltop.start(scope)
    }

    override fun close() {
        // Unregister first: while it is registered PAPI can still call onRequest, and the snapshot
        // below is what that call reads.
        expansion.unregister()
        refresher?.cancel()
        refresher = null
    }
}
