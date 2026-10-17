package com.the1mason.geckonomy.infrastructure.balance

import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.service.EconomyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Re-reads every online player's balances into [OnlineBalanceMirror] on a timer.
 *
 * Every write path we own updates the mirror itself; this catches the ones that cannot — another server
 * writing a shared database, the async Vault API, a manual SQL fix. Without it such a change shows only
 * after the player's next sync write or relog, and on SQLite or a `server` currency nothing else ever
 * refreshes the entry.
 *
 * @param interval `settings.mirror-refresh-seconds`, sampled once per cycle: a reload takes effect after
 *   the in-flight sleep.
 */
class MirrorRefresher(
    private val economy: EconomyService,
    private val mirror: OnlineBalanceMirror,
    private val interval: () -> Duration,
    private val logger: Logger,
) {

    /** Refreshes forever on [scope]; cancelled with it on disable. Sleeps first: a join just hydrated. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            delay(interval())
            refreshAll()
        }
    }

    /**
     * A failed read keeps the old value — `StorageGuard` already logged it. A throw is caught per cycle
     * for `BaltopSnapshot`'s reason: uncaught, it would end the loop silently and freeze the mirror.
     */
    internal suspend fun refreshAll() {
        try {
            val online = mirror.accounts()
            if (online.isEmpty()) return
            // One query per currency for the whole server, not one per player per currency: on a
            // network sharing one MariaDB, every server runs this, and the old shape was
            // players × currencies round trips each cycle.
            for (currency in economy.currencies()) {
                // Captured before the read begins: what refresh compares against is what the entry
                // held when the read was *issued*, so a write landing during it is never overwritten.
                val seen = online.associateWith { mirror.get(it, currency.code) }
                val read = economy.balances(online, currency.code) as? Outcome.Success ?: continue
                read.value.forEach { (id, balance) -> mirror.refresh(id, currency.code, seen[id], balance.amount) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Geckonomy: failed to refresh online balances", e)
        }
    }
}
