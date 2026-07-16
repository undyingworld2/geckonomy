package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.result.map
import com.the1mason.geckonomy.application.result.then
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.Money
import com.the1mason.geckonomy.domain.policy.CurrencyResolution
import com.the1mason.geckonomy.domain.policy.CurrencyValidation
import com.the1mason.geckonomy.domain.policy.RoundingPolicy
import java.math.BigDecimal

/**
 * "Validate the currency and the amount before touching storage" (`tasks/M4-application.md`), stated
 * once.
 *
 * Every mutating use case opens the same way — resolve the code, reject a nonsense amount, round to
 * the currency's scale — and each of those steps has a subtlety worth getting right in one place
 * rather than eleven (see [positive]).
 *
 * @param rounding a **supplier**, not a policy. `settings.rounding-mode` is reloadable —
 *   `ConfigService.restartWarnings` deliberately does not warn about it, unlike `allow-overdraft` and
 *   `server-id` — so capturing a [RoundingPolicy] at wiring would make `/geckonomy reload` silently
 *   do nothing to it. Read per call, it behaves as CONFIGURATION.md §4 advertises.
 */
internal class Amounts(
    private val validation: CurrencyValidation,
    private val rounding: () -> RoundingPolicy,
) {

    /** Resolves [code], or reports it unknown. */
    fun currency(code: CurrencyCode): Outcome<Currency> = when (val resolved = validation.resolve(code)) {
        is CurrencyResolution.Resolved -> Outcome.Success(resolved.currency)
        is CurrencyResolution.Unknown -> Outcome.Failure(EconomyError.UnknownCurrency(resolved.code))
    }

    /**
     * An amount that must be strictly positive — deposit, withdraw, transfer (DOMAIN_MODEL.md §4.3).
     *
     * The currency is resolved first: `/pay Bob -5 coinz` is two problems at once, and the one the
     * player can act on is the currency. It also has to be resolved before the amount can be rounded
     * at all, so the order costs nothing.
     *
     * The sign is then checked twice, before and after rounding, and both are needed: the first
     * rejects `-5`, the second rejects dust. `0.004` in a 2-digit currency is positive going in and
     * `0.00` coming out, and a deposit that reports success while moving no money is worse than a
     * refusal.
     */
    fun positive(amount: BigDecimal, code: CurrencyCode): Outcome<Money> = round(amount, code).then { money ->
        when {
            amount.signum() <= 0 ->
                Outcome.Failure(EconomyError.InvalidAmount(amount, "amount must be greater than zero"))
            money.amount.signum() <= 0 ->
                Outcome.Failure(EconomyError.InvalidAmount(amount, "amount rounds to zero at this currency's scale"))
            else -> Outcome.Success(money)
        }
    }

    /** An amount that may be zero but not negative — the `has`/`canWithdraw` questions. */
    fun nonNegative(amount: BigDecimal, code: CurrencyCode): Outcome<Money> = round(amount, code).then { money ->
        if (amount.signum() < 0) Outcome.Failure(EconomyError.InvalidAmount(amount, "amount must not be negative"))
        else Outcome.Success(money)
    }

    /**
     * Any amount at all, including a negative one — an admin `set`.
     *
     * Whether a negative *balance* is allowed is
     * [com.the1mason.geckonomy.domain.policy.OverdraftPolicy]'s call, not this one's; `SetBalance`
     * asks it separately.
     */
    fun any(amount: BigDecimal, code: CurrencyCode): Outcome<Money> = round(amount, code)

    /**
     * A stored amount as [Money] at the currency's display scale.
     *
     * Storage keeps every amount at `SqlDialect.MONEY_SCALE` (4), so a 2-digit currency reads back as
     * `100.0000`. Everything written was rounded first, so this changes no value — it only stops that
     * scale surfacing in every integrator's UI as the balance of a currency that has two decimals.
     */
    fun balance(stored: BigDecimal, currency: Currency): Money =
        Money(rounding().round(stored, currency), currency)

    private fun round(amount: BigDecimal, code: CurrencyCode): Outcome<Money> =
        currency(code).map { Money(rounding().round(amount, it), it) }
}
