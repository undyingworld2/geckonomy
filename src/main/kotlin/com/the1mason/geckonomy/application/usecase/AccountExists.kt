package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.port.AccountRepository

/**
 * Whether an account exists (SPEC.md FR-A2) — Vault's `hasAccount`.
 *
 * Answers `Success(false)` rather than `Failure(AccountNotFound)` for a missing account: "no" is the
 * answer to this question, not a failure of it. Only a storage fault fails.
 */
class AccountExists internal constructor(
    private val accounts: AccountRepository,
    private val guard: StorageGuard,
) {

    /** `true` if [id] names an account. */
    suspend operator fun invoke(id: AccountId): Outcome<Boolean> =
        guard.guarding({ "checking whether account ${id.value} exists" }) {
            Outcome.Success(accounts.exists(id))
        }
}
