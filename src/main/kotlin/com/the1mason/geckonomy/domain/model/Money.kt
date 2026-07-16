package com.the1mason.geckonomy.domain.model

import com.the1mason.geckonomy.domain.CurrencyMismatch
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * An amount bound to a currency. All monetary arithmetic goes through here.
 *
 * Binding the amount to its currency is what makes [CurrencyMismatch] detectable: a bare
 * [BigDecimal] passed between currencies is a silent bug, whereas [Money] refuses at the point of
 * the mistake (DOMAIN_MODEL.md §1).
 *
 * **Scale is not normalized on construction.** `Money(1.0, coins)` and `Money(1.00, coins)` are
 * *unequal*, because [BigDecimal.equals] compares scale as well as value. This is deliberate:
 * rounding is an explicit step ([rounded]) applied before persistence, not something the model does
 * behind the caller's back at every intermediate sum. Compare amounts with
 * [BigDecimal.compareTo] — or round both sides first — rather than relying on [equals].
 *
 * @property amount the quantity; always [BigDecimal], never a floating-point type (SPEC.md NFR-3).
 * @property currency the unit [amount] is denominated in.
 */
data class Money(val amount: BigDecimal, val currency: Currency) {

    /**
     * Sum of two amounts in the same currency.
     *
     * @throws CurrencyMismatch if [other] is a different currency.
     */
    operator fun plus(other: Money): Money =
        copy(amount = amount + requireSameCurrency(other).amount)

    /**
     * Difference of two amounts in the same currency. May go negative — whether that is *allowed*
     * is [com.the1mason.geckonomy.domain.policy.OverdraftPolicy]'s call, not this type's.
     *
     * @throws CurrencyMismatch if [other] is a different currency.
     */
    operator fun minus(other: Money): Money =
        copy(amount = amount - requireSameCurrency(other).amount)

    /** Whether this amount is below zero. Zero is not negative. */
    fun isNegative(): Boolean = amount.signum() < 0

    /**
     * This amount scaled to the currency's [Currency.fractionalDigits] using [mode].
     *
     * The rounding mode is a parameter rather than a field because it is a server-wide setting
     * ([com.the1mason.geckonomy.domain.policy.RoundingPolicy] owns it); [Money] should not carry a
     * copy of configuration.
     */
    fun rounded(mode: RoundingMode): Money =
        copy(amount = amount.setScale(currency.fractionalDigits, mode))

    private fun requireSameCurrency(other: Money): Money =
        if (currency.code == other.currency.code) other
        else throw CurrencyMismatch(currency.code, other.currency.code)
}
