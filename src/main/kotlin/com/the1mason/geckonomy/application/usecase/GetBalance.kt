package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.OperationResult
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.result.then
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.port.AccountRepository
import com.the1mason.geckonomy.domain.port.BalanceRepository
import java.math.BigDecimal

/**
 * What an account holds in a currency (SPEC.md FR-B1).
 *
 * The most-called operation in the plugin — every `/balance`, every Vault `getBalance` for an offline
 * player, every mirror hydration on join — so its shape is chosen around the common case costing one
 * query.
 */
class GetBalance internal constructor(
    private val accounts: AccountRepository,
    private val balances: BalanceRepository,
    private val amounts: Amounts,
    private val guard: StorageGuard,
) {

    /**
     * The balance, or why there isn't one.
     *
     * A missing row is **not** a missing account: `BalanceRepository.get` returns `null` both for an
     * account that never existed and for a currency added to config after that account was created
     * (DATA_MODEL.md §6). Only the second is legitimate, and it reads as **zero** — matching
     * `adjust`'s "a missing balance row counts as zero" contract exactly, because a `GetBalance` that
     * reported `starting-balance` here while the next deposit produced `5` would have the two paths
     * disagreeing about the same account.
     *
     * So `exists` is asked **only when the row is missing**: a stored balance already proves the
     * account, and the FK guarantees it (DATA_MODEL.md §1). The common case stays at one query, and
     * the extra one is paid only on the path that is about to answer zero or fail.
     */
    suspend operator fun invoke(id: AccountId, currency: CurrencyCode): OperationResult =
        amounts.currency(currency).then { resolved ->
            guard.guarding({ "reading ${id.value}'s ${currency.value} balance" }) {
                val stored = balances.get(id, resolved)
                when {
                    stored != null -> Outcome.Success(amounts.balance(stored, resolved))
                    accounts.exists(id) -> Outcome.Success(amounts.balance(BigDecimal.ZERO, resolved))
                    else -> Outcome.Failure(EconomyError.AccountNotFound(id))
                }
            }
        }
}
