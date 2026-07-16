package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.result.map
import com.the1mason.geckonomy.application.result.then
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.CurrencyCode
import java.math.BigDecimal

/**
 * Whether an account holds at least an amount (SPEC.md FR-B2).
 *
 * Composed from [GetBalance] rather than reaching for the repository itself: "do you have 5" is
 * "what do you have, is it ≥ 5", and the account-missing and unknown-currency answers should not be
 * written twice.
 *
 * **Advisory only.** By the time a caller acts on `true`, the balance may have changed. Nothing here
 * reserves anything — a withdrawal's real check is the atomic guard inside `BalanceRepository.adjust`,
 * which is why that guard exists rather than living in a caller.
 */
class Has internal constructor(
    private val getBalance: GetBalance,
    private val amounts: Amounts,
) {

    /** `true` if [id] holds [amount] or more of [currency]. Zero is a fair question; negative is not. */
    suspend operator fun invoke(id: AccountId, amount: BigDecimal, currency: CurrencyCode): Outcome<Boolean> =
        amounts.nonNegative(amount, currency).then { required ->
            getBalance(id, currency).map { balance -> balance.amount >= required.amount }
        }
}
