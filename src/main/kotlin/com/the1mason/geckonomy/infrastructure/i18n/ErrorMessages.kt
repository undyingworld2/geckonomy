package com.the1mason.geckonomy.infrastructure.i18n

import com.the1mason.geckonomy.application.result.EconomyError
import net.kyori.adventure.text.Component

/**
 * An [EconomyError] as the sentence a player reads.
 *
 * The five variants are one-to-one with the `error.*` keys, so this `when` is total by construction
 * and a new variant fails the build. It lives here, not in either caller, because both the Vault
 * response mapper and the commands answer for the same errors — and a second copy of the mapping would
 * drift the moment one of them gained a variant.
 */
internal class ErrorMessages(
    private val messages: MessageService,
    private val format: FormatMoney,
) {

    /**
     * @param target the name to show for an error that names an account. Vault has only a UUID to
     *   report — it never read a name, and reading one would be the database round trip its sync path
     *   exists to avoid — whereas a command knows exactly what the player typed. Falls back to the
     *   UUID, which is still better than nothing in a log or a foreign plugin's output.
     */
    fun render(error: EconomyError, target: String? = null): Component = when (error) {
        is EconomyError.UnknownCurrency ->
            messages.render(MessageKey.ERROR_UNKNOWN_CURRENCY, Placeholders.text("currency", error.code.value))

        is EconomyError.AccountNotFound -> messages.render(
            MessageKey.ERROR_ACCOUNT_NOT_FOUND,
            Placeholders.text("target", target ?: error.id.value.toString()),
        )

        is EconomyError.InsufficientFunds -> messages.render(
            MessageKey.ERROR_INSUFFICIENT_FUNDS,
            Placeholders.of(
                // The use case's own name when it read one, else what the caller knows, else the UUID.
                Placeholders.text("target", error.name ?: target ?: error.id.value.toString()),
                Placeholders.money("formatted", error.required, format),
            ),
        )

        is EconomyError.InvalidAmount -> messages.render(MessageKey.ERROR_INVALID_AMOUNT)
        is EconomyError.StorageFailure -> messages.render(MessageKey.ERROR_STORAGE)
    }
}
