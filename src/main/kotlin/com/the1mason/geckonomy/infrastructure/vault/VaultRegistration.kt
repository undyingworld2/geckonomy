package com.the1mason.geckonomy.infrastructure.vault

import com.the1mason.geckonomy.application.service.EconomyService
import com.the1mason.geckonomy.application.usecase.FormatMoney
import com.the1mason.geckonomy.domain.policy.RoundingPolicy
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import com.the1mason.geckonomy.infrastructure.i18n.MessageService
import kotlinx.coroutines.CoroutineScope
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import java.util.logging.Logger
import net.milkbowl.vault.economy.Economy as LegacyVaultEconomy
import net.milkbowl.vault2.economy.Economy as VaultUnlockedEconomy

/**
 * Builds both providers and puts them in the `ServicesManager` (VAULT_INTEGRATION.md §1).
 *
 * **The only class in the plugin that names a Vault type at wiring time.** VaultUnlocked is a soft
 * dependency, so its classes may not exist at runtime; touching one when it is absent is a
 * `NoClassDefFoundError` during enable. Keeping every reference behind this class means the
 * composition root can check for the plugin first and simply never load this if the answer is no.
 */
internal class VaultRegistration(
    private val plugin: Plugin,
    economy: EconomyService,
    currencies: CurrencyRegistry,
    sync: VaultSyncPath,
    messages: MessageService,
    format: FormatMoney,
    rounding: () -> RoundingPolicy,
    claimEconomy: () -> Boolean,
    scope: CoroutineScope,
    logger: Logger,
) : AutoCloseable {

    private val responses = ResponseMapper(messages, format)

    private val claim = EconomyClaim(plugin, claimEconomy, logger)

    private val v2 = VaultUnlockedEconomyProvider(
        enabled = plugin::isEnabled,
        economy = economy,
        currencies = currencies,
        sync = sync,
        responses = responses,
        asyncEconomy = GeckonomyAsyncEconomy(economy, currencies, scope, responses, logger),
        logger = logger,
    )

    private val v1 = LegacyVaultEconomyProvider(
        enabled = plugin::isEnabled,
        economy = economy,
        currencies = currencies,
        sync = sync,
        responses = LegacyResponseMapper(responses),
        players = PlayerResolver(plugin.server, sync),
        rounding = rounding,
    )

    /** Both, at [ServicePriority.Highest]: Geckonomy owns the economy, it does not share it. */
    fun register() {
        val services = plugin.server.servicesManager
        // Sweep first, so ours register into a clean slot; then listen, so a competitor enabling
        // later is caught too. Our own two registrations below are never suppressed ([EconomyClaim]
        // excludes them), so the order between them and [listen] does not matter.
        claim.sweep()
        services.register(VaultUnlockedEconomy::class.java, v2, plugin, ServicePriority.Highest)
        services.register(LegacyVaultEconomy::class.java, v1, plugin, ServicePriority.Highest)
        claim.listen()
    }

    override fun close() {
        claim.stop()
        val services = plugin.server.servicesManager
        services.unregister(VaultUnlockedEconomy::class.java, v2)
        services.unregister(LegacyVaultEconomy::class.java, v1)
    }
}
