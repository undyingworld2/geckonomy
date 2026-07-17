package com.the1mason.geckonomy.infrastructure.vault

import com.the1mason.geckonomy.application.Attribution
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.OperationResult
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.result.TransferResult
import com.the1mason.geckonomy.application.result.map
import com.the1mason.geckonomy.application.service.EconomyService
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.CurrencyScope
import com.the1mason.geckonomy.infrastructure.config.StorageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Bridges Vault's synchronous, main-thread API onto the suspending [EconomyService]
 * (ARCHITECTURE.md §4). The only place that knows the mirror rules; both providers go through it.
 *
 * **Reads** answer from the mirror and touch no database. **Writes** await the use case. That
 * asymmetry is the design: reads are the bulk of Vault traffic and the mirror makes them free, while
 * a write that reported success from an adapter-side guess would need its own copy of the currency,
 * rounding, dust and overdraft rules — and could still contradict what the database went on to do.
 * Writes are comparatively rare; correctness is worth their latency.
 */
internal class VaultSyncPath(
    private val economy: EconomyService,
    private val mirror: OnlineBalanceMirror,
    private val scope: CoroutineScope,
    private val storage: StorageType,
    private val logger: Logger,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) {

    private val refreshing = ConcurrentHashMap.newKeySet<Pair<AccountId, CurrencyCode>>()

    fun balance(id: AccountId, currency: Currency): BigDecimal {
        mirror.get(id, currency.code)?.let { mirrored ->
            if (staleRisk(currency)) refreshBehind(id, currency)
            return mirrored
        }
        // Not mirrored: an offline account, or one still hydrating. Rare, and the fallback is the
        // reason the sync API can answer for them at all — but it costs ~99us against SQLite where a
        // mirror hit costs ~400ns, so it must stay the rare path.
        //
        // The zero is a real answer, not a placeholder: this returns BigDecimal, and no exception may
        // reach a Vault caller, so a failed read has to become some number. Zero is the one that fails
        // closed — [has] then says false and a shop refuses the sale rather than giving goods away.
        // It is never silent: StorageGuard logs the cause, or [read] logs the timeout.
        return read("balance of ${currency.code} for ${id.value}") { economy.balance(id, currency.code) }
            .map { it.amount }
            .orElse(BigDecimal.ZERO)
    }

    fun has(id: AccountId, amount: BigDecimal, currency: Currency): Boolean =
        balance(id, currency) >= amount

    fun deposit(id: AccountId, amount: BigDecimal, currency: Currency, by: Attribution): OperationResult =
        write(id, currency, "depositing $amount ${currency.code}") { economy.deposit(id, amount, currency.code, by) }

    fun withdraw(id: AccountId, amount: BigDecimal, currency: Currency, by: Attribution): OperationResult =
        write(id, currency, "withdrawing $amount ${currency.code}") { economy.withdraw(id, amount, currency.code, by) }

    fun set(id: AccountId, amount: BigDecimal, currency: Currency, by: Attribution): OperationResult =
        write(id, currency, "setting ${currency.code} to $amount") { economy.set(id, amount, currency.code, by) }

    fun canDeposit(id: AccountId, amount: BigDecimal, currency: Currency): Outcome<Boolean> =
        read("canDeposit $amount ${currency.code}") { economy.canDeposit(id, amount, currency.code) }

    fun canWithdraw(id: AccountId, amount: BigDecimal, currency: Currency): Outcome<Boolean> =
        read("canWithdraw $amount ${currency.code}") { economy.canWithdraw(id, amount, currency.code) }

    fun transfer(from: AccountId, to: AccountId, amount: BigDecimal, currency: Currency, by: Attribution): TransferResult {
        val result = read("transferring $amount ${currency.code}") { economy.transfer(from, to, amount, currency.code, by) }
        if (result is Outcome.Success) {
            mirror.put(from, currency.code, result.value.payerBalance.amount)
            mirror.put(to, currency.code, result.value.payeeBalance.amount)
        }
        return result
    }

    fun createAccount(id: AccountId, name: String): Outcome<Boolean> =
        read("creating an account for $name") { economy.createAccount(id, name) }

    fun exists(id: AccountId): Boolean =
        mirror.isMirrored(id) || read("account check for ${id.value}") { economy.exists(id) }.orElse(false)

    fun name(id: AccountId): String? =
        read("name of ${id.value}") { economy.name(id) }.orElse(null)

    fun nameMap(): Map<AccountId, String> =
        read("the account name map") { economy.nameMap() }.orElse(emptyMap())

    fun rename(id: AccountId, name: String): Boolean =
        read("renaming ${id.value}") { economy.rename(id, name) } is Outcome.Success

    fun delete(id: AccountId): Boolean {
        val result = read("deleting ${id.value}") { economy.delete(id) }
        if (result is Outcome.Success) mirror.evict(id)
        return result is Outcome.Success
    }

    /**
     * Loads every currency into the mirror. Suspending: the caller is already off the main thread.
     *
     * The slot is claimed before the first read so that a payment landing mid-login is kept rather than
     * overwritten by a balance read before it — see [OnlineBalanceMirror.beginHydration].
     */
    suspend fun hydrate(id: AccountId) {
        val slot = mirror.beginHydration(id)
        val balances = economy.currencies().mapNotNull { currency ->
            // A currency whose read failed is left out rather than mirrored as zero: absent means
            // "ask the database", zero would mean "they're broke", and only one of those is honest.
            (economy.balance(id, currency.code) as? Outcome.Success)?.let { currency.code to it.value.amount }
        }.toMap()
        mirror.completeHydration(id, slot, balances)
    }

    private fun write(
        id: AccountId,
        currency: Currency,
        what: String,
        block: suspend () -> OperationResult,
    ): OperationResult {
        val result = read(what, block)
        if (result is Outcome.Success) mirror.put(id, currency.code, result.value.amount)
        return result
    }

    /**
     * A `NETWORK` currency on MariaDB is the one case another server can write behind our back, so the
     * mirror can go stale. On SQLite it cannot: the file is local, nothing else has it open, and a
     * refresh would be pure waste.
     */
    private fun staleRisk(currency: Currency): Boolean =
        currency.scope == CurrencyScope.NETWORK && storage == StorageType.MARIADB

    /**
     * Re-reads [currency] into the mirror off-thread, so the *next* caller sees a fresher value.
     *
     * Deduplicated: a shop plugin polling a balance every tick would otherwise queue one coroutine per
     * call, each running the same query.
     */
    private fun refreshBehind(id: AccountId, currency: Currency) {
        val key = id to currency.code
        if (!refreshing.add(key)) return
        scope.launch {
            try {
                (economy.balance(id, currency.code) as? Outcome.Success)
                    ?.let { mirror.put(id, currency.code, it.value.amount) }
            } finally {
                refreshing.remove(key)
            }
        }
    }

    /**
     * Runs [block] to completion on the calling (usually main) thread, bounded by [timeout].
     *
     * `runBlocking` here is deliberate, and this is the one place CODING_STANDARDS §3 allows it:
     * Vault's interface must answer now. It cannot deadlock — the work runs on the IO dispatcher's own
     * threads, never the one parked here — but a sick database could park the main thread forever, so
     * the timeout is not optional.
     *
     * Nothing escapes into a Vault caller: a timeout or a stray exception becomes [StorageFailure],
     * the same variant the use cases' own guard produces, so callers have one shape to handle.
     */
    private fun <T> read(what: String, block: suspend () -> Outcome<T>): Outcome<T> =
        try {
            runBlocking { withTimeout(timeout) { block() } }
        } catch (failure: Exception) {
            logger.log(Level.WARNING, "Geckonomy: main thread gave up after $timeout while $what", failure)
            Outcome.Failure(EconomyError.StorageFailure(what, failure.message))
        }

    private fun <T> Outcome<T>.orElse(fallback: T): T = if (this is Outcome.Success) value else fallback

    private companion object {
        val DEFAULT_TIMEOUT: Duration = 2.seconds
    }
}
