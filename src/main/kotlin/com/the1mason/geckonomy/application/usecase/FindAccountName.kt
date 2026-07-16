package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.port.AccountRepository

/**
 * An account's display name (SPEC.md FR-A3).
 *
 * `Success(null)` for a missing account, like [AccountExists]: the caller asked what the name is, and
 * "there isn't one" is an answer. A name is display only and never identity (DATA_MODEL.md §8) — a
 * caller wanting to *act* on the account should be holding its [AccountId] already.
 */
class FindAccountName internal constructor(
    private val accounts: AccountRepository,
    private val guard: StorageGuard,
) {

    /** The name of [id], or `null` if there is no such account. */
    suspend operator fun invoke(id: AccountId): Outcome<String?> =
        guard.guarding({ "reading account ${id.value}'s name" }) {
            Outcome.Success(accounts.findName(id))
        }
}
