package com.the1mason.geckonomy.infrastructure.bukkit.command

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.entity.PlayerMock
import kotlin.io.path.writeText

/** `/geckonomy reload|version` end to end. */
class GeckonomyCommandTest {

    private val harness = CommandHarness()

    @AfterEach
    fun tearDown() = harness.unmock()

    private fun admin(): PlayerMock = harness.server.addPlayer("Admin").apply {
        addAttachment(harness.plugin, "geckonomy.admin", true)
    }

    @Test
    fun `version reports the plugin version`() {
        val eco = admin()

        harness.dispatch(eco, "geckonomy version")

        assertEquals("[Geckonomy] Geckonomy ${CommandHarness.VERSION}", harness.nextMessage(eco))
    }

    @Test
    fun `reload applies a valid config`() {
        val eco = admin()

        harness.dispatch(eco, "geckonomy reload")

        assertEquals("[Geckonomy] Configuration reloaded.", harness.nextMessage(eco))
    }

    /**
     * A rejected file changes nothing and the server keeps running on what it had — so the only thing
     * the player must be told is that it did not take.
     */
    @Test
    fun `reload refuses a broken config and keeps the old one`() {
        val eco = admin()
        val before = harness.config.current
        harness.configFile.writeText("currencies: []\nthis is not: [valid")

        harness.dispatch(eco, "geckonomy reload")

        assertTrue(
            harness.nextMessage(eco)!!.startsWith("[Geckonomy] Reload failed"),
            "expected the reload-failed message",
        )
        assertEquals(before, harness.config.current, "the rejected config must not have been applied")
    }

    @Test
    fun `reload picks up a currency added to the config`() {
        val eco = admin()
        harness.configFile.writeText(
            CommandHarness.defaultConfigYaml().replace(
                "settings:",
                """
                  - code: "rubies"
                    singular: "Ruby"
                    plural: "Rubies"
                    symbol: "R"
                    fractional-digits: 0
                    starting-balance: 0
                    default: false
                    scope: server
                    transferable: true
                    balance-check-others: true
                    show-in-baltop: true
                    format: "<amount> <currency>"

                settings:
                """.trimIndent(),
            ),
        )

        harness.dispatch(eco, "geckonomy reload")

        assertEquals("[Geckonomy] Configuration reloaded.", harness.nextMessage(eco))
        assertTrue(
            harness.config.currencies.all().any { it.code.value == "rubies" },
            "the new currency should be live in the registry",
        )
    }

    @Test
    fun `says nothing to a player without the admin node`() {
        val alice = harness.player("Alice")

        harness.dispatch(alice, "geckonomy reload")

        assertEquals(emptyList<String>(), harness.messages(alice).filter { it.startsWith("[Geckonomy]") })
    }
}
