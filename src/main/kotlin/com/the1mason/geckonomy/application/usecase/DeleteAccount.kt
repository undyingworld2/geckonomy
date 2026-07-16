package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.port.UnitOfWork

/**
 * Deletes an account, its balances, and — if the operator asked for that — its history
 * (SPEC.md FR-A5).
 *
 * @param keepHistory reads `settings.keep-transaction-history` per call rather than capturing it.
 *   The setting is reloadable: `ConfigService.restartWarnings` warns about `allow-overdraft` and
 *   `server-id` and deliberately not this, so a captured value would make `/geckonomy reload` appear
 *   to change it and do nothing. See [Amounts] for the same reasoning about `rounding-mode`.
 */
class DeleteAccount internal constructor(
    private val unitOfWork: UnitOfWork,
    private val keepHistory: () -> Boolean,
    private val guard: StorageGuard,
) {

    /**
     * Removes [id].
     *
     * Balances go automatically — `gk_balance` has `ON DELETE CASCADE` — but the ledger does not:
     * `gk_transaction` carries no foreign key precisely so an audit trail can outlive the account it
     * describes (DATA_MODEL.md §1). So retention is a decision made here, and the default is to keep.
     *
     * The delete goes **first**, and the purge only happens if it removed something. Purging first
     * would erase the history of an account that turns out not to exist — and since a typed `Failure`
     * is a normal return rather than a throw, that transaction would *commit*, quietly destroying an
     * audit trail as a side effect of a failed operation.
     *
     * The transaction is what makes "erase this player" mean it: a purge that fails after the delete
     * rolls the account back too, leaving something an operator can retry rather than exactly the
     * records they were trying to be rid of.
     */
    suspend operator fun invoke(id: AccountId): Outcome<Unit> =
        guard.guarding({ "deleting account ${id.value}" }) {
            unitOfWork.transaction { ctx ->
                if (!ctx.accounts.delete(id)) {
                    Outcome.Failure(EconomyError.AccountNotFound(id))
                } else {
                    if (!keepHistory()) ctx.log.purge(id)
                    Outcome.Success(Unit)
                }
            }
        }
}
