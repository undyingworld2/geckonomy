package com.the1mason.geckonomy.infrastructure.i18n

import com.the1mason.geckonomy.application.usecase.FormatMoney
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.coins
import com.the1mason.geckonomy.domain.gems
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Locale

/**
 * Rendering and the placeholder rules (LOCALIZATION.md §3).
 *
 * Assertions are mostly against the plain-text projection: what matters is which *characters* reach a
 * player, and comparing whole component trees would fail on styling nobody is testing.
 */
class MiniMessageRendererTest {

    private val renderer = MiniMessageRenderer()
    private val format = FormatMoney { Locale.US }

    private fun plain(template: String, resolvers: TagResolver = TagResolver.empty()): String =
        PlainTextComponentSerializer.plainText().serialize(renderer.render(template, resolvers))

    @Test
    fun `renders a template with no placeholders`() {
        assertEquals("Hello", plain("<green>Hello</green>"))
    }

    @Test
    fun `applies the markup the template author wrote`() {
        val component = renderer.render("<green>Hello</green>", TagResolver.empty())

        // The one assertion about styling rather than characters: the template author's markup is
        // supposed to be parsed, which is the flip side of player input never being.
        assertEquals(NamedTextColor.GREEN, component.color())
    }

    @Test
    fun `fills a text placeholder`() {
        assertEquals("Hi Alice!", plain("Hi <target>!", Placeholders.text("target", "Alice")))
    }

    @Test
    fun `does not parse MiniMessage tags in player-supplied text`() {
        // The security property, and the reason Placeholders exists at all: a player renamed to
        // <red>evil</red> must not author colour in anyone else's chat (LOCALIZATION.md §3).
        assertEquals(
            "Hi <red>evil</red>!",
            plain("Hi <target>!", Placeholders.text("target", "<red>evil</red>")),
        )
    }

    @Test
    fun `does not let player-supplied text smuggle in another placeholder`() {
        // A player named "<target>" must not cause a second substitution pass.
        assertEquals("Hi <target>!", plain("Hi <target>!", Placeholders.text("target", "<target>")))
    }

    @Test
    fun `formats money through its currency template`() {
        assertEquals(
            "You paid $100.00",
            plain("You paid <formatted>", Placeholders.money("formatted", "100.00".coins, format)),
        )
    }

    @Test
    fun `does not parse markup coming from a currency symbol`() {
        // The symbol is config-authored, not template-authored: a symbol of <rainbow> would otherwise
        // colour the rest of the message from inside a value.
        val sneaky = TestCurrencies.COINS.copy(symbol = "<rainbow>")
        val money = com.the1mason.geckonomy.domain.model.Money(BigDecimal("5.00"), sneaky)

        assertEquals("<rainbow>5.00", plain("<formatted>", Placeholders.money("formatted", money, format)))
    }

    @Test
    fun `names a currency in the plural by default`() {
        assertEquals("Top balances (Gems)", plain("Top balances (<currency>)", Placeholders.currency(TestCurrencies.GEMS)))
    }

    @Test
    fun `names a currency in the singular for exactly one`() {
        assertEquals("1 Gem", plain("1 <currency>", Placeholders.currency(TestCurrencies.GEMS, BigDecimal.ONE)))
    }

    @Test
    fun `fills the currency symbol`() {
        assertEquals("$", plain("<symbol>", Placeholders.currency(TestCurrencies.COINS)))
    }

    @Test
    fun `fills a number placeholder`() {
        assertEquals("1. Alice", plain("<rank>. <name>", Placeholders.of(Placeholders.number("rank", 1), Placeholders.text("name", "Alice"))))
    }

    @Test
    fun `combines several resolvers`() {
        val resolvers = Placeholders.of(
            Placeholders.text("target", "Bob"),
            Placeholders.money("formatted", "3".gems, format),
        )

        assertEquals("Gave 3 Gems to Bob", plain("Gave <formatted> to <target>", resolvers))
    }

    @Test
    fun `leaves a tag nobody supplied as literal text`() {
        // Language files are edited by server owners. A typo should look wrong, not throw and kill the
        // command that rendered it.
        assertEquals("Balance: <nonsense>", plain("Balance: <nonsense>"))
    }
}
