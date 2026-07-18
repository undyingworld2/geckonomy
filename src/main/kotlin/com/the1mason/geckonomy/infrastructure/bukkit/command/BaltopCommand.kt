package com.the1mason.geckonomy.infrastructure.bukkit.command

import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.service.EconomyService
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.infrastructure.bukkit.CurrencyAccess
import com.the1mason.geckonomy.infrastructure.bukkit.CurrencyAccess.Action
import com.the1mason.geckonomy.infrastructure.bukkit.MainThread
import com.the1mason.geckonomy.infrastructure.i18n.FormatMoney
import com.the1mason.geckonomy.infrastructure.i18n.MessageKey
import com.the1mason.geckonomy.infrastructure.i18n.MessageService
import com.the1mason.geckonomy.infrastructure.i18n.Placeholders
import org.bukkit.command.CommandSender

/**
 * `/baltop [currency]` (SPEC.md §7, FR-CMD3).
 *
 * @param size `settings.baltop-size`. A supplier, not a value: the setting is reloadable, and
 *   capturing it would make `/geckonomy reload` report success and change nothing.
 */
internal class BaltopCommand(
    private val economy: EconomyService,
    private val access: CurrencyAccess,
    private val replies: CommandReplies,
    private val messages: MessageService,
    private val format: FormatMoney,
    private val main: MainThread,
    private val size: () -> Int,
) {

    suspend fun execute(sender: CommandSender, currency: Currency) {
        access.refusal(sender, Action.BALTOP, currency)?.let { refusal ->
            replies.send(sender, refusal, Placeholders.currency(currency, format))
            return
        }
        when (val result = economy.top(currency.code, size())) {
            is Outcome.Failure -> replies.sendError(sender, result.error)
            is Outcome.Success ->
                if (result.value.isEmpty()) {
                    replies.send(sender, MessageKey.BALTOP_EMPTY, Placeholders.currency(currency, format))
                } else {
                    // One hop for the whole table, not one per row: each `execute` is a scheduler task,
                    // and a ten-row baltop would otherwise be ten of them, interleavable with anything
                    // else the tick is doing.
                    main.execute {
                        messages.send(sender, MessageKey.BALTOP_HEADER, Placeholders.currency(currency, format))
                        result.value.forEach { row ->
                            messages.send(
                                sender,
                                MessageKey.BALTOP_ENTRY,
                                Placeholders.of(
                                    Placeholders.number("rank", row.rank),
                                    Placeholders.text("name", row.name),
                                    Placeholders.money("formatted", row.balance, format),
                                    Placeholders.currency(currency, format, row.balance.amount),
                                ),
                            )
                        }
                    }
                }
        }
    }
}
