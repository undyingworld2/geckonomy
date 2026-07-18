package com.the1mason.geckonomy.infrastructure.bukkit.command

import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.service.EconomyService
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.Money
import com.the1mason.geckonomy.infrastructure.bukkit.CurrencyAccess
import com.the1mason.geckonomy.infrastructure.bukkit.CurrencyAccess.Action
import com.the1mason.geckonomy.infrastructure.bukkit.MainThread
import com.the1mason.geckonomy.infrastructure.bukkit.PlayerTargets
import com.the1mason.geckonomy.infrastructure.i18n.FormatMoney
import com.the1mason.geckonomy.infrastructure.i18n.MessageKey
import com.the1mason.geckonomy.infrastructure.i18n.MessageService
import com.the1mason.geckonomy.infrastructure.i18n.Placeholders
import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.math.BigDecimal

/**
 * `/pay <player> <amount> [currency]` (SPEC.md §7, FR-CMD1).
 *
 * Goes through the atomic transfer use case, never withdraw-then-deposit: the two halves must commit
 * together or not at all, and that guarantee exists exactly once, in `Transfer` (ARCHITECTURE.md §5).
 */
internal class PayCommand(
    private val economy: EconomyService,
    private val access: CurrencyAccess,
    private val targets: PlayerTargets,
    private val replies: CommandReplies,
    private val messages: MessageService,
    private val format: FormatMoney,
    private val main: MainThread,
    private val server: Server,
) {

    suspend fun execute(
        sender: CommandSender,
        currency: Currency,
        target: String,
        amount: BigDecimal,
        quick: AccountId?,
    ) {
        if (sender !is Player) {
            replies.send(sender, MessageKey.ERROR_PLAYERS_ONLY, Placeholders.text("usage", "/pay <player> <amount>"))
            return
        }
        access.refusal(sender, Action.PAY, currency)?.let { refusal ->
            replies.send(sender, refusal, Placeholders.currency(currency, format))
            return
        }
        val payee = quick ?: targets.resolve(target)
        if (payee == null) {
            replies.send(sender, MessageKey.ERROR_PLAYER_NOT_FOUND, Placeholders.text("target", target))
            return
        }
        val payer = AccountId(sender.uniqueId)
        // Caught here rather than left to the economy: a self-transfer is a no-op the ledger would
        // still record twice, and `Transfer` has no reason to know the two ids came from one person.
        if (payer == payee) {
            replies.send(sender, MessageKey.PAY_SELF)
            return
        }

        // Attributed to Geckonomy, the default: `Attribution` records which *plugin* drove a change,
        // and the ledger already names the payer on both rows.
        when (val result = economy.transfer(payer, payee, amount, currency.code)) {
            is Outcome.Success -> {
                replies.send(
                    sender,
                    MessageKey.PAY_SENT,
                    Placeholders.of(
                        Placeholders.text("target", target),
                        Placeholders.money("formatted", Money(amount, currency), format),
                        Placeholders.currency(currency, format, amount),
                    ),
                )
                notifyPayee(payee, sender.name, Money(amount, currency))
            }
            is Outcome.Failure -> replies.sendError(sender, result.error, target)
        }
    }

    /**
     * Tells the payee, if they are here to hear it.
     *
     * Offline is not a failure: the money moved, and the ledger is what records it. On the main
     * thread, because looking a player up is a Bukkit call as much as messaging them is.
     */
    private fun notifyPayee(payee: AccountId, from: String, paid: Money) {
        main.execute {
            server.getPlayer(payee.value)?.let { online ->
                messages.send(
                    online,
                    MessageKey.PAY_RECEIVED,
                    Placeholders.of(
                        Placeholders.text("sender", from),
                        Placeholders.money("formatted", paid, format),
                        Placeholders.currency(paid.currency, format, paid.amount),
                    ),
                )
            }
        }
    }
}
