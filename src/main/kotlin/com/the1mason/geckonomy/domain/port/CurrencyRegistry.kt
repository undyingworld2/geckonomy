package com.the1mason.geckonomy.domain.port

import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode

/**
 * The currencies this server knows about, loaded from config and held in memory.
 *
 * A port like the repositories, but backed by config rather than a database — which is why its
 * functions are not `suspend`: there is no IO to do, and forcing callers into a coroutine for a map
 * lookup would be a lie about the cost.
 */
interface CurrencyRegistry {

    /** Every configured currency. */
    fun all(): Collection<Currency>

    /**
     * The currency used when a caller names none.
     *
     * Non-null: config validation guarantees exactly one default exists and refuses to start the
     * plugin otherwise, so by the time a registry exists the question is settled
     * (DOMAIN_MODEL.md §4).
     */
    fun default(): Currency

    /** The currency with [code], or `null` if no such currency is configured. */
    fun byCode(code: CurrencyCode): Currency?
}
