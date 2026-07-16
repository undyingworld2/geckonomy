package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.result.then
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.port.AccountRepository
import java.math.BigDecimal

/**
 * Whether a deposit would be accepted, without making one (SPEC.md FR-B4).
 *
 * Exists for Vault's `canDeposit`, whose integrators call it before a purchase to decide whether to
 * offer the transaction at all.
 *
 * **It answers `true` for any existing account and any valid amount, and that is not an oversight.**
 * Geckonomy has no per-account ceiling and no deposit permission; the only real limit is what storage
 * can hold (~922 trillion, `SqlDialect.MONEY_SCALE`), which the application cannot see by design and
 * which no player will meet. The use case exists so the *question* has one answer in one place —
 * were a cap ever added, this is where it would go, and every caller would already be asking.
 */
class CanDeposit internal constructor(
    private val accounts: AccountRepository,
    private val amounts: Amounts,
    private val guard: StorageGuard,
) {

    /** `true` if depositing [amount] of [currency] into [id] would succeed. */
    suspend operator fun invoke(id: AccountId, amount: BigDecimal, currency: CurrencyCode): Outcome<Boolean> =
        amounts.positive(amount, currency).then {
            guard.guarding({ "checking whether ${id.value} can be given $amount ${currency.value}" }) {
                if (accounts.exists(id)) Outcome.Success(true)
                else Outcome.Failure(EconomyError.AccountNotFound(id))
            }
        }
}
