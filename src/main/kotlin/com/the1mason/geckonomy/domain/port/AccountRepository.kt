package com.the1mason.geckonomy.domain.port

import com.the1mason.geckonomy.domain.model.Account
import com.the1mason.geckonomy.domain.model.AccountId

/**
 * Storage of accounts.
 *
 * Every function is `suspend` so implementations can do their IO off the caller's thread; the domain
 * states the need and stays out of *how* (ARCHITECTURE.md §3).
 */
interface AccountRepository {

    /**
     * Creates [account], doing nothing if it already exists.
     *
     * Idempotent because account creation races: a join listener and a Vault caller may both try to
     * create the same player (SPEC.md FR-A1).
     *
     * @return `true` if a new account was stored, `false` if it already existed.
     */
    suspend fun create(account: Account): Boolean

    /** Whether an account with [id] exists. */
    suspend fun exists(id: AccountId): Boolean

    /** The display name of [id], or `null` if there is no such account. */
    suspend fun findName(id: AccountId): String?

    /** Every account's id mapped to its display name (SPEC.md FR-A3). */
    suspend fun nameMap(): Map<AccountId, String>

    /**
     * The display names of [ids], for a caller that already knows which accounts it wants.
     *
     * [nameMap] answers the same question without a bound, and is the wrong tool wherever the caller
     * has a short list: `/baltop` labels ten rows, and would otherwise read every account on the
     * server to do it.
     *
     * Ids with no account are absent from the result rather than mapped to `null`, so the result size
     * is not a promise. A caller that needs a name for every id must decide what a missing one reads
     * as; `/baltop` cannot have one, since a balance row implies its account (DATA_MODEL.md §1).
     */
    suspend fun namesOf(ids: Collection<AccountId>): Map<AccountId, String>

    /**
     * Changes the display name of [id].
     *
     * @return `false` if no such account exists.
     */
    suspend fun rename(id: AccountId, name: String): Boolean

    /**
     * Removes the account [id] and its balances. Whether the ledger survives is governed by
     * `settings.keep-transaction-history` (CONFIGURATION.md §2).
     *
     * @return `false` if no such account existed.
     */
    suspend fun delete(id: AccountId): Boolean
}
