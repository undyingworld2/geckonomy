package com.the1mason.geckonomy.infrastructure.bukkit.command

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.Currency
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** `/balance [player] [currency]` end to end (`docs/tasks/M7-commands.md`). */
class BalanceCommandTest {

    private val fixture = EconomyFixture()
    private val harness = CommandHarness(fixture)

    @AfterEach
    fun tearDown() = harness.unmock()

    private fun givenBalance(id: AccountId, name: String, amount: String, currency: Currency = TestCurrencies.COINS) =
        runBlocking {
            fixture.givenAccount(id, name)
            fixture.balances.set(id, currency, BigDecimal(amount))
        }

    @Test
    fun `shows your own balance`() {
        val alice = harness.player("Alice")
        givenBalance(AccountId(alice.uniqueId), "Alice", "75.00")

        harness.dispatch(alice, "balance")

        assertEquals("[Geckonomy] Balance: \$75.00", harness.nextMessage(alice))
    }

    /** FR-CMD5, and the one fact only a registration test can establish. */
    @Test
    fun `bal is the same command as balance`() {
        val alice = harness.player("Alice")
        givenBalance(AccountId(alice.uniqueId), "Alice", "75.00")

        harness.dispatch(alice, "bal")

        assertEquals("[Geckonomy] Balance: \$75.00", harness.nextMessage(alice))
    }

    @Test
    fun `shows a named currency`() {
        val alice = harness.player("Alice")
        givenBalance(AccountId(alice.uniqueId), "Alice", "3", TestCurrencies.GEMS)

        harness.dispatch(alice, "balance gems")

        assertEquals("[Geckonomy] Balance: 3 Gems", harness.nextMessage(alice))
    }

    @Test
    fun `shows another player's balance`() {
        val alice = harness.player("Alice")
        val bob = harness.player("Bob")
        givenBalance(AccountId(bob.uniqueId), "Bob", "40.00")

        harness.dispatch(alice, "balance Bob")

        assertEquals("[Geckonomy] Bob's balance: \$40.00", harness.nextMessage(alice))
    }

    /**
     * `/balance <word>` is ambiguous by construction — the word may be a player or a currency — so a
     * word that is neither can only be reported as one of them. It reads as a player, which is what
     * the far commoner mistake actually is.
     */
    @Test
    fun `a lone word that names nothing is reported as a missing player`() {
        val alice = harness.player("Alice")
        givenBalance(AccountId(alice.uniqueId), "Alice", "75.00")

        harness.dispatch(alice, "balance unobtainium")

        assertEquals("[Geckonomy] No player named unobtainium.", harness.nextMessage(alice))
    }

    /** In the unambiguous position there is no such excuse. */
    @Test
    fun `refuses an unknown currency once a player is named`() {
        val alice = harness.player("Alice")
        harness.player("Bob")

        harness.dispatch(alice, "balance Bob unobtainium")

        assertEquals("[Geckonomy] Unknown currency: unobtainium.", harness.nextMessage(alice))
    }

    @Test
    fun `reports a player nobody has heard of`() {
        val alice = harness.player("Alice")

        harness.dispatch(alice, "balance Ghost")

        assertEquals("[Geckonomy] No player named Ghost.", harness.nextMessage(alice))
    }

    /**
     * The base node gates the command itself, through Brigadier's `requires`: the node does not exist
     * for a sender without it, so nothing of ours runs and nothing of ours is said.
     */
    @Test
    fun `says nothing without the base permission`() {
        val stranger = harness.strangerWithNoPermissions()
        runBlocking { fixture.givenAccount(AccountId(stranger.uniqueId), "Nobody") }

        harness.dispatch(stranger, "balance")

        assertEquals(emptyList<String>(), harness.messages(stranger).filter { it.startsWith("[Geckonomy]") })
    }

    @Test
    fun `refuses a currency the player lacks the per-currency node for`() {
        val alice = harness.player("Alice")
        alice.addAttachment(harness.plugin, "geckonomy.balance.gems", false)
        givenBalance(AccountId(alice.uniqueId), "Alice", "3", TestCurrencies.GEMS)

        harness.dispatch(alice, "balance gems")

        assertEquals("[Geckonomy] You can't use Gems here.", harness.nextMessage(alice))
    }

    /** `gems` is `balance-check-others: false` — a hard gate no permission opens. */
    @Test
    fun `refuses another player's balance in a currency flagged against it`() {
        val alice = harness.player("Alice")
        val bob = harness.player("Bob")
        alice.addAttachment(harness.plugin, "geckonomy.balance.others.gems", true)
        givenBalance(AccountId(bob.uniqueId), "Bob", "3", TestCurrencies.GEMS)

        harness.dispatch(alice, "balance Bob gems")

        assertEquals("[Geckonomy] You can't view others' Gems balance.", harness.nextMessage(alice))
    }
}
