package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.Attribution
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.result.TransferResult
import com.the1mason.geckonomy.application.result.Transferred
import com.the1mason.geckonomy.application.result.then
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.TransactionType
import com.the1mason.geckonomy.domain.port.UnitOfWork
import java.math.BigDecimal

/**
 * Moves money between two accounts, atomically (SPEC.md FR-B5).
 *
 * The operation the whole [UnitOfWork] port exists for: a debit without its credit destroys money, a
 * credit without its debit invents it, and either one is unrecoverable without a ledger to reconcile
 * against. All four writes — both legs and both rows — commit together or not at all.
 *
 * Does **not** enforce `Currency.transferable`. That flag is "a hard, server-wide rule enforced by the
 * command layer" (DOMAIN_MODEL.md §1) — a player-facing rule about `/pay`, not about moving money. An
 * admin correcting balances, or a plugin settling a trade, must not be refused by it. M7 checks it
 * before ever reaching this.
 */
class Transfer internal constructor(
    private val unitOfWork: UnitOfWork,
    private val amounts: Amounts,
    private val transactions: TransactionFactory,
    private val guard: StorageGuard,
) {

    /**
     * Moves [amount] of [currency] from [from] to [to].
     *
     * Debits first: the debit is the leg that can be refused, so refusing it costs no write. Both
     * accounts are checked inside the transaction, so the check and the move cannot disagree, and so
     * a missing account reads as [EconomyError.AccountNotFound] rather than an opaque foreign-key
     * `SQLException` (see [Deposit]).
     *
     * @return both resulting balances.
     */
    suspend operator fun invoke(
        from: AccountId,
        to: AccountId,
        amount: BigDecimal,
        currency: CurrencyCode,
        by: Attribution = Attribution.GECKONOMY,
    ): TransferResult = amounts.positive(amount, currency).then { money ->
        if (from == to) {
            // Refused before any IO. Paying yourself is a no-op that would still write two ledger
            // rows and, on MariaDB, take two locks on one row.
            Outcome.Failure(EconomyError.InvalidAmount(amount, "payer and payee are the same account"))
        } else {
            val context = { "transferring $amount ${currency.value} from ${from.value} to ${to.value}" }
            guard.guarding(context) {
                try {
                    unitOfWork.transaction { ctx ->
                        if (!ctx.accounts.exists(from)) throw Abort(EconomyError.AccountNotFound(from))
                        if (!ctx.accounts.exists(to)) throw Abort(EconomyError.AccountNotFound(to))

                        val debited = ctx.balance.adjust(from, money.currency, money.amount.negate())
                            ?: throw Abort(EconomyError.InsufficientFunds(from, money, ctx.accounts.findName(from)))
                        val credited = ctx.balance.adjust(to, money.currency, money.amount)
                            // Unreachable: a positive delta cannot fail a `>= 0` guard unless the
                            // balance was already negative, which cannot happen while the guard is on
                            // — and while it is off, there is no guard clause at all. If it ever does,
                            // the store is contradicting itself, which is not the payer's problem and
                            // is certainly not "insufficient funds".
                            ?: throw Abort(EconomyError.StorageFailure(context(), "the balance guard refused a credit"))

                        // Two rows, not one with two accounts, so each account's ledger reads as a
                        // complete history of its own balance (ARCHITECTURE.md §5).
                        ctx.log.append(
                            transactions.entry(
                                accountId = from,
                                currency = money.currency.code,
                                delta = money.amount.negate(),
                                resultingBalance = debited,
                                type = TransactionType.TRANSFER_OUT,
                                by = by,
                                counterparty = to,
                            ),
                        )
                        ctx.log.append(
                            transactions.entry(
                                accountId = to,
                                currency = money.currency.code,
                                delta = money.amount,
                                resultingBalance = credited,
                                type = TransactionType.TRANSFER_IN,
                                by = by,
                                counterparty = from,
                            ),
                        )

                        Outcome.Success(
                            Transferred(
                                payerBalance = amounts.balance(debited, money.currency),
                                payeeBalance = amounts.balance(credited, money.currency),
                            ),
                        )
                    }
                } catch (e: Abort) {
                    // The rollback already happened, inside SqlUnitOfWork, on the way out. All that
                    // is left is to put the error back on the happy road as a typed failure.
                    Outcome.Failure(e.error)
                }
            }
        }
    }

    /**
     * Carries a typed failure out of a transaction that must **not** commit.
     *
     * The most important five lines in this file. `SqlUnitOfWork` commits whatever the block returns
     * and rolls back only on a throwable — so `return@transaction Outcome.Failure(...)` would commit
     * the debit and report failure, which is precisely the money-destroying bug the transaction is
     * here to prevent. Insufficient funds is not exceptional, but *aborting a transaction* is the
     * only thing a throw can express, so it throws.
     *
     * Never escapes this class: caught immediately outside `transaction {}`, and inside the guard, so
     * [StorageGuard] never sees it. Stackless — it is control flow, not a fault, and filling in a
     * trace for every failed `/pay` would be pure cost.
     */
    private class Abort(val error: EconomyError) : RuntimeException(null, null, false, false)
}
