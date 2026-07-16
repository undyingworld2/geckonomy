package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.Attribution
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.OperationResult
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.result.then
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.TransactionType
import com.the1mason.geckonomy.domain.port.UnitOfWork
import java.math.BigDecimal

/**
 * Adds money to an account (SPEC.md FR-B3).
 *
 * Runs inside a [UnitOfWork], which ARCHITECTURE.md §5's sequence does not show. The diagram has
 * `adjust` then `append` bare, and that is wrong: if the ledger write fails, the money has moved and
 * no row records it — FR-B7 ("every mutating operation appends an immutable ledger row") quietly
 * false, and an audit that will never add up. Wrapping costs nothing, because
 * `SqlBalanceRepository.inTransaction` already defers to an ambient transaction rather than opening
 * its own.
 *
 * Unlike `Transfer`, no `Abort` is needed here. A refusal returns a typed [Outcome.Failure] normally
 * and the transaction commits, because at that point nothing has been written that anyone would want
 * undone — at worst `adjust`'s seeded zero row, which is by definition indistinguishable from no row.
 * Only `Transfer` has a completed write to take back.
 */
class Deposit internal constructor(
    private val unitOfWork: UnitOfWork,
    private val amounts: Amounts,
    private val transactions: TransactionFactory,
    private val guard: StorageGuard,
) {

    /**
     * Credits [amount] of [currency] to [id].
     *
     * `exists` is checked inside the transaction and before the write. Not for orphan rows —
     * `gk_balance`'s foreign key makes those impossible — but *because* of it: without the check, a
     * deposit to a deleted player fails on the FK, and an opaque `SQLException` would reach them as
     * "a storage error occurred" instead of "no such account".
     *
     * @return the resulting balance.
     */
    suspend operator fun invoke(
        id: AccountId,
        amount: BigDecimal,
        currency: CurrencyCode,
        by: Attribution = Attribution.GECKONOMY,
    ): OperationResult = amounts.positive(amount, currency).then { money ->
        guard.guarding({ "depositing $amount ${currency.value} to ${id.value}" }) {
            unitOfWork.transaction { ctx ->
                if (!ctx.accounts.exists(id)) {
                    Outcome.Failure(EconomyError.AccountNotFound(id))
                } else {
                    // The guard refuses only a negative result, and this delta is positive, so a
                    // refusal here is unreachable. Reported rather than asserted with `!!`, because if
                    // it ever does happen the store is contradicting itself and "adjust refused a
                    // credit" is a far better thing to find in a log than a NullPointerException.
                    val balance = ctx.balance.adjust(id, money.currency, money.amount)
                        ?: return@transaction Outcome.Failure(
                            EconomyError.StorageFailure(
                                "depositing $amount ${currency.value} to ${id.value}",
                                "the balance guard refused a credit",
                            ),
                        )
                    ctx.log.append(
                        transactions.entry(
                            accountId = id,
                            currency = money.currency.code,
                            delta = money.amount,
                            resultingBalance = balance,
                            type = TransactionType.DEPOSIT,
                            by = by,
                        ),
                    )
                    Outcome.Success(amounts.balance(balance, money.currency))
                }
            }
        }
    }
}
