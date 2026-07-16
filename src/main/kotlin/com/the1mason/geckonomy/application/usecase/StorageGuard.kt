package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.DomainException
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException

/**
 * The boundary where an exception stops being an exception and becomes an [EconomyError].
 *
 * `MoneyOutOfRange` and `LedgerFailure` both promise in their KDoc that they are "caught at the
 * application boundary… never thrown at a Bukkit caller"; `SQLException` makes no promise at all.
 * This is that boundary, and it exists once rather than as a `try/catch` copied into every use case —
 * which is the only way the rule can be *changed* once, too.
 *
 * @param logger a JDK logger, not a Bukkit one: `application` may not import Bukkit, and
 *   `JavaPlugin.logger` already *is* a [Logger], so the composition root passes it with no adapter.
 */
internal class StorageGuard(private val logger: Logger) {

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
            block()
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
}
