package com.the1mason.geckonomy.infrastructure.vault

import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.CurrencyCode
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Online players' balances, so the synchronous Vault path can answer without touching the database
 * (ARCHITECTURE.md §4).
 *
 * A read cache, never a write-behind buffer: the database stays the source of truth, and [put] stores
 * only a balance it actually returned — never one the adapter predicted. A write awaits its use case
 * precisely so there is a real answer to store (ARCHITECTURE.md §4).
 *
 * Keyed by `(AccountId, CurrencyCode)` and nothing else. Scope resolution belongs to the persistence
 * layer, which already turns `currency.scope` + the server id into a `scope_key`; a mirror that also
 * knew about scope keys would be a second place for that rule to be wrong.
 */
class OnlineBalanceMirror {

    private val balances = ConcurrentHashMap<AccountId, ConcurrentHashMap<CurrencyCode, BigDecimal>>()

    /** Replaces everything held for [id]. For an account whose balances are already known. */
    fun hydrate(id: AccountId, balances: Map<CurrencyCode, BigDecimal>) {
        this.balances[id] = ConcurrentHashMap(balances)
    }

    /**
     * Claims [id]'s slot before its balances are read, returning the map to fill.
     *
     * Claiming first is what stops a payment that lands mid-login from being lost: [put] only writes to
     * an account that is already mirrored, so without the claim it would no-op, and the balances read
     * *before* the payment would then be installed over the top of it. Reads in the meantime see no
     * entry for a currency and fall back to the database, which is correct — just slower.
     */
    fun beginHydration(id: AccountId): MutableMap<CurrencyCode, BigDecimal> =
        ConcurrentHashMap<CurrencyCode, BigDecimal>().also { balances[id] = it }

    /**
     * Fills [slot] with [read] without disturbing anything written since [beginHydration] claimed it —
     * a write during the read is newer than the read, and knows the balance the database settled on.
     *
     * Does nothing if [slot] is no longer [id]'s: the player quit mid-login and was evicted, or a second
     * hydration overtook this one. Either way this one's values are stale and resurrecting them would
     * leave an entry nothing will ever evict.
     */
    fun completeHydration(id: AccountId, slot: MutableMap<CurrencyCode, BigDecimal>, read: Map<CurrencyCode, BigDecimal>) {
        if (balances[id] !== slot) return
        read.forEach { (currency, amount) -> slot.putIfAbsent(currency, amount) }
    }

    fun evict(id: AccountId) {
        balances.remove(id)
    }

    /** `null` when [id] is not mirrored — an offline player, or one still hydrating. */
    fun get(id: AccountId, currency: CurrencyCode): BigDecimal? = balances[id]?.get(currency)

    fun isMirrored(id: AccountId): Boolean = balances.containsKey(id)

    /**
     * Records [amount] for [id], but only if [id] is already mirrored.
     *
     * The guard is what keeps an evicted account from being resurrected by a write that raced a quit:
     * `balances[id]` would recreate the per-account map, and the entry would then never be evicted
     * again, because eviction already happened.
     */
    fun put(id: AccountId, currency: CurrencyCode, amount: BigDecimal) {
        balances[id]?.put(currency, amount)
    }

    fun snapshot(id: AccountId): Map<CurrencyCode, BigDecimal>? = balances[id]?.toMap()
}
