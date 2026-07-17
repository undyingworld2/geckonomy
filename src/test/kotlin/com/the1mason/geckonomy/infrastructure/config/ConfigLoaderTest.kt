package com.the1mason.geckonomy.infrastructure.config

import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.CurrencyScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path

/**
 * The loader's contract is the file a server owner types by hand, so these tests work in whole YAML
 * documents rather than stubbed sections: a rule that only holds against a hand-built
 * `ConfigurationSection` would not protect anyone.
 *
 * [VALID] is the reference document; each rejection test spoils exactly one line of it, which keeps
 * "this is what breaks it" visible in the test itself.
 */
class ConfigLoaderTest {

    private val loader = ConfigLoader()

    // ── Happy path ──────────────────────────────────────────────────────

    @Test
    fun `reads the currencies in the order the file declares them`() {
        val currencies = loaded(VALID).config.currencies

        assertEquals(listOf("coins", "gems"), currencies.map { it.code.value })
    }

    @Test
    fun `reads a currency's every field`() {
        val coins = loaded(VALID).config.currencies.first()

        assertEquals(CurrencyCode("coins"), coins.code)
        assertEquals("Coin", coins.singular)
        assertEquals("Coins", coins.plural)
        assertEquals("$", coins.symbol)
        assertEquals(2, coins.fractionalDigits)
        assertEquals(BigDecimal("100.00"), coins.startingBalance)
        assertTrue(coins.isDefault)
        assertEquals(CurrencyScope.NETWORK, coins.scope)
        assertTrue(coins.transferable)
        assertTrue(coins.checkableOthers)
        assertTrue(coins.showInBaltop)
        assertEquals("<symbol><amount>", coins.format)
    }

    @Test
    fun `reads the per-currency flags that hard-gate commands`() {
        val gems = loaded(VALID).config.currencies[1]

        assertFalse(gems.isDefault)
        assertEquals(CurrencyScope.SERVER, gems.scope)
        assertFalse(gems.transferable)
        assertFalse(gems.checkableOthers)
        assertTrue(gems.showInBaltop)
    }

    @Test
    fun `reads sqlite storage`() {
        val storage = loaded(VALID).config.storage

        assertEquals(StorageType.SQLITE, storage.type)
        assertEquals(Path.of("plugins/Geckonomy/data.db"), storage.file)
        assertEquals(PoolConfig(maximumPoolSize = 10, minimumIdle = 2, connectionTimeoutMs = 10_000), storage.pool)
    }

    @Test
    fun `reads mariadb storage, its properties, and the default port`() {
        val storage = loaded(VALID.replace(SQLITE_STORAGE, MARIADB_STORAGE)).config.storage

        assertEquals(StorageType.MARIADB, storage.type)
        assertEquals("db.example.com", storage.host)
        assertEquals(3306, storage.port)
        assertEquals("geckonomy", storage.database)
        assertEquals("gecko", storage.username)
        assertEquals("hunter2", storage.password)
        assertEquals(mapOf("useSSL" to "false"), storage.properties)
    }

    @Test
    fun `reads settings`() {
        val settings = loaded(VALID).config.settings

        assertEquals("survival", settings.serverId)
        assertEquals("en", settings.language)
        assertFalse(settings.allowOverdraft)
        assertEquals(RoundingMode.HALF_UP, settings.roundingMode)
        assertTrue(settings.keepTransactionHistory)
        assertEquals(10, settings.baltopSize)
        assertTrue(settings.claimVaultEconomy)
    }

    @Test
    fun `claim-vault-economy parses false`() {
        val settings = loaded(VALID.replace("baltop-size: 10", "baltop-size: 10\n  claim-vault-economy: false"))
            .config.settings
        assertFalse(settings.claimVaultEconomy)
    }

    @Test
    fun `a config that says nothing about the optional keys falls back to the documented defaults`() {
        val config = loaded(MINIMAL).config

        assertEquals(StorageType.SQLITE, config.storage.type)
        assertEquals(Path.of(DEFAULT_SQLITE_FILE), config.storage.file)
        assertEquals(emptyMap<String, String>(), config.storage.properties)
        assertEquals(SettingsConfig("default", "en", false, RoundingMode.HALF_UP, true, 10, true), config.settings)
        with(config.currencies.single()) {
            assertEquals(BigDecimal("0.00"), startingBalance)
            assertEquals(CurrencyScope.SERVER, scope)
            assertTrue(transferable)
            assertTrue(checkableOthers)
            assertTrue(showInBaltop)
            assertEquals(DEFAULT_FORMAT, format)
        }
    }

    @Test
    fun `the unused backend's fields are left null rather than invented`() {
        val storage = loaded(MINIMAL).config.storage

        assertNull(storage.host)
        assertNull(storage.port)
        assertNull(storage.database)
        assertNull(storage.username)
        assertNull(storage.password)
    }

    @Test
    fun `a mariadb config keeps the sqlite file it is migrating away from`() {
        val storage = loaded(VALID.replace(SQLITE_STORAGE, MARIADB_STORAGE)).config.storage

        assertEquals(Path.of("plugins/Geckonomy/data.db"), storage.file)
    }

    @Test
    fun `the reference config loads without a word of complaint`() {
        assertEquals(emptyList<String>(), loaded(VALID).warnings)
    }

    // ── Money precision ─────────────────────────────────────────────────

    @Test
    fun `a starting balance keeps the value the owner wrote, not a double's idea of it`() {
        // 0.1 has no exact double; reading it as one and converting would seed accounts with
        // 0.1000000000000000055511151231257827.
        val coins = loaded(VALID.replace("starting-balance: 100.00", "starting-balance: 0.1")).config.currencies.first()

        assertEquals(BigDecimal("0.10"), coins.startingBalance)
    }

    @Test
    fun `a starting balance is scaled to the currency's digits, so nothing downstream must re-round it`() {
        val coins = loaded(VALID.replace("starting-balance: 100.00", "starting-balance: 5")).config.currencies.first()

        assertEquals(BigDecimal("5.00"), coins.startingBalance)
    }

    @Test
    fun `a starting balance finer than the currency is rounded, with a warning naming both values`() {
        val load = loaded(VALID.replace("starting-balance: 0", "starting-balance: 4.6"))

        assertEquals(BigDecimal("5"), load.config.currencies[1].startingBalance)
        assertTrue(
            load.warnings.any { "currencies[1].starting-balance" in it && "4.6" in it && "using 5" in it },
            "expected a rounding warning, got ${load.warnings}",
        )
    }

    @Test
    fun `the configured rounding mode decides how a starting balance is rounded`() {
        val load = loaded(
            VALID.replace("starting-balance: 0", "starting-balance: 4.6")
                .replace("rounding-mode: HALF_UP", "rounding-mode: FLOOR"),
        )

        assertEquals(BigDecimal("4"), load.config.currencies[1].startingBalance)
    }

    // ── Validation: storage ─────────────────────────────────────────────

    @Test
    fun `rejects a file that is not YAML at all`() {
        assertRejects("storage: [unclosed", "not valid YAML")
    }

    @Test
    fun `rejects an unknown storage type`() {
        assertRejects(VALID.replace("type: sqlite", "type: postgres"), "storage.type", "sqlite | mariadb", "postgres")
    }

    @Test
    fun `rejects mariadb without the fields mariadb needs`() {
        val errors = errors(
            VALID.replace(SQLITE_STORAGE, "storage:\n  type: mariadb\n"),
        )

        assertTrue(errors.any { it.startsWith("storage.host:") }, errors.toString())
        assertTrue(errors.any { it.startsWith("storage.database:") }, errors.toString())
        assertTrue(errors.any { it.startsWith("storage.username:") }, errors.toString())
    }

    @Test
    fun `rejects a port outside the range a port can be`() {
        assertRejects(
            VALID.replace(SQLITE_STORAGE, MARIADB_STORAGE).replace("port: 3306", "port: 70000"),
            "storage.port",
            "between 1 and 65535",
        )
    }

    @Test
    fun `rejects a pool that keeps more connections idle than it may open`() {
        assertRejects(
            VALID.replace("minimum-idle: 2", "minimum-idle: 20"),
            "storage.pool.minimum-idle",
            "maximum-pool-size (10)",
        )
    }

    @Test
    fun `rejects a connection timeout Hikari would refuse anyway`() {
        assertRejects(VALID.replace("connection-timeout-ms: 10000", "connection-timeout-ms: 10"), "between 250")
    }

    @Test
    fun `rejects a number where a whole number belongs, rather than truncating it`() {
        assertRejects(VALID.replace("baltop-size: 10", "baltop-size: 3.5"), "settings.baltop-size", "whole number")
    }

    // ── Validation: currencies ──────────────────────────────────────────

    @Test
    fun `rejects a config with no currencies section`() {
        assertRejects(MINIMAL.replace(MINIMAL_CURRENCY, ""), "currencies", "at least one")
    }

    @Test
    fun `rejects an empty currencies list`() {
        assertRejects(MINIMAL.replace(MINIMAL_CURRENCY, "currencies: []\n"), "currencies", "at least one")
    }

    @Test
    fun `rejects a malformed currency code`() {
        assertRejects(VALID.replace("code: \"gems\"", "code: \"Gems!\""), "currencies[1].code", "Gems!", "[a-z0-9_-]")
    }

    @Test
    fun `rejects duplicate codes, including ones that differ only in case`() {
        assertRejects(VALID.replace("code: \"gems\"", "code: \"COINS\""), "duplicate currency code 'coins'")
    }

    @Test
    fun `rejects a currency missing a display name`() {
        assertRejects(VALID.replace("singular: \"Gem\"", "singular: \"\""), "currencies[1].singular", "blank")
    }

    @Test
    fun `rejects negative fractional digits`() {
        assertRejects(VALID.replace("fractional-digits: 0", "fractional-digits: -1"), "currencies[1].fractional-digits")
    }

    @Test
    fun `rejects more fractional digits than storage can hold`() {
        // Money is stored at a fixed scale of 4 — a fifth decimal would be truncated on write,
        // silently. See DATA_MODEL.md §3 for why the scale is 4 and not more.
        assertRejects(
            VALID.replace("fractional-digits: 2", "fractional-digits: 5"),
            "currencies[0].fractional-digits",
            "between 0 and 4",
        )
    }

    @Test
    fun `accepts fractional digits at the storage scale`() {
        val currencies = loaded(VALID.replace("fractional-digits: 2", "fractional-digits: 4")).config.currencies

        assertEquals(4, currencies.first().fractionalDigits)
    }

    @Test
    fun `rejects a currency set with no default`() {
        assertRejects(VALID.replace("default: true", "default: false"), "none does")
    }

    @Test
    fun `rejects a currency set with two defaults, naming both`() {
        assertRejects(VALID.replace("default: false", "default: true"), "coins, gems all do")
    }

    @Test
    fun `rejects a currency that does not say whether it is the default`() {
        assertRejects(VALID.replace("    default: false\n", ""), "currencies[1].default", "required")
    }

    @Test
    fun `rejects an unknown scope`() {
        assertRejects(VALID.replace("scope: server", "scope: world"), "currencies[1].scope", "network | server")
    }

    @Test
    fun `rejects a flag that is not a boolean`() {
        assertRejects(VALID.replace("transferable: false", "transferable: maybe"), "currencies[1].transferable", "true or false")
    }

    @Test
    fun `rejects a negative starting balance while overdraft is off`() {
        assertRejects(
            VALID.replace("starting-balance: 100.00", "starting-balance: -5.00"),
            "currencies[0].starting-balance",
            "allow-overdraft",
        )
    }

    @Test
    fun `allows a negative starting balance once overdraft is on`() {
        val load = loaded(
            VALID.replace("starting-balance: 100.00", "starting-balance: -5.00")
                .replace("allow-overdraft: false", "allow-overdraft: true"),
        )

        assertEquals(BigDecimal("-5.00"), load.config.currencies.first().startingBalance)
    }

    @Test
    fun `rejects a currencies entry that is not a currency`() {
        assertRejects(MINIMAL.replace(MINIMAL_CURRENCY, "currencies:\n  - \"coins\"\n"), "currencies[0]", "coins")
    }

    // ── Validation: settings ────────────────────────────────────────────

    @Test
    fun `rejects a blank server id`() {
        assertRejects(VALID.replace("server-id: \"survival\"", "server-id: \"\""), "settings.server-id", "blank")
    }

    @Test
    fun `rejects a rounding mode that is not one`() {
        assertRejects(VALID.replace("rounding-mode: HALF_UP", "rounding-mode: NEAREST"), "settings.rounding-mode", "HALF_UP")
    }

    @Test
    fun `rejects a baltop size of nothing`() {
        assertRejects(VALID.replace("baltop-size: 10", "baltop-size: 0"), "settings.baltop-size")
    }

    @Test
    fun `warns, but still loads, when a shipped server id meets a network currency`() {
        val load = loaded(VALID.replace("server-id: \"survival\"", "server-id: \"default\""))

        assertTrue(
            load.warnings.any { "settings.server-id" in it && "unique id" in it },
            "expected a server-id warning, got ${load.warnings}",
        )
    }

    @Test
    fun `stays quiet about the shipped server id when no currency is shared`() {
        val load = loaded(
            VALID.replace("server-id: \"survival\"", "server-id: \"default\"")
                .replace("scope: network", "scope: server"),
        )

        assertEquals(emptyList<String>(), load.warnings)
    }

    // ── Reporting ───────────────────────────────────────────────────────

    @Test
    fun `reports every problem at once, so one round of edits can fix them all`() {
        val errors = errors(
            VALID.replace("type: sqlite", "type: postgres")
                .replace("rounding-mode: HALF_UP", "rounding-mode: NEAREST")
                .replace("default: true", "default: false")
                .replace("singular: \"Gem\"", "singular: \"\""),
        )

        assertEquals(4, errors.size, "expected one error per mistake, got $errors")
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun loaded(text: String): ConfigLoad.Loaded = when (val load = loader.load(text)) {
        is ConfigLoad.Loaded -> load
        is ConfigLoad.Invalid -> fail("expected a valid config, got errors: ${load.errors}")
    }

    private fun errors(text: String): List<String> = when (val load = loader.load(text)) {
        is ConfigLoad.Invalid -> load.errors
        is ConfigLoad.Loaded -> fail("expected the config to be rejected, but it loaded")
    }

    /** Asserts [text] is rejected by an error mentioning every one of [fragments]. */
    private fun assertRejects(text: String, vararg fragments: String) {
        val errors = errors(text)

        assertTrue(
            errors.any { error -> fragments.all { it in error } },
            "expected an error mentioning ${fragments.toList()}, got $errors",
        )
    }

    private companion object {

        val SQLITE_STORAGE = """
            storage:
              type: sqlite
              file: "plugins/Geckonomy/data.db"
              pool:
                maximum-pool-size: 10
                minimum-idle: 2
                connection-timeout-ms: 10000
        """.trimIndent() + "\n"

        val MARIADB_STORAGE = """
            storage:
              type: mariadb
              file: "plugins/Geckonomy/data.db"
              host: "db.example.com"
              port: 3306
              database: "geckonomy"
              username: "gecko"
              password: "hunter2"
              properties:
                useSSL: "false"
              pool:
                maximum-pool-size: 10
                minimum-idle: 2
                connection-timeout-ms: 10000
        """.trimIndent() + "\n"

        /** A full document: every key set, nothing defaulted. */
        val VALID = SQLITE_STORAGE + """
            currencies:
              - code: "coins"
                singular: "Coin"
                plural: "Coins"
                symbol: "${'$'}"
                fractional-digits: 2
                starting-balance: 100.00
                default: true
                scope: network
                transferable: true
                balance-check-others: true
                show-in-baltop: true
                format: "<symbol><amount>"
              - code: "gems"
                singular: "Gem"
                plural: "Gems"
                symbol: "G"
                fractional-digits: 0
                starting-balance: 0
                default: false
                scope: server
                transferable: false
                balance-check-others: false
                show-in-baltop: true
                format: "<amount> <currency>"
            settings:
              server-id: "survival"
              language: "en"
              allow-overdraft: false
              rounding-mode: HALF_UP
              keep-transaction-history: true
              baltop-size: 10
        """.trimIndent() + "\n"

        val MINIMAL_CURRENCY = """
            currencies:
              - code: "coins"
                singular: "Coin"
                plural: "Coins"
                symbol: "C"
                fractional-digits: 2
                default: true
        """.trimIndent() + "\n"

        /** Only the keys with no documented default. */
        val MINIMAL = MINIMAL_CURRENCY
    }
}
