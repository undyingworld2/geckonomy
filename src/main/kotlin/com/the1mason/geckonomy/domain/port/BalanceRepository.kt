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
     * Applies a signed [delta] to the balance of [id] in [currency] and returns the new amount, or
     * `null` if the overdraft rule refused it.
     *
     * **Atomic**: the check and the write are one operation, so concurrent adjustments cannot lose an
     * update the way `get` followed by `set` would, and two simultaneous withdrawals cannot both pass
     * a balance check and jointly overdraw. Enforcing the rule here rather than in the caller is what
     * makes that guarantee possible — a caller cannot atomically check-then-act across a port.
     *
     * A missing balance row counts as zero and is created by this call, so a currency added to config
     * after an account already exists can still be deposited into (DATA_MODEL.md §6).
     *
     * `null` is a typed refusal, not an error: insufficient funds is a routine outcome every caller
     * must handle, so it is a return value rather than an exception (CODING_STANDARDS.md §4). Callers
     * map it to `EconomyError.InsufficientFunds`.
     *
     * @return the balance after the change, or `null` if applying [delta] would leave the balance
     *   below zero while overdraft is off.
     */
    suspend fun adjust(id: AccountId, currency: Currency, delta: BigDecimal): BigDecimal?

    /** The [limit] highest balances in [currency], richest first, for `/baltop`. */
    suspend fun top(currency: Currency, limit: Int): List<Pair<AccountId, BigDecimal>>
}
