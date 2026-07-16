package com.the1mason.geckonomy.domain

import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.CurrencyScope
import com.the1mason.geckonomy.domain.model.Money
import java.math.BigDecimal

/**
 * Currencies for tests, mirroring the example config in CONFIGURATION.md §1 so the suite exercises
 * realistic definitions rather than invented ones.
 *
 * The pair is chosen to cover the axis most likely to break money math: [COINS] has fractional
 * digits, [GEMS] has none.
 */
object TestCurrencies {

    /** Default, network-scoped, 2 fractional digits. */
    val COINS = Currency(
        code = CurrencyCode("coins"),
        singular = "Coin",
        plural = "Coins",
        symbol = "$",
        fractionalDigits = 2,
        startingBalance = BigDecimal("100.00"),
        isDefault = true,
        scope = CurrencyScope.NETWORK,
        transferable = true,
        checkableOthers = true,
        showInBaltop = true,
        format = "<symbol><amount>",
    )

    /** Non-default, server-scoped, whole units only. */
    val GEMS = Currency(
        code = CurrencyCode("gems"),
        singular = "Gem",
        plural = "Gems",
        symbol = "◆",
        fractionalDigits = 0,
        startingBalance = BigDecimal.ZERO,
        isDefault = false,
        scope = CurrencyScope.SERVER,
        transferable = false,
        checkableOthers = false,
        showInBaltop = true,
        format = "<amount> <currency>",
    )
}

/** `"1.50".coins` — keeps the arithmetic under test visible instead of buried in constructors. */
val String.coins: Money get() = Money(BigDecimal(this), TestCurrencies.COINS)

/** `"3".gems` — see [coins]. */
val String.gems: Money get() = Money(BigDecimal(this), TestCurrencies.GEMS)
