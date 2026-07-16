package com.the1mason.geckonomy.infrastructure.config

import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import java.nio.file.Path
import kotlin.io.path.readText

/** The outcome of the first config load, which decides whether the plugin runs at all. */
sealed interface StartOutcome {

    /** The config is usable. [warnings] are worth logging; the plugin may enable. */
    data class Started(val service: ConfigService, val warnings: List<String>) : StartOutcome

    /** The config is unusable. The plugin must disable rather than run misconfigured. */
    data class Failed(val errors: List<String>) : StartOutcome
}

/** The outcome of a `/geckonomy reload`. */
sealed interface ReloadOutcome {

    /** The new config is live. [warnings] include anything that needs a restart to take effect. */
    data class Applied(val warnings: List<String>) : ReloadOutcome

    /** The file was rejected and the previous config is still live, untouched. */
    data class Rejected(val errors: List<String>) : ReloadOutcome
}

/**
 * Owns the config file: reads it, holds the current [GeckonomyConfig], and rebuilds the currency
 * registry on reload (CONFIGURATION.md §4).
 *
 * Only exists once a valid config has been read — [start] returns [StartOutcome.Failed] instead of a
 * half-configured service — so [current] is always a config that passed validation.
 *
 * **Threading.** [reload] does blocking file IO, so M7's `/geckonomy reload` must call it off the
 * main thread (CODING_STANDARDS.md §3). It is plain file reading, not DB work, and needs no
 * `IoDispatcher`.
 *
 * **What reload actually applies.** Currencies, live, through [currencies]. Everything else is
 * updated in [current] for whoever reads it next; collaborators that captured a setting by value at
 * startup (M3's pool, `ScopeResolver`, and the balance repository's `OverdraftPolicy` guard, M4's
 * `RoundingPolicy`) keep what they were built with — hence the warnings, and hence [StorageConfig]
 * not being swapped at all.
 */
class ConfigService private constructor(
    private val file: Path,
    private val loader: ConfigLoader,
    private val registry: ConfigCurrencyRegistry,
    initial: GeckonomyConfig,
) {

    /**
     * The config in force. Replaced wholesale by a successful [reload]; `@Volatile` so a reader on
     * any thread sees one whole config rather than a mix of two.
     */
    @Volatile
    var current: GeckonomyConfig = initial
        private set

    /** The live currency registry. Its contents follow [reload]; the object never changes. */
    val currencies: CurrencyRegistry get() = registry

    /**
     * Re-reads the config file.
     *
     * A rejected file changes nothing: the server keeps running on the config it had, which is the
     * whole point of validating before swapping.
     */
    fun reload(): ReloadOutcome {
        val text = runCatching { file.readText() }
            .getOrElse { return ReloadOutcome.Rejected(listOf(readFailure(file, it))) }
        return when (val load = loader.load(text)) {
            is ConfigLoad.Invalid -> ReloadOutcome.Rejected(load.errors)
            is ConfigLoad.Loaded -> apply(load)
        }
    }

    private fun apply(load: ConfigLoad.Loaded): ReloadOutcome {
        val warnings = load.warnings + restartWarnings(current, load.config)
        // Storage is carried over rather than taken from the file: the pool M3 opened at startup is
        // what the server is actually talking to, and letting `current.storage` disagree with it
        // would be a lie waiting to be acted on. The warning above tells the owner what to do.
        current = load.config.copy(storage = current.storage)
        registry.replaceWith(load.config.currencies)
        return ReloadOutcome.Applied(warnings)
    }

    /** What the new file asks for that only a restart can deliver. */
    private fun restartWarnings(old: GeckonomyConfig, new: GeckonomyConfig): List<String> = buildList {
        if (old.storage != new.storage) {
            // Warning text stays ASCII: it reaches the server console, which is not reliably UTF-8.
            add("storage settings changed; Geckonomy keeps the connection it opened at startup, so restart the server to apply them")
        }
        if (old.settings.allowOverdraft != new.settings.allowOverdraft) {
            // The overdraft rule is compiled into the balance repository's SQL guard at startup (it
            // has to be: the check must be atomic with the update, so it lives in the WHERE clause).
            // Nothing re-reads it, so without this warning the setting would appear to reload and
            // silently do nothing.
            add("settings.allow-overdraft changed; the balance guard is fixed at startup, so restart the server to apply it")
        }
        if (old.settings.serverId != new.settings.serverId) {
            add(
                "settings.server-id changed from '${old.settings.serverId}' to '${new.settings.serverId}'; " +
                    "it takes effect on restart, and per-server balances stored under the old id will not be " +
                    "visible under the new one",
            )
        }
        val removed = old.currencies.map(Currency::code) - new.currencies.map(Currency::code).toSet()
        removed.forEach { code ->
            add("currency '$code' is no longer configured; its stored balances are left untouched, not deleted")
        }
    }

    companion object {

        /** Reads [file] for the first time, or reports why the plugin cannot start. */
        fun start(file: Path, loader: ConfigLoader = ConfigLoader()): StartOutcome {
            val text = runCatching { file.readText() }
                .getOrElse { return StartOutcome.Failed(listOf(readFailure(file, it))) }
            return when (val load = loader.load(text)) {
                is ConfigLoad.Invalid -> StartOutcome.Failed(load.errors)
                is ConfigLoad.Loaded -> StartOutcome.Started(
                    ConfigService(file, loader, ConfigCurrencyRegistry(load.config.currencies), load.config),
                    load.warnings,
                )
            }
        }

        /** Why the file could not be read, in terms a server owner can act on. */
        private fun readFailure(file: Path, cause: Throwable): String =
            "$file could not be read: ${cause.message ?: cause}"
    }
}
