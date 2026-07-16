package com.the1mason.geckonomy

import com.the1mason.geckonomy.infrastructure.config.ConfigService
import com.the1mason.geckonomy.infrastructure.config.StartOutcome
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bukkit.plugin.java.JavaPlugin

/**
 * Composition root — the only class that may know every layer (ARCHITECTURE.md §1).
 *
 * At M2 it wires config and the currency registry; the later milestones fill in the rest in the
 * order [onEnable] documents.
 */
class Geckonomy : JavaPlugin() {

    /**
     * The one scope every coroutine in the plugin runs under, cancelled in [onDisable] so nothing
     * outlives the plugin classloader (CODING_STANDARDS.md §3).
     *
     * A [SupervisorJob] keeps one failed operation from tearing down unrelated ones. The dispatcher
     * is a placeholder: M3 replaces it with the bounded `IoDispatcher` that carries all DB work.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("geckonomy"))

    /**
     * Config and the currency registry, held for the milestones that build on them and for M7's
     * `/geckonomy reload`. Null until a valid config is read — and if none ever is, the plugin
     * disables rather than reaching this state.
     */
    private var config: ConfigService? = null

    override fun onEnable() {
        // Wiring order — ARCHITECTURE.md §7. Each step arrives with its milestone:
        // 1. M2 — load config; build CurrencyRegistry and StorageConfig.                    [done]
        // 2. M3 — DataSourceFactory -> SqlDialect -> repositories + UnitOfWork; run MigrationRunner.
        // 3. M4 — EconomyService from the use cases + ports.
        // 4. M5 — MessageService from the language files.
        // 5. M6 — VaultUnlockedEconomyProvider (v2) and LegacyVaultEconomyProvider (v1) + the online
        //         balance mirror; register both with the ServicesManager.
        // 6. M7 — register commands and listeners.
        config = loadConfig() ?: return
        logger.info("Geckonomy enabled (M2: config loaded; no economy wiring yet).")
    }

    override fun onDisable() {
        // Unwinds in reverse: unregister services, flush, close the pool and dispatcher.
        scope.cancel("Geckonomy is disabling")
        config = null
        logger.info("Geckonomy disabled.")
    }

    /**
     * Reads `config.yml`, or disables the plugin and returns null.
     *
     * Refusing to start beats starting misconfigured (CONFIGURATION.md §3): an economy that comes up
     * with the wrong currencies or points at the wrong database does damage that a clear error at
     * boot does not.
     */
    private fun loadConfig(): ConfigService? {
        saveDefaultConfig()
        return when (val outcome = ConfigService.start(dataFolder.toPath().resolve("config.yml"))) {
            is StartOutcome.Failed -> {
                // ASCII only: the server console writes the log in the platform charset, and a
                // bullet or an em-dash comes out as a replacement character on a Windows console.
                logger.severe("Geckonomy cannot start - config.yml is invalid:")
                outcome.errors.forEach { logger.severe("  - $it") }
                logger.severe("Fix the problems above and restart the server.")
                server.pluginManager.disablePlugin(this)
                null
            }

            is StartOutcome.Started -> {
                outcome.warnings.forEach { logger.warning(it) }
                val service = outcome.service
                val config = service.current
                logger.info(
                    "Loaded ${config.currencies.size} currencies " +
                        "(default: ${service.currencies.default().code}); storage: ${config.storage.type}.",
                )
                service
            }
        }
    }
}
