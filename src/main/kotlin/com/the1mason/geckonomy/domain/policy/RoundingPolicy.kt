package com.the1mason.geckonomy.domain.policy

import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.Money
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Rounds amounts to a currency's fractional digits.
 *
 * Exists as an object rather than a loose function because the [mode] is a server-wide setting
 * (`settings.rounding-mode`, CONFIGURATION.md §2): holding it once here means no call site has to
 * remember to pass the configured value, and none can quietly pick a different one.
 *
 * Applied before any persistence (SPEC.md FR-B8) so that what is stored is what the currency can
 * actually represent — a 2-digit currency never holds a third decimal that later display rounds away.
 *
 * @param mode how to break ties; defaults to the config default.
 */
class RoundingPolicy(private val mode: RoundingMode = RoundingMode.HALF_UP) {

    /** [amount] scaled to [currency]'s fractional digits. */
    fun round(amount: BigDecimal, currency: Currency): BigDecimal =
        amount.setScale(currency.fractionalDigits, mode)

    /** [money] scaled to its own currency's fractional digits. */
    fun round(money: Money): Money = money.rounded(mode)
}
