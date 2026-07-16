package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.result.map
import com.the1mason.geckonomy.application.result.then
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.policy.OverdraftPolicy
import java.math.BigDecimal

/**
 * Whether a withdrawal would be accepted, without making one (SPEC.md FR-B4).
 *
 * Distinct from [Has] despite the family resemblance: `has(5)` asks about the balance, while this
 * asks about the *rule*. With `allow-overdraft` on they diverge — a player with nothing can still
 * withdraw, so `has` is `false` while this is `true` — and a caller wanting the second question must
 * not have to know about the first.
 *
 * Asks [OverdraftPolicy] the same question the SQL guard asks, about the same number: the balance
 * *after* the withdrawal. The policy instance is the one compiled into the balance repository's
 * `WHERE` clause, so this cannot promise what the write then refuses.
 *
 * **Advisory only** — see [Has]. The authoritative check is atomic, inside `adjust`.
 */
class CanWithdraw internal constructor(
    private val getBalance: GetBalance,
    private val amounts: Amounts,
    private val overdraft: OverdraftPolicy,
) {

    /** `true` if taking [amount] of [currency] from [id] would succeed. */
    suspend operator fun invoke(id: AccountId, amount: BigDecimal, currency: CurrencyCode): Outcome<Boolean> =
        amounts.nonNegative(amount, currency).then { requested ->
            getBalance(id, currency).map { balance -> overdraft.permits(balance.amount - requested.amount) }
        }
}
