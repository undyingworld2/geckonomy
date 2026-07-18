package com.the1mason.geckonomy.infrastructure.i18n

import com.the1mason.geckonomy.domain.coins
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.writeText

/**
 * [MessageService] against the **real** `lang/en.yml`, not a stub.
 *
 * That is the point of it being a class rather than an interface (CODING_STANDARDS.md §5): these tests
 * prove the shipped messages exist, carry the placeholders their callers fill, and read correctly —
 * none of which a fake could tell us.
 */
class MessageServiceTest {

    @TempDir
    lateinit var directory: Path

    private val log = LogCapture()
    private val format = FormatMoney({ Locale.US }, CurrencyNames { _, _ -> null })

    private fun service(language: String = "en") =
        MessageService(LanguageRepository(directory, log.logger), { language }).apply { reload() }

    private fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)

    private fun writeLanguage(code: String, contents: String) {
        directory.resolve("$code.yml").writeText(contents.trimIndent())
    }

    @Test
    fun `renders a shipped message with its placeholders filled`() {
        val message = service().render(
            MessageKey.BALANCE_SELF,
            Placeholders.money("formatted", "100.00".coins, format),
        )

        assertEquals("[Geckonomy] Balance: $100.00", plain(message))
    }

    @Test
    fun `injects the prefix into every message`() {
        // Templates say <prefix> rather than repeating the markup, so nothing but this line has to
        // know what the prefix is (LOCALIZATION.md §2).
        assertTrue(plain(service().render(MessageKey.ERROR_INVALID_AMOUNT)).startsWith("[Geckonomy] "))
    }

    @Test
    fun `keeps player-supplied text unparsed through the service`() {
        val message = service().render(MessageKey.ERROR_ACCOUNT_NOT_FOUND, Placeholders.text("target", "<red>evil</red>"))

        assertEquals("[Geckonomy] No account for <red>evil</red>.", plain(message))
    }

    @Test
    fun `renders a message the active language overrides`() {
        writeLanguage("de", "balance:\n  self: '<prefix>Kontostand: <formatted>'")

        val message = service("de").render(MessageKey.BALANCE_SELF, Placeholders.money("formatted", "100.00".coins, format))

        assertEquals("[Geckonomy] Kontostand: $100.00", plain(message))
    }

    @Test
    fun `sends the rendered message to the audience`() {
        val sent = mutableListOf<Component>()
        val audience = object : Audience {
            override fun sendMessage(message: Component) { sent += message }
        }

        service().send(audience, MessageKey.ADMIN_RELOADED)

        assertEquals("[Geckonomy] Configuration reloaded.", plain(sent.single()))
    }

    @Test
    fun `reload picks up an edited language file`() {
        val service = service("de")
        writeLanguage("de", "admin:\n  reloaded: '<prefix>Neu geladen.'")

        service.reload()

        assertEquals("[Geckonomy] Neu geladen.", plain(service.render(MessageKey.ADMIN_RELOADED)))
    }

    @Test
    fun `reload picks up an edited prefix`() {
        val service = service("de")
        writeLanguage("de", "prefix: '<gray>[Eco]</gray> '\nadmin:\n  reloaded: '<prefix>Neu geladen.'")

        service.reload()

        // The prefix is cached, so a reload that did not recompute it would keep the old one forever.
        assertEquals("[Eco] Neu geladen.", plain(service.render(MessageKey.ADMIN_RELOADED)))
    }

    @Test
    fun `does not recurse on a prefix that contains the prefix tag`() {
        writeLanguage("de", "prefix: '<prefix>[Eco] '\nadmin:\n  reloaded: '<prefix>Reloaded.'")

        // Rendered with no prefix resolver of its own, so the tag stays literal instead of recursing.
        assertEquals("<prefix>[Eco] Reloaded.", plain(service("de").render(MessageKey.ADMIN_RELOADED)))
    }

    @Test
    fun `every shipped message renders without throwing`() {
        val service = service()

        // The cheap guard against a MiniMessage syntax error in en.yml: a broken tag in a message
        // nothing has wired yet would otherwise surface at M7, on a live server, in front of a player.
        MessageKey.entries.forEach { key ->
            val rendered = runCatching { plain(service.render(key)) }
            assertTrue(rendered.isSuccess, "${key.path} failed to render: ${rendered.exceptionOrNull()}")
        }
    }

    @Test
    fun `renders every shipped message from a real language file rather than a raw key`() {
        val service = service()

        MessageKey.entries.forEach { key ->
            assertTrue(plain(service.render(key)) != key.path, "${key.path} rendered as its own key")
        }
        assertTrue(log.warnings().isEmpty(), "the shipped language must be complete: ${log.warnings()}")
    }
}
