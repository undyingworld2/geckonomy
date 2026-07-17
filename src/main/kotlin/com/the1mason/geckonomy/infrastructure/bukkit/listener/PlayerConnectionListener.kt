package com.the1mason.geckonomy.infrastructure.bukkit.listener

import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.service.EconomyService
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.infrastructure.vault.OnlineBalanceMirror
import com.the1mason.geckonomy.infrastructure.vault.VaultSyncPath
import kotlinx.coroutines.runBlocking
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerLoginEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Creates a player's account and warms the mirror before they are in the world (SPEC.md FR-A6).
 *
 * The work happens on [AsyncPlayerPreLoginEvent] rather than `PlayerJoinEvent` for two reasons: that
 * event already runs off the main thread, so the database read is free of NFR-1; and it completes
 * before the player exists to anyone else, which closes the race where a plugin asks for a balance
 * that is still loading and gets served by the bounded blocking fallback instead.
 */
internal class PlayerConnectionListener(
    private val economy: EconomyService,
    private val sync: VaultSyncPath,
    private val mirror: OnlineBalanceMirror,
    private val logger: Logger,
) : Listener {

    /**
     * Blocking is correct here — the event is already off the main thread and Bukkit holds the login
     * open for exactly this kind of work.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        val id = AccountId(event.uniqueId)

        runBlocking {
            when (val created = economy.createAccount(id, event.name)) {
                is Outcome.Success -> if (!created.value) renameIfChanged(id, event.name)
                is Outcome.Failure ->
                    // Not a reason to refuse the login: they get in, and the sync path falls back to
                    // reading the database directly until this recovers.
                    logger.warning("Geckonomy: could not prepare an account for ${event.name}: ${created.error}")
            }
            sync.hydrate(id)
        }
    }

    /**
     * A login allowed at pre-login can still be refused here — a full server, a ban applied in
     * between. Without this the hydrated entry would never be evicted, because no quit event follows
     * a player who never joined.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onLogin(event: PlayerLoginEvent) {
        if (event.result != PlayerLoginEvent.Result.ALLOWED) mirror.evict(AccountId(event.player.uniqueId))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        mirror.evict(AccountId(event.player.uniqueId))
    }

    private suspend fun renameIfChanged(id: AccountId, name: String) {
        val current = economy.name(id)
        if (current is Outcome.Success && current.value != name) {
            economy.rename(id, name).let {
                if (it is Outcome.Failure) logger.log(Level.WARNING, "Geckonomy: could not rename ${id.value} to $name: ${it.error}")
            }
        }
    }
}
