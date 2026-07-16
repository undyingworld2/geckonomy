package com.the1mason.geckonomy.infrastructure.config

import com.the1mason.geckonomy.domain.model.CurrencyCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/** Real files rather than a stubbed reader: reading `config.yml` is most of what this class does. */
class ConfigServiceTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `starts on a valid file, with the registry already populated`() {
        val service = start(config())

        assertEquals(listOf("coins"), service.current.currencies.map { it.code.value })
        assertEquals(CurrencyCode("coins"), service.currencies.default().code)
    }

    @Test
    fun `refuses to start on an invalid file, reporting why`() {
        val outcome = ConfigService.start(write(config(currencies = "currencies: []")))

        val errors = (outcome as? StartOutcome.Failed)?.errors ?: fail("expected the start to fail")
        assertTrue(errors.any { "at least one" in it }, errors.toString())
    }

    @Test
    fun `refuses to start when the file is not there, naming the path`() {
        val missing = dir.resolve("config.yml")

        val outcome = ConfigService.start(missing)

        val errors = (outcome as? StartOutcome.Failed)?.errors ?: fail("expected the start to fail")
        assertTrue(errors.single().contains(missing.toString()), errors.toString())
    }

    @Test
    fun `passes on the warnings from a file that loads`() {
        val networked = config(currencies = CURRENCIES.replace("scope: server", "scope: network"), serverId = "default")

        val outcome = ConfigService.start(write(networked))

        val warnings = (outcome as? StartOutcome.Started)?.warnings ?: fail("expected the start to succeed")
        assertTrue(warnings.any { "settings.server-id" in it }, warnings.toString())
    }

    @Test
    fun `a reload rebuilds the registry from the file on disk`() {
        val file = write(config())
        val service = start(file)

        file.writeText(config(currencies = CURRENCIES_WITH_GEMS))
        val outcome = service.reload()

        assertEquals(ReloadOutcome.Applied(emptyList()), outcome)
        assertEquals(listOf("coins", "gems"), service.current.currencies.map { it.code.value })
        assertNotNull(service.currencies.byCode(CurrencyCode("gems")))
    }

    @Test
    fun `a rejected reload leaves the running config exactly as it was`() {
        val file = write(config(currencies = CURRENCIES_WITH_GEMS))
        val service = start(file)
        val before = service.current

        file.writeText(config(currencies = "currencies: []"))
        val outcome = service.reload()

        assertTrue(outcome is ReloadOutcome.Rejected, "expected the reload to be rejected, got $outcome")
        assertEquals(before, service.current)
        assertNotNull(service.currencies.byCode(CurrencyCode("gems")))
    }

    @Test
    fun `a reload that drops a currency says the balances are still there`() {
        val file = write(config(currencies = CURRENCIES_WITH_GEMS))
        val service = start(file)

        file.writeText(config())
        val outcome = service.reload()

        assertTrue(
            warnings(outcome).any { "'gems'" in it && "not deleted" in it },
            "expected a removal warning, got ${warnings(outcome)}",
        )
        assertNull(service.currencies.byCode(CurrencyCode("gems")))
    }

    /**
     * The connection M3 opens at startup is what the server is really talking to, so `current` must
     * keep describing *that* — a changed `storage` block is reported, not adopted (CONFIGURATION.md
     * §4).
     */
    @Test
    fun `a reload warns about a changed storage block instead of applying it`() {
        val file = write(config())
        val service = start(file)

        file.writeText(config(storage = MARIADB_STORAGE))
        val outcome = service.reload()

        assertTrue(
            warnings(outcome).any { "storage settings changed" in it && "restart" in it },
            "expected a storage warning, got ${warnings(outcome)}",
        )
        assertEquals(StorageType.SQLITE, service.current.storage.type)
    }

    @Test
    fun `a reload warns that a changed server id orphans this server's per-server balances`() {
        val file = write(config())
        val service = start(file)

        file.writeText(config(serverId = "lobby"))
        val outcome = service.reload()

        assertTrue(
            warnings(outcome).any { "settings.server-id" in it && "'survival'" in it && "'lobby'" in it },
            "expected a server-id warning, got ${warnings(outcome)}",
        )
    }

    @Test
    fun `a reload applies the settings whose consumers read them fresh`() {
        val file = write(config())
        val service = start(file)

        file.writeText(config().replace("baltop-size: 10", "baltop-size: 25"))
        service.reload()

        assertEquals(25, service.current.settings.baltopSize)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun start(file: Path): ConfigService =
        (ConfigService.start(file) as? StartOutcome.Started)?.service ?: fail("expected the start to succeed")

    private fun start(text: String): ConfigService = start(write(text))

    private fun write(text: String): Path = dir.resolve("config.yml").apply { writeText(text) }

    private fun warnings(outcome: ReloadOutcome): List<String> =
        (outcome as? ReloadOutcome.Applied)?.warnings ?: fail("expected the reload to be applied, got $outcome")

    private fun config(
        storage: String = SQLITE_STORAGE,
        currencies: String = CURRENCIES,
        serverId: String = "survival",
    ): String = "$storage\n$currencies\nsettings:\n  server-id: \"$serverId\"\n  baltop-size: 10\n"

    private companion object {

        val SQLITE_STORAGE = """
            storage:
              type: sqlite
              file: "plugins/Geckonomy/data.db"
        """.trimIndent()

        val MARIADB_STORAGE = """
            storage:
              type: mariadb
              host: "db.example.com"
              database: "geckonomy"
              username: "gecko"
              password: "hunter2"
        """.trimIndent()

        val CURRENCIES = """
            currencies:
              - code: "coins"
                singular: "Coin"
                plural: "Coins"
                symbol: "C"
                fractional-digits: 2
                default: true
                scope: server
        """.trimIndent()

        val CURRENCIES_WITH_GEMS = """
            currencies:
              - code: "coins"
                singular: "Coin"
                plural: "Coins"
                symbol: "C"
                fractional-digits: 2
                default: true
                scope: server
              - code: "gems"
                singular: "Gem"
                plural: "Gems"
                symbol: "G"
                fractional-digits: 0
                default: false
                scope: server
        """.trimIndent()
    }
}
