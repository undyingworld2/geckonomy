package com.the1mason.geckonomy.infrastructure.bukkit.command

import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.service.EconomyService
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.infrastructure.bukkit.CurrencyAccess
import com.the1mason.geckonomy.infrastructure.bukkit.CurrencyAccess.Action
import com.the1mason.geckonomy.infrastructure.bukkit.PlayerTargets
import com.the1mason.geckonomy.infrastructure.i18n.FormatMoney
import com.the1mason.geckonomy.infrastructure.i18n.MessageKey
import com.the1mason.geckonomy.infrastructure.i18n.Placeholders
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/balance [player] [currency]` (SPEC.md §7).
 *
 * The rules, with no Brigadier in sight — the node that parses the arguments is in
 * [GeckonomyCommands], and everything worth testing is here.
 */
internal class BalanceCommand(
    private val economy: EconomyService,
    private val access: CurrencyAccess,
    private val targets: PlayerTargets,
    private val replies: CommandReplies,
    private val format: FormatMoney,
) {

    /**
     * @param target the name typed, or `null` for the sender's own balance.
     * @param quick the target resolved from the cheap main-thread sources before this was launched;
     *   `null` means they were not found there and the account table is worth asking.
     */
    suspend fun execute(sender: CommandSender, currency: Currency, target: String?, quick: AccountId?) {
        if (target == null) self(sender, currency) else other(sender, currency, target, quick)
    }

    /**
     * Own balance. Console has no account, so it is refused here rather than answered with a zero it
     * would have to invent.
     */
    private suspend fun self(sender: CommandSender, currency: Currency) {
        if (sender !is Player) {
            replies.send(sender, MessageKey.ERROR_PLAYERS_ONLY, Placeholders.text("usage", "/balance <player>"))
            return
        }
        access.refusal(sender, Action.BALANCE, currency)?.let { refusal ->
            replies.send(sender, refusal, Placeholders.currency(currency, format))
            return
        }
        when (val result = economy.balance(AccountId(sender.uniqueId), currency.code)) {
            is Outcome.Success -> replies.send(
                sender,
                MessageKey.BALANCE_SELF,
                Placeholders.of(
                    Placeholders.money("formatted", result.value, format),
                    Placeholders.currency(currency, format, result.value.amount),
                ),
            )
            is Outcome.Failure -> replies.sendError(sender, result.error, sender.name)
        }
    }

    /**
     * Another player's balance.
     *
     * Both gates are [Action.BALANCE_OTHERS]'s: the `balance-check-others` flag, and
     * `geckonomy.balance.others.<code>` — which the base `geckonomy.balance.others` node has already
     * been required on top of, by the Brigadier node.
     */
    private suspend fun other(sender: CommandSender, currency: Currency, target: String, quick: AccountId?) {
        access.refusal(sender, Action.BALANCE_OTHERS, currency)?.let { refusal ->
            replies.send(sender, refusal, Placeholders.currency(currency, format))
            return
        }
        val id = quick ?: targets.resolve(target)
        if (id == null) {
            replies.send(sender, MessageKey.ERROR_PLAYER_NOT_FOUND, Placeholders.text("target", target))
            return
        }
        when (val result = economy.balance(id, currency.code)) {
            is Outcome.Success -> replies.send(
                sender,
                MessageKey.BALANCE_OTHER,
                Placeholders.of(
                    Placeholders.text("target", target),
                    Placeholders.money("formatted", result.value, format),
                    Placeholders.currency(currency, format, result.value.amount),
                ),
            )
            is Outcome.Failure -> replies.sendError(sender, result.error, target)
        }
    }
}
