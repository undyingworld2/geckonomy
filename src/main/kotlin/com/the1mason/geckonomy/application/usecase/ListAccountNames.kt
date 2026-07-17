package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.port.AccountRepository

/**
 * Every account's id mapped to its name (SPEC.md FR-A3) — Vault's `getUUIDNameMap`.
 *
 * Unbounded, because the Vault method it serves is. On a long-lived server this reads every account
 * row, so it belongs nowhere near a hot path; it exists because integrators ask for it. A caller with
 * a known, short list of ids wants `AccountRepository.namesOf` instead — which is what [ListTopBalances]
 * uses to label ten rows without reading the whole table.
 */
class ListAccountNames internal constructor(
    private val accounts: AccountRepository,
    private val guard: StorageGuard,
) {

    /** Every account, by id. */
    suspend operator fun invoke(): Outcome<Map<AccountId, String>> =
        guard.guarding({ "reading every account name" }) {
            Outcome.Success(accounts.nameMap())
        }
}
