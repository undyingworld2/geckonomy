package com.the1mason.geckonomy.infrastructure.vault

import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.OperationResult
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.result.TransferResult
import com.the1mason.geckonomy.application.usecase.FormatMoney
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.infrastructure.i18n.ErrorMessages
import com.the1mason.geckonomy.infrastructure.i18n.MessageKey
import com.the1mason.geckonomy.infrastructure.i18n.MessageService
import com.the1mason.geckonomy.infrastructure.i18n.Placeholders
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.milkbowl.vault2.economy.EconomyResponse
import net.milkbowl.vault2.economy.EconomyResponse.ResponseType
import net.milkbowl.vault2.economy.MultiEconomyResponse
import java.math.BigDecimal

/**
 * Builds Vault v2 responses from [Outcome]s (VAULT_INTEGRATION.md §2).
 *
 * The five [EconomyError] variants all collapse to `FAILURE`; the distinction survives only in
 * [errorMessage], which is rendered from the `error.*` language keys rather than hard-coded, because
 * a caller commonly shows it to the player who triggered it. It keeps the `<prefix>` for that reason:
 * text surfacing inside a foreign plugin's output should say whose refusal it is.
 */
class ResponseMapper(
    private val messages: MessageService,
    format: FormatMoney,
) {

    /** Shared with the command path, so the two cannot disagree about what an error says. */
    private val errors = ErrorMessages(messages, format)

    /**
     * @param requested the amount the caller asked to move, echoed back as `EconomyResponse.amount`.
     *   A failure reports zero for it: nothing moved.
     */
    fun response(outcome: OperationResult, requested: BigDecimal): EconomyResponse =
        when (outcome) {
            is Outcome.Success -> EconomyResponse(requested, outcome.value.amount, ResponseType.SUCCESS, "")
            is Outcome.Failure -> failure(outcome.error)
        }

    /** For `has`/`canDeposit`-shaped answers, where the balance is not part of the result. */
    fun booleanResponse(outcome: Outcome<Boolean>, requested: BigDecimal, balance: BigDecimal): EconomyResponse =
        when (outcome) {
            is Outcome.Success ->
                if (outcome.value) EconomyResponse(requested, balance, ResponseType.SUCCESS, "")
                else EconomyResponse(BigDecimal.ZERO, balance, ResponseType.FAILURE, "")
            is Outcome.Failure -> failure(outcome.error)
        }

    fun failure(error: EconomyError): EconomyResponse =
        EconomyResponse(BigDecimal.ZERO, BigDecimal.ZERO, ResponseType.FAILURE, errorMessage(error))

    /**
     * A currency string that resolved to nothing.
     *
     * Takes the raw text rather than an [EconomyError.UnknownCurrency], because a caller may pass
     * something that is not a well-formed code at all (`"GEMS!"`) and so has no [CurrencyCode] to
     * carry. Echoing back exactly what arrived is what makes the message useful.
     */
    fun unknownCurrency(raw: String): EconomyResponse =
        EconomyResponse(BigDecimal.ZERO, BigDecimal.ZERO, ResponseType.FAILURE, unknownCurrencyMessage(raw))

    fun unknownCurrencyMessage(raw: String): String =
        PLAIN.serialize(messages.render(MessageKey.ERROR_UNKNOWN_CURRENCY, Placeholders.text("currency", raw)))

    /** A name the legacy API passed that resolves to no player we know of — there is no account id to report. */
    fun playerNotFoundMessage(name: String): String =
        PLAIN.serialize(messages.render(MessageKey.ERROR_PLAYER_NOT_FOUND, Placeholders.text("target", name)))

    fun notImplemented(reason: String): EconomyResponse =
        EconomyResponse(BigDecimal.ZERO, BigDecimal.ZERO, ResponseType.NOT_IMPLEMENTED, reason)

    /** Both resulting balances ride along via `addBalance`, which is why a transfer needs its own type. */
    fun transfer(outcome: TransferResult, from: AccountId, to: AccountId, requested: BigDecimal): MultiEconomyResponse =
        when (outcome) {
            is Outcome.Success -> MultiEconomyResponse(requested, ResponseType.SUCCESS, "").apply {
                addBalance(from.value, outcome.value.payerBalance.amount)
                addBalance(to.value, outcome.value.payeeBalance.amount)
            }
            is Outcome.Failure ->
                MultiEconomyResponse(BigDecimal.ZERO, ResponseType.FAILURE, errorMessage(outcome.error))
        }

    /**
     * No `target` is passed: a Vault caller gets the UUID it handed us, because this path never read a
     * name and reading one would be the database round trip it exists to avoid.
     */
    fun errorMessage(error: EconomyError): String = PLAIN.serialize(errors.render(error))

    private companion object {
        val PLAIN: PlainTextComponentSerializer = PlainTextComponentSerializer.plainText()
    }
}
