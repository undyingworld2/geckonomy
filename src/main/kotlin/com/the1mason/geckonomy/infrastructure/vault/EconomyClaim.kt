package com.the1mason.geckonomy.infrastructure.vault

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.ServiceRegisterEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredServiceProvider
import java.util.logging.Logger
import net.milkbowl.vault.economy.Economy as LegacyVaultEconomy
import net.milkbowl.vault2.economy.Economy as VaultUnlockedEconomy

/**
 * Keeps Geckonomy the **sole** economy provider Vault answers with (`settings.claim-vault-economy`).
 *
 * Registering at `Highest` wins the primary-provider lookup, but only against providers already
 * present — and only cleanly when nothing ties us at that priority. A plugin that enables *after*
 * Geckonomy, or registers at `Highest` itself and got there first, is still in the `ServicesManager`
 * and can be handed to a third party. **EssentialsX is the case this exists for:** its economy does
 * not defer to another and has no setting to make it, so the only place to settle it is here.
 *
 * Two halves, because a competitor can arrive on either side of our own registration:
 * - [sweep] removes any that are already there when we register.
 * - the [ServiceRegisterEvent] handler removes any that register afterward.
 *
 * Aggressive by design, and gated by a supplier read **per event** so `/geckonomy reload` can switch
 * it off live. It never touches Geckonomy's own providers, nor Vault's — only a *third* economy
 * plugin. Every removal is logged, because silently disabling another plugin's integration is exactly
 * the kind of thing an admin must be able to find in the console.
 *
 * Names Vault types, so like [VaultRegistration] it is loaded only once the composition root has
 * confirmed Vault is present.
 */
internal class EconomyClaim(
    private val plugin: Plugin,
    private val claim: () -> Boolean,
    private val logger: Logger,
) : Listener {

    fun sweep() {
        if (!claim()) return
        ECONOMY_SERVICES.forEach { service -> suppressExisting(service) }
    }

    fun listen() {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun stop() {
        ServiceRegisterEvent.getHandlerList().unregister(this)
    }

    private fun <T> suppressExisting(service: Class<T>) {
        val services = plugin.server.servicesManager
        // A copy, because unregister mutates the registration list we are iterating.
        services.getRegistrations(service).toList()
            .filter { it.isCompetitor() }
            .forEach { registration ->
                services.unregister(service, registration.provider)
                logSuppression(service, registration)
            }
    }

    /**
     * Monitor priority: let every other listener see the registration first and do nothing observable
     * to them, then remove it. We are the last word, not a participant.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onServiceRegister(event: ServiceRegisterEvent) = suppressIfCompetitor(event.provider)

    /** The listener's body, minus the event — so it is testable without constructing a Bukkit event. */
    internal fun suppressIfCompetitor(registration: RegisteredServiceProvider<*>) {
        if (!claim()) return
        if (registration.service !in ECONOMY_SERVICES || !registration.isCompetitor()) return
        plugin.server.servicesManager.unregister(registration.service, registration.provider)
        logSuppression(registration.service, registration)
    }

    /**
     * Neither ours nor Vault's own.
     *
     * Ours is obvious. Vault's is excluded because VaultUnlocked (plugin name `Vault`) may register
     * bridge providers of its own; ours already outranks them at `Highest`, so removing them would be
     * a fight for no gain — the point is to stop a *third* economy, not to police the broker.
     */
    private fun RegisteredServiceProvider<*>.isCompetitor(): Boolean =
        plugin !== this@EconomyClaim.plugin && plugin.name != BROKER

    private fun logSuppression(service: Class<*>, registration: RegisteredServiceProvider<*>) {
        logger.info(
            "Geckonomy claimed the economy: unregistered ${registration.plugin.name}'s " +
                "${service.simpleName} provider so it cannot be handed to other plugins. " +
                "Set settings.claim-vault-economy to false to allow it.",
        )
    }

    private companion object {
        /** Both APIs Geckonomy provides; a competitor for either is one to suppress. */
        val ECONOMY_SERVICES: Set<Class<*>> = setOf(
            LegacyVaultEconomy::class.java,
            VaultUnlockedEconomy::class.java,
        )

        /** VaultUnlocked's plugin name — the broker, not a competing economy. See [isCompetitor]. */
        const val BROKER = "Vault"
    }
}
