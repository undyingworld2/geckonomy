package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.Attribution
import com.the1mason.geckonomy.application.result.BonusCredit
import com.the1mason.geckonomy.application.result.DepositResult
import com.the1mason.geckonomy.application.result.Deposited
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.result.map
import com.the1mason.geckonomy.application.result.then
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.Money
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
 * A refusal of the deposit itself returns a typed [Outcome.Failure] normally and the transaction
 * commits, because at that point nothing has been written that anyone would want undone — at worst
 * `adjust`'s seeded zero row, which is by definition indistinguishable from no row. The bonus credit
 * is different: it comes after the deposit, so a refusal there has a completed write to take back,
 * and needs [Abort] exactly as `Transfer` does.
 *
 * The bonus (SPEC.md FR-B9) is credited in the same transaction, so a deposit never lands without the
 * bonus it promised, nor a bonus without its deposit.
 */
class Deposit internal constructor(
    private val unitOfWork: UnitOfWork,
    private val amounts: Amounts,
    private val transactions: TransactionFactory,
    private val guard: StorageGuard,
) {

    /**
     * Credits [amount] of [currency] to [id], and its bonus currency alongside it if one applies.
     *
     * `exists` is checked inside the transaction and before the write. Not for orphan rows —
     * `gk_balance`'s foreign key makes those impossible — but *because* of it: without the check, a
     * deposit to a deleted player fails on the FK, and an opaque `SQLException` would reach them as
     * "a storage error occurred" instead of "no such account".
     *
     * @param bonus `true` or `false` to grant or withhold the bonus; `null` follows the currency's
     *   `by-default`. `true` on a currency with no bonus is refused rather than ignored: the caller
     *   asked for something that will not happen.
     * @return the resulting balance, and the bonus credited, if any.
     */
    suspend operator fun invoke(
        id: AccountId,
        amount: BigDecimal,
        currency: CurrencyCode,
        by: Attribution = Attribution.GECKONOMY,
        bonus: Boolean? = null,
    ): DepositResult = amounts.positive(amount, currency).then { money ->
        bonusFor(money, bonus).then { credit ->
            val context = { "depositing $amount ${currency.value} to ${id.value}" }
            guard.guarding(context) {
                try {
                    unitOfWork.transaction<DepositResult>(setOf(id)) { ctx ->
                        if (!ctx.accounts.exists(id)) {
                            return@transaction Outcome.Failure(EconomyError.AccountNotFound(id))
                        }
                        // With a bonus, two rows: locked together in the store's global order, so a
                        // deposit of another currency sharing this bonus cannot take them the other
                        // way round (DATA_MODEL.md §4).
                        if (credit != null) ctx.balance.lock(listOf(id to money.currency, id to credit.currency))
                        // The guard refuses only a negative result, and these deltas are positive, so
                        // a refusal is unreachable. Reported rather than asserted with `!!`, because if
                        // it ever does happen the store is contradicting itself and "adjust refused a
                        // credit" is a far better thing to find in a log than a NullPointerException.
                        val balance = ctx.balance.adjust(id, money.currency, money.amount)
                            ?: return@transaction Outcome.Failure(refusedCredit(context()))
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
                        val bonusCredit = credit?.let { credited ->
                            val bonusBalance = ctx.balance.adjust(id, credited.currency, credited.amount)
                                ?: throw Abort(refusedCredit(context()))
                            ctx.log.append(
                                transactions.entry(
                                    accountId = id,
                                    currency = credited.currency.code,
                                    delta = credited.amount,
                                    resultingBalance = bonusBalance,
                                    type = TransactionType.BONUS,
                                    by = by,
                                ),
                            )
                            BonusCredit(credited, amounts.balance(bonusBalance, credited.currency))
                        }
                        Outcome.Success(Deposited(amounts.balance(balance, money.currency), bonusCredit))
                    }
                } catch (e: Abort) {
                    Outcome.Failure(e.error)
                }
            }
        }
    }

    /**
     * The bonus [money] earns, or `null` for none.
     *
     * Resolved before the transaction opens, so a bonus currency missing from the registry is refused
     * without a write. One that rounds to zero is dropped rather than recorded: a ledger row for
     * nothing is noise.
     */
    private fun bonusFor(money: Money, requested: Boolean?): Outcome<Money?> {
        val rule = money.currency.bonus
            ?: return if (requested == true) {
                Outcome.Failure(EconomyError.NoBonusCurrency(money.currency.code))
            } else {
                Outcome.Success(null)
            }
        if (!(requested ?: rule.byDefault)) return Outcome.Success(null)
        return amounts.currency(rule.currency).map { bonusCurrency ->
            amounts.rounded(money.amount.multiply(rule.ratio), bonusCurrency).takeIf { it.amount.signum() > 0 }
        }
    }

    private fun refusedCredit(context: String) =
        EconomyError.StorageFailure(context, "the balance guard refused a credit")

    /** Carries a failure out of a transaction that must not commit — see `Transfer.Abort`. */
    private class Abort(val error: EconomyError) : RuntimeException(null, null, false, false)
}
