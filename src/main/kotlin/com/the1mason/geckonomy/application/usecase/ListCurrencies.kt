package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.port.CurrencyRegistry

/**
 * Every configured currency (SPEC.md FR-C1) — for `/balance`'s tab completion and Vault's currency
 * listing.
 *
 * Deliberately **not** `suspend` and not an `Outcome`: the registry is an in-memory map that cannot
 * fail, and dressing it as an async fallible call would lie about its cost to every caller. Same
 * reasoning [CurrencyRegistry] gives for not being suspend itself.
 */
class ListCurrencies(private val currencies: CurrencyRegistry) {

    /** All currencies, default first — the order a listing wants, and the one `/balance` completes in. */
    operator fun invoke(): List<Currency> = currencies.all().sortedByDescending { it.isDefault }
}
