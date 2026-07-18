package com.the1mason.geckonomy.infrastructure.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.logging.Level
import kotlin.io.path.writeText

/**
 * The fallback chain (LOCALIZATION.md §1): active language → the server's `en.yml` → the jar's
 * `en.yml` → the raw key.
 *
 * Real files in a temp directory rather than injected strings: what these tests are actually about is
 * which file wins, and a fake that handed over maps would be testing the resolution order while
 * assuming away the file lookup that decides it.
 */
class LanguageRepositoryTest {

    @TempDir
    lateinit var directory: Path

    private val log = LogCapture()

    private fun repository() = LanguageRepository(directory, log.logger)

    /** A repository whose jar shipped without an `en.yml` — the broken-build case. */
    private fun withoutBundle() = LanguageRepository(directory, log.logger) { null }

    private fun writeLanguage(code: String, contents: String) {
        directory.resolve("$code.yml").writeText(contents.trimIndent())
    }

    @Test
    fun `reads the active language`() {
        writeLanguage("de", "balance:\n  self: 'Kontostand: <formatted>'")
        val repository = repository().apply { reload("de") }

        assertEquals("Kontostand: <formatted>", repository.template(MessageKey.BALANCE_SELF))
    }

    @Test
    fun `falls back to the on-disk en for a key the active language lacks`() {
        writeLanguage("de", "balance:\n  self: 'Kontostand: <formatted>'")
        writeLanguage("en", "balance:\n  self: 'Balance: <formatted>'\n  other: 'Custom other'")
        val repository = repository().apply { reload("de") }

        // de.yml has no balance.other — a half-finished translation, which is the normal state of one.
        assertEquals("Custom other", repository.template(MessageKey.BALANCE_OTHER))
    }

    @Test
    fun `falls back to the bundled en for a key no file on disk has`() {
        // The upgrade case, and the reason the bundled layer exists: an owner customised en.yml back
        // when it had two keys, and a later Geckonomy added more. Neither file on disk has them.
        writeLanguage("en", "balance:\n  self: 'Mine'")
        val repository = repository().apply { reload("en") }

        assertEquals("Mine", repository.template(MessageKey.BALANCE_SELF), "the owner's edit must still win")
        assertTrue(
            repository.template(MessageKey.ERROR_NOT_TRANSFERABLE).contains("<currency>"),
            "a key only the jar has must still render its real message",
        )
    }

    @Test
    fun `uses the bundled en when the data folder is empty`() {
        val repository = repository().apply { reload("en") }

        assertTrue(repository.template(MessageKey.PREFIX).contains("Geckonomy"))
    }

    @Test
    fun `answers from the bundled en before it is ever reloaded`() {
        // Nothing should be asking this early, but rendering raw keys because of wiring order would be
        // a silly way to find out.
        assertTrue(repository().template(MessageKey.PREFIX).contains("Geckonomy"))
    }

    @Test
    fun `warns and falls back when the active language has no file at all`() {
        repository().reload("nonexistent")

        assertTrue(
            log.warnings().any { it.contains("nonexistent") && it.contains("using 'en'") },
            "an owner who typo'd settings.language must be told, not silently served English: ${log.warnings()}",
        )
    }

    @Test
    fun `falls back past a language file that is not valid YAML`() {
        writeLanguage("de", "balance:\n  self: 'unterminated")
        val repository = repository().apply { reload("de") }

        // A broken translation degrades to English rather than taking the plugin down with it.
        assertEquals("<prefix><green>Balance:</green> <white><formatted></white>", repository.template(MessageKey.BALANCE_SELF))
        assertTrue(log.warnings().any { it.contains("de.yml") }, "the broken file must be named: ${log.warnings()}")
    }

    @Test
    fun `does not claim a broken language file is missing`() {
        writeLanguage("de", "balance:\n  self: 'unterminated")

        repository().reload("de")

        // The file is sitting right where it belongs; telling its owner it "has no file" would send
        // them off to create the one thing they already have.
        assertTrue(
            log.warnings().none { it.contains("has no file") },
            "a broken file needs fixing, not creating: ${log.warnings()}",
        )
    }

    @Test
    fun `renders the raw key when no layer has the message`() {
        val repository = withoutBundle().apply { reload("en") }

        assertEquals("balance.self", repository.template(MessageKey.BALANCE_SELF))
        assertTrue(log.warnings().any { it.contains("balance.self") }, "a missing key must warn: ${log.warnings()}")
    }

    @Test
    fun `warns once per missing key, not once per render`() {
        val repository = withoutBundle().apply { reload("en") }

        repeat(5) { repository.template(MessageKey.BALANCE_SELF) }

        // A message missing from /balance would otherwise log per command, forever — burying the very
        // warning it is trying to deliver.
        assertEquals(1, log.warnings().count { it.contains("balance.self") }, "expected exactly one warning")
    }

    @Test
    fun `warns again after a reload`() {
        val repository = withoutBundle().apply { reload("en") }
        repository.template(MessageKey.BALANCE_SELF)

        repository.reload("en")
        repository.template(MessageKey.BALANCE_SELF)

        // Re-arming on reload is what makes the warning useful to someone who just tried to fix it.
        assertEquals(2, log.warnings().count { it.contains("balance.self") })
    }

    @Test
    fun `reload swaps the language`() {
        writeLanguage("de", "balance:\n  self: 'Kontostand'")
        writeLanguage("fr", "balance:\n  self: 'Solde'")
        val repository = repository().apply { reload("de") }

        repository.reload("fr")

        assertEquals("Solde", repository.template(MessageKey.BALANCE_SELF))
    }

    @Test
    fun `reports a broken jar rather than dying over it`() {
        val repository = withoutBundle().apply { reload("en") }

        repository.template(MessageKey.BALANCE_SELF)

        assertTrue(
            log.messages(Level.SEVERE).any { it.contains("missing from the plugin jar") },
            "a jar without its own language file is a broken build and must say so: ${log.records}",
        )
    }

    // ── currencies: override block (SPEC.md FR-L5) ──────────────────────

    @Test
    fun `reads a currency name override from the active language`() {
        writeLanguage("de", "currencies:\n  coins:\n    singular: 'Münze'\n    plural: 'Münzen'")
        val repository = repository().apply { reload("de") }

        assertEquals("Münze", repository.currencyOverride("coins", "singular"))
        assertEquals("Münzen", repository.currencyOverride("coins", "plural"))
    }

    @Test
    fun `a currency present but missing one key answers null for only that key`() {
        writeLanguage("de", "currencies:\n  coins:\n    singular: 'Münze'")
        val repository = repository().apply { reload("de") }

        assertEquals("Münze", repository.currencyOverride("coins", "singular"))
        assertEquals(null, repository.currencyOverride("coins", "plural"))
    }

    @Test
    fun `a currency code absent from the block answers null for both keys`() {
        writeLanguage("de", "currencies:\n  coins:\n    singular: 'Münze'")
        val repository = repository().apply { reload("de") }

        assertEquals(null, repository.currencyOverride("gems", "singular"))
        assertEquals(null, repository.currencyOverride("gems", "plural"))
    }

    @Test
    fun `an active language with no currencies block answers null rather than reading en's`() {
        // FR-L5's fallback is config.yml, not the en.yml layer messages fall through to — a German
        // server with no currencies: block must not silently pick up English's overrides.
        writeLanguage("en", "currencies:\n  coins:\n    singular: 'EnglishOverride'")
        writeLanguage("de", "balance:\n  self: 'Kontostand'")
        val repository = repository().apply { reload("de") }

        assertEquals(null, repository.currencyOverride("coins", "singular"))
    }

    @Test
    fun `no currencies block at all answers null`() {
        val repository = repository().apply { reload("en") }

        assertEquals(null, repository.currencyOverride("coins", "singular"))
    }

    @Test
    fun `reload picks up an edited currencies block live`() {
        writeLanguage("de", "currencies:\n  coins:\n    singular: 'Münze'")
        val repository = repository().apply { reload("de") }
        assertEquals("Münze", repository.currencyOverride("coins", "singular"))

        writeLanguage("de", "currencies:\n  coins:\n    singular: 'Taler'")
        repository.reload("de")

        assertEquals("Taler", repository.currencyOverride("coins", "singular"))
    }

    @Test
    fun `a malformed active language file degrades to no override, not an exception`() {
        writeLanguage("de", "balance:\n  self: 'unterminated")

        val repository = repository().apply { reload("de") }

        assertEquals(null, repository.currencyOverride("coins", "singular"))
    }
}
