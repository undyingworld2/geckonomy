package com.the1mason.geckonomy.infrastructure.bukkit.command

import com.the1mason.geckonomy.application.result.DepositResult
import com.the1mason.geckonomy.application.result.Deposited
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.result.map
import com.the1mason.geckonomy.application.service.EconomyService
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.Money
import com.the1mason.geckonomy.infrastructure.balance.OnlineBalanceMirror
import com.the1mason.geckonomy.infrastructure.bukkit.PlayerTargets
import com.the1mason.geckonomy.infrastructure.i18n.FormatMoney
import com.the1mason.geckonomy.infrastructure.i18n.MessageKey
import com.the1mason.geckonomy.infrastructure.i18n.Placeholders
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender
import java.math.BigDecimal

/**
 * `/eco give|take|set|reset <player> [amount] [currency]` (SPEC.md §7, FR-CMD2), and `give`'s optional
 * trailing `bonus|nobonus`.
 *
 * Gated by `geckonomy.admin` alone. Per-currency permission nodes are deliberately **not** consulted —
 * they exist to shape what *players* may do, and an admin who can set any balance is not meaningfully
 * restrained by them. The currency must still exist, and the currency *flags* are equally irrelevant
 * here: `transferable: false` says players may not pay it, not that an admin may not grant it.
 *
 * Only `give` can grant a bonus (SPEC.md FR-B9). `take` and `set` never touch the bonus currency.
 *
 * Every change is written through to [mirror], as `VaultSyncPath` does for Vault writes: otherwise a
 * shop asking about an online player would answer from the balance before the admin touched it.
 */
internal class EcoCommand(
    private val economy: EconomyService,
    private val targets: PlayerTargets,
    private val replies: CommandReplies,
    private val format: FormatMoney,
    private val mirror: OnlineBalanceMirror,
) {

    /**
     * The four operations, each with the message it reports on success.
     *
     * @property reportsBalance whether `<formatted>` is the resulting balance or the amount moved.
     *   The two halves genuinely differ: "Set Bob's balance to $500" is about the balance, while
     *   "Gave $100 to Bob" is about the amount — and the use cases all return the *balance*, so giving
     *   $100 to a player holding $1000 would otherwise report "Gave $1100.00 to Bob".
     */
    enum class Operation(val label: String, val success: MessageKey, val reportsBalance: Boolean) {
        GIVE("give", MessageKey.ADMIN_GIVEN, reportsBalance = false),
        TAKE("take", MessageKey.ADMIN_TAKEN, reportsBalance = false),
        SET("set", MessageKey.ADMIN_SET, reportsBalance = true),

        /** Back to the currency's `starting-balance` — what a new account would have been seeded with. */
        RESET("reset", MessageKey.ADMIN_RESET, reportsBalance = true),
    }

    /**
     * @param amount ignored by [Operation.RESET], which takes its amount from the currency.
     * @param bonus [Operation.GIVE] only: grant or withhold the bonus; `null` follows the currency's config.
     */
    suspend fun execute(
        sender: CommandSender,
        operation: Operation,
        currency: Currency,
        target: String,
        amount: BigDecimal?,
        quick: AccountId?,
        bonus: Boolean? = null,
    ) {
        val id = quick ?: targets.resolve(target)
        if (id == null) {
            replies.send(sender, MessageKey.ERROR_PLAYER_NOT_FOUND, Placeholders.text("target", target))
            return
        }
        // Reset takes its amount from the currency; the node requires one for the other three, so a
        // null here is a caller that bypassed it.
        val moved = if (operation == Operation.RESET) currency.startingBalance else amount
        if (moved == null) {
            replies.send(sender, MessageKey.ERROR_INVALID_AMOUNT)
            return
        }
        when (val result = apply(operation, id, currency, moved, bonus)) {
            is Outcome.Success -> {
                val (balance, credit) = result.value
                mirror.put(id, currency.code, balance.amount)
                credit?.let { mirror.put(id, it.balance.currency.code, it.balance.amount) }
                replies.send(
                    sender,
                    if (credit != null) MessageKey.ADMIN_GIVEN_BONUS else operation.success,
                    Placeholders.of(
                        Placeholders.text("target", target),
                        Placeholders.money(
                            "formatted",
                            if (operation.reportsBalance) balance else Money(moved, currency),
                            format,
                        ),
                        // Supplied whichever <formatted> means, so a translator can use both
                        // (LOCALIZATION.md §3) — `<balance>` is always the balance after the change.
                        Placeholders.money("balance", balance, format),
                        Placeholders.currency(currency, format, moved),
                        credit?.let { Placeholders.money("bonus", it.amount, format) } ?: TagResolver.empty(),
                    ),
                )
            }
            is Outcome.Failure -> replies.sendError(sender, result.error, target)
        }
    }

    private suspend fun apply(
        operation: Operation,
        id: AccountId,
        currency: Currency,
        amount: BigDecimal,
        bonus: Boolean?,
    ): DepositResult = when (operation) {
        Operation.GIVE -> economy.deposit(id, amount, currency.code, bonus = bonus)
        Operation.TAKE -> economy.withdraw(id, amount, currency.code).map { Deposited(it) }
        Operation.SET, Operation.RESET -> economy.set(id, amount, currency.code).map { Deposited(it) }
    }

    companion object {
        /** `give`'s trailing argument, as the word a sender types → whether to grant the bonus. */
        val BONUS_KEYWORDS: Map<String, Boolean> = mapOf("bonus" to true, "nobonus" to false)
    }
}
