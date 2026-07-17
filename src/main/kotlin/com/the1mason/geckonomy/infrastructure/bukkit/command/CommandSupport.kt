package com.the1mason.geckonomy.infrastructure.bukkit.command

import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import com.the1mason.geckonomy.infrastructure.bukkit.MainThread
import com.the1mason.geckonomy.infrastructure.i18n.ErrorMessages
import com.the1mason.geckonomy.infrastructure.i18n.MessageKey
import com.the1mason.geckonomy.infrastructure.i18n.MessageService
import com.the1mason.geckonomy.infrastructure.i18n.Placeholders
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender
import java.math.BigDecimal

/**
 * Replying to a command sender, from wherever the use case happened to finish.
 *
 * Every send hops to the main thread, because a reply is a Bukkit call and a handler resumes on
 * whatever thread the economy answered on (CODING_STANDARDS.md §3). Centralised so no handler has to
 * remember, and so a test can hand over an inline [MainThread] and stay synchronous.
 */
internal class CommandReplies(
    private val messages: MessageService,
    private val errors: ErrorMessages,
    private val main: MainThread,
) {

    fun send(to: CommandSender, key: MessageKey, resolvers: TagResolver = TagResolver.empty()) {
        main.execute { messages.send(to, key, resolvers) }
    }

    /**
     * The message for a failed economy call.
     *
     * @param target what the player typed, so `account-not-found` names them rather than a UUID.
     */
    fun sendError(to: CommandSender, error: EconomyError, target: String? = null) {
        main.execute { to.sendMessage(errors.render(error, target)) }
    }
}

/**
 * Resolves the optional trailing `[currency]` argument.
 *
 * `null` — the argument omitted — is the default currency, which is the whole reason the argument is
 * optional (SPEC.md FR-B1). A code that names nothing is [MessageKey.ERROR_UNKNOWN_CURRENCY], and is
 * refused here rather than by the economy: the command layer needs the [Currency] anyway, to ask
 * `CurrencyAccess` about it before spending a query on it.
 */
internal fun CurrencyRegistry.resolveArgument(code: String?): Currency? =
    if (code == null) default() else byCode(CurrencyCode.parseOrNull(code) ?: return null)

/** `<currency>` for a message about a currency the registry could not resolve. */
internal fun unknownCurrency(raw: String): TagResolver = Placeholders.text("currency", raw)

/**
 * Parses an amount argument.
 *
 * `null` for anything that is not a number, which becomes `error.invalid-amount` — the command never
 * hands junk to the economy. What counts as a *sensible* amount (positive, non-dust, within range) is
 * `Amounts`' rule and is deliberately not second-guessed here; this only rejects what is not a number
 * at all.
 *
 * `BigDecimal(String)` rather than `toBigDecimalOrNull()`'s locale-sensitive cousins: money is never a
 * `Double` (CODING_STANDARDS.md §1), and `1e9` — which `BigDecimal` accepts — is a legitimate way to
 * type a billion.
 */
internal fun parseAmount(raw: String): BigDecimal? = try {
    BigDecimal(raw)
} catch (_: NumberFormatException) {
    null
}
