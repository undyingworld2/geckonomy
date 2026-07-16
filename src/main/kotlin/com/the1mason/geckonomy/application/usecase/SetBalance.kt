package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.Attribution
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.OperationResult
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.result.then
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.TransactionType
import com.the1mason.geckonomy.domain.policy.OverdraftPolicy
import com.the1mason.geckonomy.domain.port.UnitOfWork
import java.math.BigDecimal

/**
 * Replaces an account's balance outright — `/eco set` (SPEC.md FR-B3).
 *
 * The only use case that checks [OverdraftPolicy] itself. `BalanceRepository.set` is deliberately
 * unguarded — its KDoc says so, and it has to be, since "make this exactly 50" has no delta for a
 * `WHERE amount + ? >= 0` clause to guard. So the rule is applied here instead, using
 * [OverdraftPolicy.permits], which takes the *resulting* balance precisely so that one function can
 * cover withdraw, transfer, and an admin setting a negative number alike.
 *
 * @param overdraft the same instance the balance repository compiled into its SQL guard, so the two
 *   cannot disagree about `allow-overdraft`.
 */
class SetBalance internal constructor(
    private val unitOfWork: UnitOfWork,
    private val amounts: Amounts,
    private val overdraft: OverdraftPolicy,
    private val transactions: TransactionFactory,
    private val guard: StorageGuard,
) {

    /**
     * Sets [id]'s [currency] balance to [amount].
     *
     * A refused negative is [EconomyError.InvalidAmount], not `InsufficientFunds`: nothing is being
     * paid for. The amount is simply not one this server permits, which is what `InvalidAmount` says.
     *
     * The ledger row records `delta = new − previous`, read inside the transaction so it is exact. A
     * `SET` row with `delta = 0` would make the ledger useless for reconciling an account's history —
     * the column is documented as "the signed change", and a set is a change like any other. That
     * read is the second reason this needs the transaction, beyond the one in [Deposit].
     *
     * @return the new balance.
     */
    suspend operator fun invoke(
        id: AccountId,
        amount: BigDecimal,
        currency: CurrencyCode,
        by: Attribution = Attribution.GECKONOMY,
    ): OperationResult = amounts.any(amount, currency).then { target ->
        if (!overdraft.permits(target.amount)) {
            Outcome.Failure(EconomyError.InvalidAmount(amount, "negative balances are not allowed"))
        } else {
            guard.guarding({ "setting ${id.value}'s ${currency.value} balance to $amount" }) {
                unitOfWork.transaction { ctx ->
                    if (!ctx.accounts.exists(id)) {
                        Outcome.Failure(EconomyError.AccountNotFound(id))
                    } else {
                        val previous = ctx.balance.get(id, target.currency) ?: BigDecimal.ZERO
                        ctx.balance.set(id, target.currency, target.amount)
                        ctx.log.append(
                            transactions.entry(
                                accountId = id,
                                currency = target.currency.code,
                                delta = target.amount - previous,
                                resultingBalance = target.amount,
                                type = TransactionType.SET,
                                by = by,
                            ),
                        )
                        Outcome.Success(target)
                    }
                }
            }
        }
    }
}
