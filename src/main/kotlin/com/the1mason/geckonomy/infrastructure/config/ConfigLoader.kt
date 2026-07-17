package com.the1mason.geckonomy.infrastructure.config

import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.CurrencyScope
import com.the1mason.geckonomy.infrastructure.placeholder.PlaceholderVariant
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** The value `settings.server-id` ships with; a server that never changed it (CONFIGURATION.md §2). */
internal const val DEFAULT_SERVER_ID = "default"

/** SQLite's database file when `storage.file` is left out. */
internal const val DEFAULT_SQLITE_FILE = "plugins/Geckonomy/data.db"

/** Display template a currency falls back to (LOCALIZATION.md). */
internal const val DEFAULT_FORMAT = "<symbol><amount>"

private const val DEFAULT_MARIADB_PORT = 3306

/**
 * Ceiling on a currency's `fractional-digits`.
 *
 * Money is stored at a fixed scale of 4 (DATA_MODEL.md §3), so a fifth decimal place has nowhere to
 * go: the currency would look fine at startup and silently truncate on the first write. Rejecting it
 * here turns a data-loss bug into a config error.
 *
 * Four rather than more because SQLite stores money as an INTEGER count of minor units, and every
 * digit of scale costs a factor of ten off the largest storable balance. At scale 4 the ceiling is
 * ~922 trillion, which no economy reaches; at scale 10 it would be ~922 million, which one plausibly
 * does. Two digits covers the conventional currency, so the two spare digits are headroom rather than
 * a constraint anyone should feel.
 */
internal const val MAX_FRACTIONAL_DIGITS = 4

private val PORT_RANGE = 1..65_535
private val POOL_SIZE_RANGE = 1..64
private val IDLE_RANGE = 0..64

/** Hikari itself refuses a connection timeout under 250 ms, so accepting one would only mislead. */
private val CONNECTION_TIMEOUT_RANGE = 250..600_000

/** The outcome of reading a config file. */
sealed interface ConfigLoad {

    /** The file is usable. [warnings] are worth logging but did not stop it loading. */
    data class Loaded(val config: GeckonomyConfig, val warnings: List<String>) : ConfigLoad

    /**
     * The file cannot be used. [errors] holds *every* problem found, not just the first, so one
     * round of edits can fix them all.
     */
    data class Invalid(val errors: List<String>) : ConfigLoad
}

/**
 * Reads `config.yml` into validated typed objects (CONFIGURATION.md).
 *
 * Stateless and free of IO: it takes the file's text, so it can be tested with plain JUnit — no
 * server, no temp files. [ConfigService] owns the reading and the resulting state.
 *
 * Parsing is Bukkit's [YamlConfiguration] rather than a bundled YAML library: the server already
 * provides it, so it costs no runtime dependency (CODING_STANDARDS.md §8), and [loadFromString]
 * touches no Bukkit statics.
 */
class ConfigLoader {

    /** Reads [text] as `config.yml`, validating it (CONFIGURATION.md §3). */
    fun load(text: String): ConfigLoad {
        val yaml = YamlConfiguration()
        try {
            yaml.loadFromString(text)
        } catch (e: InvalidConfigurationException) {
            return ConfigLoad.Invalid(listOf("config.yml is not valid YAML: ${firstLineOf(e)}"))
        }

        val problems = ConfigProblems()
        val root = ConfigNode(yaml, "", problems)
        // Settings first: a currency's starting balance is rounded with the configured mode, and
        // whether a negative one is even legal depends on allow-overdraft.
        val settings = readSettings(root.child("settings"))
        val storage = readStorage(root.child("storage"))
        val currencies = readCurrencies(root, settings)
        val placeholders = readPlaceholders(root.child("placeholders"))
        warnOnDefaultServerId(settings, currencies, problems)
        warnOnPlaceholderShadowing(currencies, problems)

        val config = GeckonomyConfig(storage, currencies, settings, placeholders)
        return if (problems.errors.isEmpty()) {
            ConfigLoad.Loaded(config, problems.warnings)
        } else {
            ConfigLoad.Invalid(problems.errors)
        }
    }

    private fun readSettings(node: ConfigNode): SettingsConfig = SettingsConfig(
        serverId = node.string("server-id", DEFAULT_SERVER_ID),
        language = node.string("language", "en"),
        allowOverdraft = node.bool("allow-overdraft", false),
        roundingMode = node.enum("rounding-mode", RoundingMode.HALF_UP, RoundingMode.entries.toTypedArray()) { it.name },
        keepTransactionHistory = node.bool("keep-transaction-history", true),
        baltopSize = node.int("baltop-size", 10, 1..1_000),
        claimVaultEconomy = node.bool("claim-vault-economy", true),
    )

    /**
     * Reads the `storage` section.
     *
     * Only the fields belonging to the configured [StorageType] are required; the other backend's are
     * carried through if present and left null if not, since a file that keeps its MariaDB
     * credentials while running on SQLite is a server owner mid-migration, not a mistake.
     */
    private fun readStorage(node: ConfigNode): StorageConfig {
        val type = node.enum("type", StorageType.SQLITE, StorageType.entries.toTypedArray())
        val sqlite = type == StorageType.SQLITE
        val mariadb = type == StorageType.MARIADB
        return StorageConfig(
            type = type,
            file = when {
                sqlite -> Path.of(node.string("file", DEFAULT_SQLITE_FILE))
                else -> node.stringOrNull("file")?.let(Path::of)
            },
            host = if (mariadb) node.requiredString("host") else node.stringOrNull("host"),
            port = if (mariadb) node.int("port", DEFAULT_MARIADB_PORT, PORT_RANGE) else node.intOrNull("port", PORT_RANGE),
            database = if (mariadb) node.requiredString("database") else node.stringOrNull("database"),
            username = if (mariadb) node.requiredString("username") else node.stringOrNull("username"),
            // Not required: an empty password is a legitimate local setup, and demanding one here
            // would only teach owners to write a placeholder.
            password = if (mariadb) node.string("password", "") else node.stringOrNull("password"),
            properties = node.stringMap("properties"),
            pool = readPool(node.child("pool")),
        )
    }

    private fun readPool(node: ConfigNode): PoolConfig {
        val maximumPoolSize = node.int("maximum-pool-size", 10, POOL_SIZE_RANGE)
        val minimumIdle = node.int("minimum-idle", 2, IDLE_RANGE)
        if (minimumIdle > maximumPoolSize) {
            node.error("minimum-idle", "must not exceed maximum-pool-size ($maximumPoolSize), got $minimumIdle")
        }
        return PoolConfig(
            maximumPoolSize = maximumPoolSize,
            minimumIdle = minimumIdle,
            connectionTimeoutMs = node.int("connection-timeout-ms", 10_000, CONNECTION_TIMEOUT_RANGE),
        )
    }

    private fun readCurrencies(root: ConfigNode, settings: SettingsConfig): List<Currency> {
        val entries = root.list("currencies")
        if (entries.isEmpty()) {
            root.error("currencies", "at least one currency must be configured")
            return emptyList()
        }

        val currencies = entries.mapIndexedNotNull { index, entry ->
            val path = "currencies[$index]"
            if (entry !is Map<*, *>) {
                root.problems.error(path, "expected a currency entry, got '$entry'")
                null
            } else {
                readCurrency(ConfigNode(entry.asSection(), path, root.problems), settings)
            }
        }

        // Only meaningful once every entry has a code to compare: a dropped entry would make an
        // otherwise-fine list look like it has no default, burying the real error under a made-up one.
        if (currencies.size == entries.size) checkCurrencySet(currencies, root.problems)
        return currencies
    }

    /** One currency entry, or null if its code is unusable — which leaves nothing to identify it by. */
    private fun readCurrency(node: ConfigNode, settings: SettingsConfig): Currency? {
        val rawCode = node.requiredString("code")
        val code = CurrencyCode.parseOrNull(rawCode)
        if (code == null) {
            // A blank code already reported itself; saying it twice helps nobody.
            if (rawCode.isNotBlank()) {
                node.error("code", "'$rawCode' is not a valid currency code (expected one or more of [a-z0-9_-])")
            }
            return null
        }

        val fractionalDigits = node.requiredInt("fractional-digits", 0..MAX_FRACTIONAL_DIGITS)
        return Currency(
            code = code,
            singular = node.requiredString("singular"),
            plural = node.requiredString("plural"),
            symbol = node.requiredString("symbol"),
            fractionalDigits = fractionalDigits,
            startingBalance = readStartingBalance(node, fractionalDigits, settings),
            isDefault = node.requiredBool("default"),
            scope = node.enum("scope", CurrencyScope.SERVER, CurrencyScope.entries.toTypedArray()),
            transferable = node.bool("transferable", true),
            checkableOthers = node.bool("balance-check-others", true),
            showInBaltop = node.bool("show-in-baltop", true),
            format = node.string("format", DEFAULT_FORMAT),
        )
    }

    /**
     * The seed balance for new accounts, rounded to what the currency can actually hold.
     *
     * Rounding here rather than at account creation means the registry never carries an amount the
     * currency cannot represent, so no later code has to wonder whether it was rounded yet.
     */
    private fun readStartingBalance(node: ConfigNode, fractionalDigits: Int, settings: SettingsConfig): BigDecimal {
        val configured = node.decimal("starting-balance", BigDecimal.ZERO)
        if (configured.signum() < 0 && !settings.allowOverdraft) {
            node.error(
                "starting-balance",
                "must not be negative while settings.allow-overdraft is false, got $configured",
            )
        }
        val rounded = configured.setScale(fractionalDigits, settings.roundingMode)
        if (rounded.compareTo(configured) != 0) {
            node.warn(
                "starting-balance",
                "$configured is finer than this currency's $fractionalDigits fractional digits; using $rounded",
            )
        }
        return rounded
    }

    private fun checkCurrencySet(currencies: List<Currency>, problems: ConfigProblems) {
        currencies.groupBy(Currency::code)
            .filterValues { it.size > 1 }
            .keys
            .forEach { code ->
                problems.error("currencies", "duplicate currency code '$code' (codes are case-insensitive)")
            }

        val defaults = currencies.filter(Currency::isDefault).map { it.code.value }
        when (defaults.size) {
            1 -> Unit
            0 -> problems.error("currencies", "exactly one currency must have 'default: true'; none does")
            else -> problems.error(
                "currencies",
                "exactly one currency must have 'default: true'; ${defaults.joinToString()} all do",
            )
        }
    }

    /**
     * A network currency means this database is meant to be shared, and a shared database with the
     * shipped server id means the next server to join collides with this one's per-server balances.
     * A warning, not an error: a single server with a network currency is perfectly valid, and it is
     * the common case for someone trying the plugin out.
     */
    private fun warnOnDefaultServerId(
        settings: SettingsConfig,
        currencies: List<Currency>,
        problems: ConfigProblems,
    ) {
        if (settings.serverId == DEFAULT_SERVER_ID && currencies.any { it.scope == CurrencyScope.NETWORK }) {
            problems.warn(
                "settings.server-id",
                "is still '$DEFAULT_SERVER_ID' while network currencies are configured; give each server " +
                    "sharing this database a unique id before starting a second one",
            )
        }
    }

    private fun readPlaceholders(node: ConfigNode): PlaceholderConfig = PlaceholderConfig(
        // Floored at 5s, not 1: a tight loop here is one query per currency against the database
        // forever, and nobody watching a leaderboard needs it fresher than that.
        baltopRefresh = node.int("baltop-refresh-seconds", 60, 5..86_400).seconds,
        offlineCacheTtl = node.int("offline-cache-seconds", 60, 5..86_400).seconds,
        fallback = node.string("fallback", "0"),
    )

    /**
     * A currency whose code is a placeholder keyword is shadowed — a warning, never an error.
     *
     * The currency works perfectly everywhere else, the shadowed placeholder is still reachable
     * through its explicit spelling (`%geckonomy_balance_raw_formatted%`), and refusing to start a
     * server over a placeholder spelling would be wildly out of proportion to the harm.
     */
    private fun warnOnPlaceholderShadowing(currencies: List<Currency>, problems: ConfigProblems) {
        for (currency in currencies.filter { it.code.value in PlaceholderVariant.KEYWORDS }) {
            problems.warn(
                "currencies.${currency.code.value}.code",
                "collides with the placeholder keyword '${currency.code.value}', so " +
                    "%geckonomy_balance_${currency.code.value}% reads as the keyword rather than this " +
                    "currency; reach it with %geckonomy_balance_raw_${currency.code.value}%",
            )
        }
    }

    private fun firstLineOf(e: InvalidConfigurationException): String =
        e.message?.lineSequence()?.firstOrNull()?.trim() ?: e.toString()
}
