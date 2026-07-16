package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.port.AccountRepository

/**
 * Changes an account's display name (SPEC.md FR-A4).
 *
 * Called by M7's join listener when a returning player's name has changed: `CreateAccount` is an
 * `INSERT OR IGNORE` and will not update the name of an account it declined to create, so the
 * refresh has to be its own step.
 *
 * A name is display only, never identity (DATA_MODEL.md §8) — nothing keys off it, so this touches
 * one column and no balances.
 */
class RenameAccount internal constructor(
    private val accounts: AccountRepository,
    private val guard: StorageGuard,
) {

    /**
     * Renames [id] to [name].
     *
     * No `exists` check: `rename` already answers `false` for a missing account, so asking first
     * would be a second query to learn what the write is about to tell us anyway.
     */
    suspend operator fun invoke(id: AccountId, name: String): Outcome<Unit> =
        guard.guarding({ "renaming account ${id.value} to $name" }) {
            if (accounts.rename(id, name)) Outcome.Success(Unit)
            else Outcome.Failure(EconomyError.AccountNotFound(id))
        }
}
