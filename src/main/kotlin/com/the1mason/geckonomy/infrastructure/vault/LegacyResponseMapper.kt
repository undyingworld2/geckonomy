package com.the1mason.geckonomy.infrastructure.vault

import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.OperationResult
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.policy.RoundingPolicy
import net.milkbowl.vault.economy.EconomyResponse
import net.milkbowl.vault.economy.EconomyResponse.ResponseType
import java.math.BigDecimal

/**
 * Builds legacy v1 responses (VAULT_INTEGRATION.md §8).
 *
 * A separate type from [ResponseMapper] because `net.milkbowl.vault.economy.EconomyResponse` is a
 * different class from the v2 one — `double` fields, no `@NotNull` — not merely a different shape.
 *
 * Deprecation is suppressed for the file: the entire legacy API is deprecated, and shipping it is the
 * point (FR-V6). Plugins still bound to v1 outnumber those on v2.
 */
@Suppress("DEPRECATION")
class LegacyResponseMapper(private val errors: ResponseMapper) {

    fun response(outcome: OperationResult, requested: BigDecimal): EconomyResponse = when (outcome) {
        is Outcome.Success ->
            EconomyResponse(requested.toDouble(), outcome.value.amount.toDouble(), ResponseType.SUCCESS, "")
        is Outcome.Failure -> failure(outcome.error)
    }

    fun failure(error: EconomyError): EconomyResponse =
        EconomyResponse(0.0, 0.0, ResponseType.FAILURE, errors.errorMessage(error))

    fun notImplemented(reason: String): EconomyResponse =
        EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, reason)

    fun playerNotFound(name: String): EconomyResponse =
        EconomyResponse(0.0, 0.0, ResponseType.FAILURE, errors.playerNotFoundMessage(name))
}

/**
 * `double` → `BigDecimal` at [currency]'s scale.
 *
 * `BigDecimal.valueOf`, never `BigDecimal(double)`: the latter takes the binary value literally, so
 * `0.1` becomes 0.1000000000000000055511151231257827, and a legacy caller's round number would land
 * in the ledger as noise. Precision past ~15 significant digits is still lost — that is the legacy
 * `double` API's own limit, and nothing here can recover it.
 */
internal fun Double.toMoney(currency: Currency, rounding: RoundingPolicy): BigDecimal =
    rounding.round(BigDecimal.valueOf(this), currency)
