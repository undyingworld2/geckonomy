package com.the1mason.geckonomy.domain.port

import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.Currency
import java.math.BigDecimal

/**
 * Storage of balances.
 *
 * Every function takes the full [Currency] rather than just its code so the implementation can
 * resolve the scope key (`@global` for a network currency, the server id for a per-server one) from
 * [Currency.scope] plus its injected server id. That keeps the "which server" question entirely in
 * infrastructure — domain and application never learn that server ids exist (ARCHITECTURE.md §3).
 */
interface BalanceRepository {

    /**
     * The amount [id] holds in [currency], or `null` if there is no balance row — which is distinct
     * from a stored zero, and lets callers tell "never seeded" from "spent it all".
     */
    suspend fun get(id: AccountId, currency: Currency): BigDecimal?

    /** Replaces the balance of [id] in [currency] with [amount], creating the row if absent. */
    suspend fun set(id: AccountId, currency: Currency, amount: BigDecimal)

    /**
     * Applies a signed [delta] to the balance of [id] in [currency] and returns the new amount.
     *
     * **Atomic**: the read and write are one operation, so concurrent adjustments cannot lose an
     * update the way `get` followed by `set` would.
     *
     * @return the balance after the change.
     */
    suspend fun adjust(id: AccountId, currency: Currency, delta: BigDecimal): BigDecimal

    /** The [limit] highest balances in [currency], richest first, for `/baltop`. */
    suspend fun top(currency: Currency, limit: Int): List<Pair<AccountId, BigDecimal>>
}
