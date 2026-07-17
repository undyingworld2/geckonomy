package com.the1mason.geckonomy.infrastructure.bukkit.command

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.AccountId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** `/pay <player> <amount> [currency]` end to end (`docs/tasks/M7-commands.md`). */
class PayCommandTest {

    private val fixture = EconomyFixture()
    private val harness = CommandHarness(fixture)

    @AfterEach
    fun tearDown() = harness.unmock()

    private fun givenCoins(id: AccountId, name: String, amount: String) = runBlocking {
        fixture.givenAccount(id, name)
        fixture.balances.set(id, TestCurrencies.COINS, BigDecimal(amount))
    }

    private fun balanceOf(id: AccountId): BigDecimal =
        runBlocking { (fixture.service.balance(id, TestCurrencies.COINS.code) as Outcome.Success).value.amount }

    @Test
    fun `moves money and tells both players`() {
        val alice = harness.player("Alice")
        val bob = harness.player("Bob")
        givenCoins(AccountId(alice.uniqueId), "Alice", "100.00")
        givenCoins(AccountId(bob.uniqueId), "Bob", "0.00")

        harness.dispatch(alice, "pay Bob 25")

        assertEquals("[Geckonomy] Sent \$25.00 to Bob.", harness.nextMessage(alice))
        assertEquals("[Geckonomy] Received \$25.00 from Alice.", harness.nextMessage(bob))
        assertEquals(BigDecimal("75.00"), balanceOf(AccountId(alice.uniqueId)))
        assertEquals(BigDecimal("25.00"), balanceOf(AccountId(bob.uniqueId)))
    }

    @Test
    fun `refuses to move more than the payer has, and moves nothing`() {
        val alice = harness.player("Alice")
        val bob = harness.player("Bob")
        givenCoins(AccountId(alice.uniqueId), "Alice", "10.00")
        givenCoins(AccountId(bob.uniqueId), "Bob", "0.00")

        harness.dispatch(alice, "pay Bob 25")

        assertEquals("[Geckonomy] Alice doesn't have \$25.00.", harness.nextMessage(alice))
        assertEquals(BigDecimal("10.00"), balanceOf(AccountId(alice.uniqueId)))
        assertEquals(BigDecimal("0.00"), balanceOf(AccountId(bob.uniqueId)))
    }

    @Test
    fun `refuses an amount that is not a number`() {
        val alice = harness.player("Alice")
        harness.player("Bob")
        givenCoins(AccountId(alice.uniqueId), "Alice", "100.00")

        harness.dispatch(alice, "pay Bob lots")

        assertEquals("[Geckonomy] Invalid amount.", harness.nextMessage(alice))
    }

    @Test
    fun `refuses a negative amount`() {
        val alice = harness.player("Alice")
        val bob = harness.player("Bob")
        givenCoins(AccountId(alice.uniqueId), "Alice", "100.00")
        givenCoins(AccountId(bob.uniqueId), "Bob", "0.00")

        harness.dispatch(alice, "pay Bob -25")

        assertEquals("[Geckonomy] Invalid amount.", harness.nextMessage(alice))
        assertEquals(BigDecimal("100.00"), balanceOf(AccountId(alice.uniqueId)))
    }

    @Test
    fun `refuses paying yourself`() {
        val alice = harness.player("Alice")
        givenCoins(AccountId(alice.uniqueId), "Alice", "100.00")

        harness.dispatch(alice, "pay Alice 25")

        assertEquals("[Geckonomy] You can't pay yourself.", harness.nextMessage(alice))
        assertEquals(BigDecimal("100.00"), balanceOf(AccountId(alice.uniqueId)))
    }

    /** `gems` is `transferable: false` — a hard gate, checked before the economy is ever asked. */
    @Test
    fun `refuses a currency flagged untransferable`() {
        val alice = harness.player("Alice")
        val bob = harness.player("Bob")
        runBlocking {
            fixture.givenAccount(AccountId(alice.uniqueId), "Alice")
            fixture.givenAccount(AccountId(bob.uniqueId), "Bob")
            fixture.balances.set(AccountId(alice.uniqueId), TestCurrencies.GEMS, BigDecimal("10"))
        }

        harness.dispatch(alice, "pay Bob 5 gems")

        assertEquals("[Geckonomy] Gems can't be paid to other players.", harness.nextMessage(alice))
    }

    @Test
    fun `refuses a currency the player lacks the per-currency node for`() {
        val alice = harness.player("Alice")
        harness.player("Bob")
        alice.addAttachment(harness.plugin, "geckonomy.pay.coins", false)
        givenCoins(AccountId(alice.uniqueId), "Alice", "100.00")

        harness.dispatch(alice, "pay Bob 25")

        assertEquals("[Geckonomy] You can't use Coins here.", harness.nextMessage(alice))
    }

    @Test
    fun `says nothing without the base permission`() {
        val stranger = harness.strangerWithNoPermissions()
        harness.player("Bob")

        harness.dispatch(stranger, "pay Bob 25")

        assertEquals(emptyList<String>(), harness.messages(stranger).filter { it.startsWith("[Geckonomy]") })
    }

    @Test
    fun `pays an offline player it can still resolve`() {
        val alice = harness.player("Alice")
        givenCoins(AccountId(alice.uniqueId), "Alice", "100.00")
        // Never online in this test: only our account table knows the name.
        val ghost = AccountId(java.util.UUID.randomUUID())
        givenCoins(ghost, "Casper", "0.00")

        harness.dispatch(alice, "pay Casper 25")

        assertEquals("[Geckonomy] Sent \$25.00 to Casper.", harness.nextMessage(alice))
        assertEquals(BigDecimal("25.00"), balanceOf(ghost))
    }
}
