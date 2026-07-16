package com.the1mason.geckonomy.application.result

import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.Money
import java.math.BigDecimal

/**
 * Why an economy operation did not succeed.
 *
 * Sealed so an adapter's `when` is exhaustive and a new variant fails the build rather than slipping
 * through as a generic failure (ARCHITECTURE.md §6). These are *expected* outcomes — a typo'd
 * currency, an empty wallet — travelling as data rather than exceptions, because every caller must
 * handle them (CODING_STANDARDS.md §4).
 *
 * Each variant carries what a message needs to name the problem, and nothing more: the M5 renderer
 * turns these into `lang/en.yml`'s `error.*` keys, and M6 maps them to Vault's `ResponseType`.
 */
sealed interface EconomyError {

    /** No currency is configured with [code]. */
    data class UnknownCurrency(val code: CurrencyCode) : EconomyError

    /** There is no account [id] — it was never created, or it has been deleted. */
    data class AccountNotFound(val id: AccountId) : EconomyError

    /**
     * [id] does not hold [required], and overdraft is off.
     *
     * Carries only what was *asked for*, not what is available: `lang/en.yml`'s `pay.insufficient`
     * renders the required amount alone, and reporting the balance would cost an extra read — the
     * very read that `BalanceRepository.adjust`'s atomic, typed `null` refusal exists to avoid.
     */
    data class InsufficientFunds(val id: AccountId, val required: Money) : EconomyError

    /**
     * [amount] is not a legal amount for the operation: zero or negative where a positive amount is
     * required, or a balance the overdraft rule forbids.
     *
     * [reason] is for the log and for a developer reading it, not for a player — `error.invalid-amount`
     * is a single generic message, and M7 pre-checks the cases worth wording individually.
     */
    data class InvalidAmount(val amount: BigDecimal, val reason: String) : EconomyError

    /**
     * Storage could not carry out the operation, or the plugin has a bug. Either way, nothing the
     * caller can fix.
     *
     * [cause] is the exception's *message*, never the exception: the guard that built this already
     * logged the stack trace, and an adapter must not be able to render one at a player.
     *
     * @property context what was being attempted, in words ("depositing 5 to <uuid>").
     */
    data class StorageFailure(val context: String, val cause: String?) : EconomyError
}
