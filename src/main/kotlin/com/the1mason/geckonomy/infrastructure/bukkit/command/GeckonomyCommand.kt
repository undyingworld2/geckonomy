package com.the1mason.geckonomy.infrastructure.bukkit.command

import com.the1mason.geckonomy.infrastructure.config.ConfigService
import com.the1mason.geckonomy.infrastructure.config.ReloadOutcome
import com.the1mason.geckonomy.infrastructure.i18n.MessageKey
import com.the1mason.geckonomy.infrastructure.i18n.MessageService
import com.the1mason.geckonomy.infrastructure.i18n.Placeholders
import org.bukkit.command.CommandSender
import java.util.logging.Logger

/**
 * `/geckonomy reload|version` (SPEC.md §7).
 *
 * Both halves are `geckonomy.admin`.
 */
internal class GeckonomyCommand(
    private val config: ConfigService,
    private val messages: MessageService,
    private val replies: CommandReplies,
    private val permissions: GeckonomyPermissions,
    private val logger: Logger,
    private val version: String,
) {

    fun version(sender: CommandSender) {
        replies.send(sender, MessageKey.ADMIN_VERSION, Placeholders.text("version", version))
    }

    /**
     * Re-reads `config.yml` and the language files.
     *
     * Suspending, and called off the main thread, because both do blocking file IO
     * (`ConfigService.reload`'s own KDoc says so). A rejected config changes nothing and the server
     * keeps running on what it had — the point of validating before swapping — so the only thing left
     * to do is say which it was.
     *
     * Warnings go to the console, not the player: they are about settings that need a restart
     * (`allow-overdraft`, `server-id`, storage), which is an operator's problem and too long to read
     * in chat.
     */
    suspend fun reload(sender: CommandSender) {
        when (val outcome = config.reload()) {
            is ReloadOutcome.Rejected -> {
                logger.severe("Geckonomy: /geckonomy reload rejected config.yml; the previous configuration is still running:")
                outcome.errors.forEach { logger.severe("  - $it") }
                replies.send(sender, MessageKey.ADMIN_RELOAD_FAILED)
            }

            is ReloadOutcome.Applied -> {
                // Languages follow the config: `settings.language` may have just changed, and
                // MessageService.reload is what applies it.
                messages.reload()
                // Currencies may have been added or removed, and each carries permission nodes that
                // must exist for a non-op to pass them. Re-registered before the reply, so the very
                // next command sees them.
                permissions.register()
                outcome.warnings.forEach { logger.warning("Geckonomy: $it") }
                replies.send(sender, MessageKey.ADMIN_RELOADED)
            }
        }
    }
}
