package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.Throttle
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.suppressedSuffix
import com.the1mason.geckonomy.domain.DomainException
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The boundary where an exception stops being an exception and becomes an [EconomyError], and where a
 * storage operation is timed (SPEC.md NFR-8).
 *
 * `MoneyOutOfRange` and `LedgerFailure` both promise in their KDoc that they are "caught at the
 * application boundary… never thrown at a Bukkit caller"; `SQLException` makes no promise at all.
 * This is that boundary, and it exists once rather than as a `try/catch` copied into every use case —
 * which is the only way the rule can be *changed* once, too.
 *
 * Timing lives here for the same reason: every use case that touches a port already wraps it in
 * [guarding] and already names what it is doing, so the one place that sees every storage operation
 * gets the measurement for free, and nothing else has to remember to take it.
 *
 * @param logger a JDK logger, not a Bukkit one: `application` may not import Bukkit, and
 *   `JavaPlugin.logger` already *is* a [Logger], so the composition root passes it with no adapter.
 * @param nanos injected so a test can make an operation slow without being slow.
 */
internal class StorageGuard(
    private val logger: Logger,
    private val nanos: () -> Long = System::nanoTime,
) {

    private val slowOps = Throttle(WARN_EVERY, nanos)

    /**
     * Runs [block], turning any storage exception into [EconomyError.StorageFailure].
     *
     * The catch order is load-bearing; see each arm.
     *
     * @param context what is being attempted, for the log and the error. A lambda, so the string is
     *   built only when something actually fails.
     */
    suspend fun <T> guarding(context: () -> String, block: suspend () -> Outcome<T>): Outcome<T> =
        try {
            val startedAt = nanos()
            // Only on the way out normally. A throw is already being logged below with its cause, and
            // "that also took a while" is not the useful half of a database that just failed.
            block().also { warnIfSlow(startedAt, context) }
        } catch (e: CancellationException) {
            // First, and rethrown. Cancellation is not a failure, it is the plugin disabling or a
            // player quitting mid-operation, and SqlUnitOfWork's rollback depends on it reaching it:
            // "including CancellationException, so a player quitting mid-transfer cannot leave half
            // of one committed". Swallowing it would also leave the plugin's scope un-cancellable.
            // This arm is not redundant with the one below — java.util.concurrent.CancellationException
            // is an IllegalStateException, so `catch (e: Exception)` would happily eat it.
            throw e
        } catch (e: DomainException) {
            // A broken invariant is *our* bug, not a sick database, so it is logged at a level that
            // says so. Unreachable by construction — the application never calls CurrencyCode(raw),
            // and every Money in one operation shares one resolved Currency — which makes this a
            // tripwire rather than a path. It still maps to StorageFailure: EconomyError has no
            // Internal variant, and both would reach a player as "something went wrong" regardless.
            logger.log(Level.SEVERE, "Geckonomy bug while ${context()}", e)
            Outcome.Failure(EconomyError.StorageFailure(context(), "internal error"))
        } catch (e: Exception) {
            // SQLException (including Hikari's timeout), MoneyOutOfRange, LedgerFailure. Deliberately
            // not Throwable: an OutOfMemoryError is not ours to map, and pretending we handled it
            // would only hide it.
            logger.log(Level.WARNING, "Storage failure while ${context()}", e)
            Outcome.Failure(EconomyError.StorageFailure(context(), e.message))
        }

    /**
     * A slow operation is a warning, not a failure: it succeeded, and the answer was right. What it
     * says is that the *next* one might not be — NFR-1's promise is that no player waits on the
     * database, and the mirror only keeps that promise while the database is quick.
     */
    private fun warnIfSlow(startedAt: Long, context: () -> String) {
        val elapsed = (nanos() - startedAt) / 1_000_000
        if (elapsed < SLOW_OP.inWholeMilliseconds) return
        val suppressed = slowOps.claim() ?: return
        logger.warning("Geckonomy: slow storage - ${elapsed}ms while ${context()}${suppressedSuffix(suppressed)}")
    }

    private companion object {
        /**
         * Chosen against the measurements M6 took on the sync path: a mirror hit is ~400ns and an
         * un-mirrored SQLite read ~99us, so anything at this scale is three orders of magnitude off
         * its own normal and means the storage, not the code.
         */
        val SLOW_OP = 250.milliseconds

        val WARN_EVERY = 30.seconds
    }
}
