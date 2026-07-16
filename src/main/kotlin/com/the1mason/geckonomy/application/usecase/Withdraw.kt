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
 * Takes money from an account, refusing to overdraw it (SPEC.md FR-B3, FR-B6).
 *
 * See [Deposit] for why this runs inside a [UnitOfWork].
 *
 * **The overdraft rule is not checked here.** It lives inside `BalanceRepository.adjust`, as one
 * statement with the update it guards, and `null` is its refusal. A check in this class would be a
 * read followed by a write, and two players spending at once could both pass it and jointly overdraw
 * an account — the exact race the port's KDoc explains it cannot delegate to a caller. So there is no
 * `if` about funds anywhere in this file, and that absence is the design.
 */
class Withdraw internal constructor(
    private val unitOfWork: UnitOfWork,
    private val amounts: Amounts,
    private val transactions: TransactionFactory,
    private val guard: StorageGuard,
) {

    /**
     * Debits [amount] of [currency] from [id].
     *
     * @return the resulting balance, or [EconomyError.InsufficientFunds] if the guard refused.
     */
    suspend operator fun invoke(
        id: AccountId,
        amount: BigDecimal,
        currency: CurrencyCode,
        by: Attribution = Attribution.GECKONOMY,
    ): OperationResult = amounts.positive(amount, currency).then { money ->
        guard.guarding({ "withdrawing $amount ${currency.value} from ${id.value}" }) {
            unitOfWork.transaction { ctx ->
                if (!ctx.accounts.exists(id)) {
                    Outcome.Failure(EconomyError.AccountNotFound(id))
                } else {
                    val balance = ctx.balance.adjust(id, money.currency, money.amount.negate())
                        ?: return@transaction Outcome.Failure(EconomyError.InsufficientFunds(id, money))
                    ctx.log.append(
                        transactions.entry(
                            accountId = id,
                            currency = money.currency.code,
                            delta = money.amount.negate(),
                            resultingBalance = balance,
                            type = TransactionType.WITHDRAW,
                            by = by,
                        ),
                    )
                    Outcome.Success(amounts.balance(balance, money.currency))
                }
            }
        }
    }
}
