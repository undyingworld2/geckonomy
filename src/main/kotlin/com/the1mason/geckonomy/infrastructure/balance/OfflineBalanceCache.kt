package com.the1mason.geckonomy.infrastructure.balance

import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.service.EconomyService
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.CurrencyCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Balances of players the [OnlineBalanceMirror] does not hold — offline ones — for callers that
 * cannot wait and must not block (SPEC.md FR-P7).
 *
 * **[get] never touches the database.** It answers from the map and, when what it holds is missing or
 * older than [ttl], schedules the read on [scope] for the *next* caller. PlaceholderAPI re-renders
 * every tick, so a scoreboard shows the real balance a tick or two after it first asks — where the
 * alternative, a blocking read, would spend a tick every ~500 lookups on a tab list of offline
 * players and is what FR-P7 exists to forbid.
 *
 * The first render therefore shows the configured fallback rather than a balance. That is the whole
 * cost of the design, and it is bounded to about a tick.
 *
 * A read cache, like the mirror: [refresh] stores only what a use case actually returned. Consulted
 * **only** when the mirror has no entry — an online player's balance is the mirror's business, and
 * two caches answering for one player is how they come to disagree.
 *
 * @param ttl read per call, not captured: `placeholders.offline-cache-seconds` is reloadable.
 * @param nanos injected so tests move time without sleeping (CODING_STANDARDS §6), as `StorageGuard`
 *   and `Throttle` already do.
 */
class OfflineBalanceCache(
    private val economy: EconomyService,
    private val scope: CoroutineScope,
    private val ttl: () -> Duration,
    private val logger: Logger,
    private val nanos: () -> Long = System::nanoTime,
) {

    /** Not a data class: [lastAskedAt] mutates, and generated equality over a moving field is a trap. */
    private class Entry(val amount: BigDecimal, val readAt: Long, @Volatile var lastAskedAt: Long)

    private val entries = ConcurrentHashMap<Key, Entry>()

    /** In-flight refreshes, so a scoreboard polling every tick queues one query and not sixty. */
    private val refreshing = ConcurrentHashMap.newKeySet<Key>()

    private data class Key(val id: AccountId, val currency: CurrencyCode)

    /**
     * What we last read for [id] in [currency], or `null` if we have never read it.
     *
     * Never blocks, never throws. A stale or absent value schedules a refresh as a side effect —
     * asking *is* what keeps an entry alive, which is what [sweep] uses to tell a tab list's working
     * set from a player nobody has looked at since.
     */
    fun get(id: AccountId, currency: CurrencyCode): BigDecimal? {
        val key = Key(id, currency)
        val now = nanos()
        val entry = entries[key]
        entry?.lastAskedAt = now
        if (entry == null || now - entry.readAt >= ttl().inWholeNanoseconds) refresh(key, now)
        return entry?.amount
    }

    /**
     * Drops entries nobody has asked for in twice the [ttl].
     *
     * Without this the map grows with every distinct offline player ever rendered — a leaderboard
     * hologram cycling a whole season's players would hold them all forever. Keyed on *asked*, not
     * *read*, so an entry in active use survives however often it refreshes.
     */
    fun sweep() {
        val cutoff = nanos() - ttl().inWholeNanoseconds * 2
        entries.values.removeIf { entry -> entry.lastAskedAt < cutoff }
    }

    /** Evicts [id] outright — for a player who just came online, whose balances the mirror now owns. */
    fun evict(id: AccountId) {
        entries.keys.removeIf { it.id == id }
    }

    internal fun size(): Int = entries.size

    private fun refresh(key: Key, askedAt: Long) {
        if (!refreshing.add(key)) return
        scope.launch {
            try {
                when (val outcome = economy.balance(key.id, key.currency)) {
                    is Outcome.Success ->
                        entries[key] = Entry(outcome.value.amount, nanos(), askedAt)
                    // Left as it was rather than blanked: a stale balance beats a scoreboard that
                    // blinks to the fallback every time the database hiccups. StorageGuard logged why.
                    is Outcome.Failure -> Unit
                }
            } catch (e: CancellationException) {
                // First, and rethrown: java.util.concurrent.CancellationException is an
                // IllegalStateException, so the arm below would eat the plugin's own shutdown.
                throw e
            } catch (e: Exception) {
                // This coroutine is nobody's child that anyone awaits: an escape would reach the
                // scope's SupervisorJob, which cancels it and logs nothing, and the entry would then
                // never refresh again because `refreshing` still held the key.
                logger.log(Level.WARNING, "Geckonomy: failed to refresh the offline balance of ${key.currency.value} for ${key.id.value}", e)
            } finally {
                refreshing.remove(key)
            }
        }
    }
}
