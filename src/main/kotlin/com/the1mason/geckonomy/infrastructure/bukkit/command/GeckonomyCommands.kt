package com.the1mason.geckonomy.infrastructure.bukkit.command

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.tree.LiteralCommandNode
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import com.the1mason.geckonomy.infrastructure.bukkit.CurrencyAccess
import com.the1mason.geckonomy.infrastructure.bukkit.CurrencyAccess.Action
import com.the1mason.geckonomy.infrastructure.bukkit.PlayerTargets
import com.the1mason.geckonomy.infrastructure.i18n.MessageKey
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin
import java.math.BigDecimal
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Registers every command with Paper's Brigadier API (`docs/tasks/M7-commands.md`).
 *
 * **The shell, and only the shell.** It parses arguments, checks the *base* permission, resolves the
 * cheap player sources while still on the main thread, and hands over. Every rule worth testing lives
 * in the command classes, which know nothing about Brigadier — the same split M6 used to keep the Vault
 * providers thin over `VaultSyncPath`.
 *
 * Cloud v2 was the reviewed choice here and was reverted: every Cloud command manager reflects into
 * NMS in its constructor and cannot be built under MockBukkit, which M7's acceptance criteria require.
 * Brigadier is part of `paper-api` and needs no dependency at all.
 */
internal class GeckonomyCommands(
    private val plugin: Plugin,
    private val scope: CoroutineScope,
    private val currencies: CurrencyRegistry,
    private val access: CurrencyAccess,
    private val targets: PlayerTargets,
    private val replies: CommandReplies,
    private val balance: BalanceCommand,
    private val pay: PayCommand,
    private val baltop: BaltopCommand,
    private val eco: EcoCommand,
    private val geckonomy: GeckonomyCommand,
    private val logger: Logger,
) {

    /**
     * Must be called from `onEnable`.
     *
     * Paper permits lifecycle registration only while a plugin is enabling —
     * `JavaPlugin.allowsLifecycleRegistration` flips false the moment enable returns — so a later call
     * throws `IllegalStateException: Cannot register lifecycle event handlers`.
     */
    fun register() {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val registrar = event.registrar()
            registrar.register(balanceNode(), "Show a balance", listOf("bal"))
            registrar.register(payNode(), "Pay another player", emptyList())
            registrar.register(baltopNode(), "Show the richest accounts", emptyList())
            registrar.register(ecoNode(), "Administer balances", emptyList())
            registrar.register(geckonomyNode(), "Geckonomy administration", emptyList())
        }
    }

    // ── /balance [player] [currency] ─────────────────────────────────────

    private fun balanceNode(): GeckonomyNode = Commands.literal("balance")
        .requires { it.sender.hasPermission(Action.BALANCE.base) }
        .executes { ctx -> run(ctx, "checking a balance") { sender, currency -> balance.execute(sender, currency, null, null) } }
        .then(
            // One argument for two meanings. `/balance coins` and `/balance Notch` are the same shape
            // to Brigadier — two `word()` children would not disambiguate by content, they would just
            // let whichever was registered first swallow both — so the *handler* decides, by asking
            // whether the word names a currency.
            Commands.argument("target", StringArgumentType.word())
                .suggests(playersAndCurrencies(Action.BALANCE))
                .executes(::balanceOneArgument)
                .then(
                    currencyArgument(Action.BALANCE_OTHERS)
                        .executes { ctx -> balanceOther(ctx, ctx.string("currency")) },
                ),
        )
        .build()

    /** `/balance <word>` — the word is a currency if it names one, and a player otherwise. */
    private fun balanceOneArgument(ctx: CommandContext<CommandSourceStack>): Int {
        val word = ctx.string("target")!!
        val asCurrency = currencies.byCode(CurrencyCode.parseOrNull(word) ?: return balanceOther(ctx, null))
        return if (asCurrency != null) {
            run(ctx, "checking a balance", word) { sender, currency -> balance.execute(sender, currency, null, null) }
        } else {
            balanceOther(ctx, null)
        }
    }

    /**
     * Another player's balance.
     *
     * The base `geckonomy.balance.others` node is checked here rather than with `requires` on the
     * argument, because that argument also carries `/balance <currency>` — hiding the node would hide
     * a player's own balance along with it. A refusal is also the better answer: `requires` makes a
     * command vanish, which reads as a typo rather than as "you may not".
     */
    private fun balanceOther(ctx: CommandContext<CommandSourceStack>, currencyArg: String?): Int {
        val sender = ctx.source.sender
        if (!sender.hasPermission(Action.BALANCE_OTHERS.base)) {
            replies.send(sender, MessageKey.ERROR_NO_PERMISSION)
            return SUCCESS
        }
        val target = ctx.string("target")!!
        val quick = targets.fromServer(target)
        return run(ctx, "checking $target's balance", currencyArg) { s, currency -> balance.execute(s, currency, target, quick) }
    }

    // ── /pay <player> <amount> [currency] ────────────────────────────────

    private fun payNode(): GeckonomyNode = Commands.literal("pay")
        .requires { it.sender.hasPermission(Action.PAY.base) }
        .then(
            Commands.argument("player", StringArgumentType.word())
                .suggests(players())
                .then(
                    Commands.argument("amount", StringArgumentType.word())
                        .executes { ctx -> payTo(ctx, null) }
                        .then(
                            currencyArgument(Action.PAY)
                                .executes { ctx -> payTo(ctx, ctx.string("currency")) },
                        ),
                ),
        )
        .build()

    private fun payTo(ctx: CommandContext<CommandSourceStack>, currencyArg: String?): Int {
        val target = ctx.string("player")!!
        val amount = parseAmount(ctx.string("amount")!!)
        val quick = targets.fromServer(target)
        return run(ctx, "paying $target", currencyArg) { sender, currency ->
            if (amount == null) replies.send(sender, MessageKey.ERROR_INVALID_AMOUNT)
            else pay.execute(sender, currency, target, amount, quick)
        }
    }

    // ── /baltop [currency] ───────────────────────────────────────────────

    private fun baltopNode(): GeckonomyNode = Commands.literal("baltop")
        .requires { it.sender.hasPermission(Action.BALTOP.base) }
        .executes { ctx -> run(ctx, "listing the top balances") { sender, currency -> baltop.execute(sender, currency) } }
        .then(
            currencyArgument(Action.BALTOP)
                .executes { ctx -> run(ctx, "listing the top balances", ctx.string("currency")) { s, c -> baltop.execute(s, c) } },
        )
        .build()

    // ── /eco give|take|set|reset <player> <amount> [currency] ────────────

    private fun ecoNode(): GeckonomyNode {
        val eco = Commands.literal("eco").requires { it.sender.hasPermission(ADMIN) }
        EcoCommand.Operation.entries.forEach { operation ->
            eco.then(
                Commands.literal(operation.label).then(
                    Commands.argument("player", StringArgumentType.word())
                        .suggests(players())
                        // Reset needs no amount: the currency supplies it.
                        .apply { if (operation == EcoCommand.Operation.RESET) executes { ctx -> ecoRun(ctx, operation, null, null) } }
                        .then(
                            if (operation == EcoCommand.Operation.RESET) {
                                adminCurrencyArgument().executes { ctx -> ecoRun(ctx, operation, null, ctx.string("currency")) }
                            } else {
                                Commands.argument("amount", StringArgumentType.word())
                                    .executes { ctx -> ecoRun(ctx, operation, ctx.string("amount"), null) }
                                    .then(
                                        adminCurrencyArgument()
                                            .executes { ctx -> ecoRun(ctx, operation, ctx.string("amount"), ctx.string("currency")) },
                                    )
                            },
                        ),
                ),
            )
        }
        return eco.build()
    }

    private fun ecoRun(
        ctx: CommandContext<CommandSourceStack>,
        operation: EcoCommand.Operation,
        amountArg: String?,
        currencyArg: String?,
    ): Int {
        val sender = ctx.source.sender
        val target = ctx.string("player")!!
        val currency = currencies.resolveArgument(currencyArg)
        if (currency == null) {
            replies.send(sender, MessageKey.ERROR_UNKNOWN_CURRENCY, unknownCurrency(currencyArg!!))
            return SUCCESS
        }
        val amount = amountArg?.let(::parseAmount)
        if (amountArg != null && amount == null) {
            replies.send(sender, MessageKey.ERROR_INVALID_AMOUNT)
            return SUCCESS
        }
        val quick = targets.fromServer(target)
        launchGuarded(sender, "running /eco ${operation.label} on $target") {
            eco.execute(sender, operation, currency, target, amount, quick)
        }
        return SUCCESS
    }

    // ── /geckonomy reload|version ────────────────────────────────────────

    private fun geckonomyNode(): GeckonomyNode = Commands.literal("geckonomy")
        .requires { it.sender.hasPermission(ADMIN) }
        .then(
            Commands.literal("reload").executes { ctx ->
                val sender = ctx.source.sender
                // Off the main thread: both reloads do blocking file IO.
                launchGuarded(sender, "reloading the configuration") { geckonomy.reload(sender) }
                SUCCESS
            },
        )
        .then(
            Commands.literal("version").executes { ctx ->
                geckonomy.version(ctx.source.sender)
                SUCCESS
            },
        )
        .build()

    // ── Plumbing ─────────────────────────────────────────────────────────

    /**
     * Resolves the currency argument and launches [block] off the main thread.
     *
     * An unknown currency is refused here, before a coroutine is started: the command layer needs the
     * [Currency] anyway to ask [CurrencyAccess] about it.
     */
    private fun run(
        ctx: CommandContext<CommandSourceStack>,
        what: String,
        currencyArg: String? = null,
        block: suspend (CommandSender, Currency) -> Unit,
    ): Int {
        val sender = ctx.source.sender
        val currency = currencies.resolveArgument(currencyArg)
        if (currency == null) {
            replies.send(sender, MessageKey.ERROR_UNKNOWN_CURRENCY, unknownCurrency(currencyArg!!))
            return SUCCESS
        }
        launchGuarded(sender, what) { block(sender, currency) }
        return SUCCESS
    }

    /**
     * The only place a command coroutine may start.
     *
     * A use case reports its own failures as an `EconomyError`, so what is left to catch here is a bug
     * above it — a handler with a hole in it, a template that throws while rendering. Uncaught, that
     * throw reaches the scope's `SupervisorJob`, which cancels this one child and tells nobody: the
     * command the player ran simply never answers, and silence is the one failure mode a player cannot
     * report usefully. Answering with the same [EconomyError.StorageFailure] the guarded paths produce
     * keeps one shape for callers, and the SEVERE log is what distinguishes a bug from a sick database
     * (ARCHITECTURE.md §6).
     */
    private fun launchGuarded(sender: CommandSender, what: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                // Rethrown first, for StorageGuard's reason: it is an IllegalStateException, so the
                // arm below would eat the plugin disabling or a player quitting mid-command.
                throw e
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Geckonomy bug while $what", e)
                replies.sendError(sender, EconomyError.StorageFailure(what, e.message))
            }
        }
    }

    /** `[currency]`, completing only what the sender may actually use for [action]. */
    private fun currencyArgument(action: Action) =
        Commands.argument("currency", StringArgumentType.word()).suggests { ctx, builder ->
            access.permitted(ctx.source.sender, action).forEach { builder.suggest(it.code.value) }
            builder.buildFuture()
        }

    /** `[currency]` for `/eco`, which is not gated by per-currency nodes or flags. */
    private fun adminCurrencyArgument() =
        Commands.argument("currency", StringArgumentType.word()).suggests { _, builder ->
            currencies.all().forEach { builder.suggest(it.code.value) }
            builder.buildFuture()
        }

    private fun players() = SuggestionProvider<CommandSourceStack> { _, builder ->
        plugin.server.onlinePlayers.forEach { builder.suggest(it.name) }
        builder.buildFuture()
    }

    /** `/balance <word>`'s completion, offering both things the word can mean. */
    private fun playersAndCurrencies(action: Action) = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        access.permitted(ctx.source.sender, action).forEach { builder.suggest(it.code.value) }
        if (ctx.source.sender.hasPermission(Action.BALANCE_OTHERS.base)) {
            plugin.server.onlinePlayers.forEach { builder.suggest(it.name) }
        }
        builder.buildFuture()
    }

    private fun CommandContext<CommandSourceStack>.string(name: String): String? =
        runCatching { StringArgumentType.getString(this, name) }.getOrNull()

    private companion object {
        const val ADMIN = "geckonomy.admin"

        /**
         * Brigadier's "handled" result. Every path returns it, including refusals: the command *ran*
         * and answered, and a zero would make the client render it as a syntax error.
         */
        const val SUCCESS = 1
    }
}

/** Every node this file builds is one of these; spelling it out at each return adds nothing. */
private typealias GeckonomyNode = LiteralCommandNode<CommandSourceStack>
