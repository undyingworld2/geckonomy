package com.the1mason.geckonomy.infrastructure.placeholder

import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.service.EconomyService
import com.the1mason.geckonomy.application.usecase.TopBalance
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * The leaderboard, rebuilt on a timer so a placeholder can read it for free (SPEC.md FR-P7).
 *
 * A scoreboard asks for `%geckonomy_baltop_player_1%` every tick, for every viewer; the query behind
 * it is two `SELECT`s. So it is run once per currency per [interval] no matter how often it is read,
 * and reads are a plain volatile field read — safe on the main thread because it touches nothing.
 *
 * Before the first refresh lands the snapshot is empty and every rank renders the fallback.
 *
 * @param size `settings.baltop-size`, read per call: reloadable.
 * @param interval `placeholders.baltop-refresh-seconds`, read per call for the same reason. Sampled
 *   once per cycle, so a reload takes effect on the next tick of the loop rather than mid-sleep.
 */
class BaltopSnapshot(
    private val economy: EconomyService,
    private val currencies: CurrencyRegistry,
    private val size: () -> Int,
    private val interval: () -> Duration,
    private val logger: Logger,
    private val onRefreshed: () -> Unit = {},
) {

    /** One currency's rows, plus the rank lookup derived from them. */
    private class Board(val rows: List<TopBalance>, val ranks: Map<AccountId, Int>)

    @Volatile
    private var boards: Map<CurrencyCode, Board> = emptyMap()

    /**
     * The account at [rank] (1-based) in [currency], or `null` beyond what the snapshot holds.
     *
     * The list can be shorter than `baltop-size` — [com.the1mason.geckonomy.application.usecase.ListTopBalances]
     * drops an account whose name has gone missing — so this indexes defensively rather than trusting
     * `rank <= size`.
     */
    fun at(currency: CurrencyCode, rank: Int): TopBalance? =
        boards[currency]?.rows?.getOrNull(rank - 1)

    /**
     * Where [id] places in [currency], or `null` if it is not in the top `baltop-size`.
     *
     * Bounded by the snapshot **on purpose**: a true rank is `COUNT(*) WHERE balance > ?` per player
     * per tick, which is exactly the IO FR-P7 forbids. Most players have no rank, and that is the
     * documented answer rather than a gap.
     */
    fun rankOf(currency: CurrencyCode, id: AccountId): Int? = boards[currency]?.ranks?.get(id)

    /** Refreshes forever on [scope]; cancelled with it on disable. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            refreshAll()
            onRefreshed()
            delay(interval())
        }
    }

    internal suspend fun refreshAll() {
        val refreshed = boards.toMutableMap()
        for (currency in currencies.all()) {
            refresh(currency.code)?.let { refreshed[currency.code] = it }
        }
        boards = refreshed
    }

    /**
     * One currency, or `null` to keep whatever the last good refresh left.
     *
     * Two failure modes, one answer. A typed [Outcome.Failure] is a sick database, already logged by
     * `StorageGuard`; a throw would otherwise reach the scope's `SupervisorJob`, which cancels this
     * loop and **logs nothing** — the snapshot would then freeze at its last value and serve it
     * forever, with no line anywhere connecting a stale leaderboard to the night the database blinked.
     * Neither may blank the board: a scoreboard flashing to the fallback on every hiccup is worse than
     * one a minute behind.
     */
    private suspend fun refresh(code: CurrencyCode): Board? =
        try {
            when (val outcome = economy.top(code, size())) {
                is Outcome.Success -> Board(outcome.value, outcome.value.associate { it.id to it.rank })
                is Outcome.Failure -> null
            }
        } catch (e: CancellationException) {
            // First, and rethrown: the plugin is disabling. `catch (e: Exception)` below would eat it
            // — java.util.concurrent.CancellationException is an IllegalStateException — and the loop
            // would log a scary warning on every clean shutdown.
            throw e
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Geckonomy: failed to refresh the ${code.value} leaderboard snapshot", e)
            null
        }
}
