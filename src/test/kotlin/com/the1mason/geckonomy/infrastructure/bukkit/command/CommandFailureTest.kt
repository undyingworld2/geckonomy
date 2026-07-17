package com.the1mason.geckonomy.infrastructure.bukkit.command

import com.the1mason.geckonomy.application.EconomyFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.logging.Level

/**
 * What a command does when the code *above* the economy throws.
 *
 * The use cases report their own failures as an `EconomyError` and are covered elsewhere. This is the
 * other half, and the half that was missing: a bug in a handler used to reach the plugin scope's
 * `SupervisorJob`, which cancelled that one coroutine and told nobody. The player's command simply
 * never answered — the one failure mode they cannot report, because there is nothing to report.
 */
class CommandFailureTest {

    private val harness = CommandHarness(EconomyFixture())

    @AfterEach
    fun tearDown() = harness.unmock()

    private fun givenTheCommandThrows() {
        harness.baltopSizeSupplier = { throw IllegalStateException("boom") }
    }

    @Test
    fun `answers the player instead of failing silently`() {
        val alice = harness.player("Alice")
        givenTheCommandThrows()

        harness.dispatch(alice, "baltop")

        // The same message a storage failure produces. A player cannot act on the difference between
        // "the database is down" and "we have a bug", and the log is where that distinction lives.
        assertEquals(
            listOf("[Geckonomy] Something went wrong. Try again, and tell an admin if it keeps happening."),
            harness.messages(alice),
        )
    }

    @Test
    fun `logs the bug at SEVERE, naming what the player was doing`() {
        val alice = harness.player("Alice")
        givenTheCommandThrows()

        harness.dispatch(alice, "baltop")

        val severe = harness.log.records.single { it.level == Level.SEVERE }
        assertTrue(severe.message.contains("listing the top balances"), severe.message)
        assertEquals("boom", severe.thrown.message, "the cause must survive, or the log cannot be acted on")
    }

    @Test
    fun `a throw in one command does not stop the next`() {
        val alice = harness.player("Alice")
        givenTheCommandThrows()
        harness.dispatch(alice, "baltop")
        harness.messages(alice)

        harness.baltopSizeSupplier = { 10 }
        harness.dispatch(alice, "baltop")

        assertEquals(listOf("[Geckonomy] Nobody has any Coins yet."), harness.messages(alice))
    }
}
