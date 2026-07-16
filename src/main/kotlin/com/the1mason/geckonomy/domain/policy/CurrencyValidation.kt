package com.the1mason.geckonomy.domain.policy

import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.port.CurrencyRegistry

/**
 * The outcome of looking up a currency code.
 *
 * A sealed result rather than a nullable [Currency] or a thrown exception: an unknown code is an
 * expected, routine outcome — a player typos `/pay Bob 5 coinz`, a Vault caller names a currency
 * this server does not configure — so it is data to be handled, not an error to be raised
 * (CODING_STANDARDS.md §4). The application layer maps [Unknown] to
 * `EconomyError.UnknownCurrency`; the domain cannot name that type, since it lives a layer out.
 */
sealed interface CurrencyResolution {

    /** The code named a configured currency. */
    data class Resolved(val currency: Currency) : CurrencyResolution

    /** No currency is configured with this code. Carries [code] so callers can report *which*. */
    data class Unknown(val code: CurrencyCode) : CurrencyResolution
}

/**
 * Resolves a [CurrencyCode] to its [Currency] (SPEC.md FR-C4).
 *
 * A thin wrapper over [CurrencyRegistry.byCode] that turns "not found" into the typed
 * [CurrencyResolution.Unknown] the rest of the system handles, so that the registry stays a plain
 * lookup and the *policy* — what a miss means — lives in one place.
 */
class CurrencyValidation(private val registry: CurrencyRegistry) {

    /** Resolves [code], or reports it as unknown. */
    fun resolve(code: CurrencyCode): CurrencyResolution =
        registry.byCode(code)
            ?.let(CurrencyResolution::Resolved)
            ?: CurrencyResolution.Unknown(code)
}
