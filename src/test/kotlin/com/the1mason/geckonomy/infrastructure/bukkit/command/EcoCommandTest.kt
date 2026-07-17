package com.the1mason.geckonomy.infrastructure.bukkit.command

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.AccountId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.math.BigDecimal

/** `/eco give|take|set|reset <player> [amount] [currency]` end to end. */
class EcoCommandTest {

    private val fixture = EconomyFixture()
    private val harness = CommandHarness(fixture)

    @AfterEach
    fun tearDown() = harness.unmock()

    /** `/eco` is `geckonomy.admin` and nothing else. */
    private fun admin(): PlayerMock = harness.server.addPlayer("Admin").apply {
        addAttachment(harness.plugin, "geckonomy.admin", true)
    }

    private fun givenCoins(id: AccountId, name: String, amount: String) = runBlocking {
        fixture.givenAccount(id, name)
        fixture.balances.set(id, TestCurrencies.COINS, BigDecimal(amount))
    }

    private fun balanceOf(id: AccountId, currency: com.the1mason.geckonomy.domain.model.Currency = TestCurrencies.COINS) =
        runBlocking { (fixture.service.balance(id, currency.code) as Outcome.Success).value.amount }

    @Test
    fun `give credits the amount and reports the amount, not the balance`() {
        val eco = admin()
        val bob = harness.player("Bob")
        givenCoins(AccountId(bob.uniqueId), "Bob", "1000.00")

        harness.dispatch(eco, "eco give Bob 100")

        // The trap: the use case returns the *balance*, so a naive handler says "Gave $1100.00".
        assertEquals("[Geckonomy] Gave \$100.00 to Bob.", harness.nextMessage(eco))
        assertEquals(BigDecimal("1100.00"), balanceOf(AccountId(bob.uniqueId)))
    }

    @Test
    fun `take debits the amount`() {
        val eco = admin()
        val bob = harness.player("Bob")
        givenCoins(AccountId(bob.uniqueId), "Bob", "1000.00")

        harness.dispatch(eco, "eco take Bob 100")

        assertEquals("[Geckonomy] Took \$100.00 from Bob.", harness.nextMessage(eco))
        assertEquals(BigDecimal("900.00"), balanceOf(AccountId(bob.uniqueId)))
    }

    @Test
    fun `set replaces the balance and reports the balance`() {
        val eco = admin()
        val bob = harness.player("Bob")
        givenCoins(AccountId(bob.uniqueId), "Bob", "1000.00")

        harness.dispatch(eco, "eco set Bob 42")

        assertEquals("[Geckonomy] Set Bob's balance to \$42.00.", harness.nextMessage(eco))
        assertEquals(BigDecimal("42.00"), balanceOf(AccountId(bob.uniqueId)))
    }

    /** Reset takes its amount from the currency's `starting-balance`, and needs no argument. */
    @Test
    fun `reset restores the starting balance`() {
        val eco = admin()
        val bob = harness.player("Bob")
        givenCoins(AccountId(bob.uniqueId), "Bob", "5.00")

        harness.dispatch(eco, "eco reset Bob")

        assertEquals("[Geckonomy] Reset Bob's balance to \$100.00.", harness.nextMessage(eco))
        assertEquals(TestCurrencies.COINS.startingBalance, balanceOf(AccountId(bob.uniqueId)))
    }

    @Test
    fun `works on a named currency`() {
        val eco = admin()
        val bob = harness.player("Bob")
        runBlocking { fixture.givenAccount(AccountId(bob.uniqueId), "Bob") }

        harness.dispatch(eco, "eco give Bob 5 gems")

        assertEquals("[Geckonomy] Gave 5 Gems to Bob.", harness.nextMessage(eco))
        assertEquals(BigDecimal("5"), balanceOf(AccountId(bob.uniqueId), TestCurrencies.GEMS))
    }

    /**
     * `gems` is `transferable: false`, which stops players paying it and has nothing to say about an
     * admin granting it (SPEC.md §7).
     */
    @Test
    fun `ignores the currency flags players are bound by`() {
        val eco = admin()
        val bob = harness.player("Bob")
        runBlocking { fixture.givenAccount(AccountId(bob.uniqueId), "Bob") }

        harness.dispatch(eco, "eco set Bob 7 gems")

        assertEquals("[Geckonomy] Set Bob's balance to 7 Gems.", harness.nextMessage(eco))
    }

    /** Admins bypass the per-currency nodes; only `geckonomy.admin` matters. */
    @Test
    fun `ignores the per-currency permission nodes`() {
        val eco = admin()
        eco.addAttachment(harness.plugin, "geckonomy.pay.coins", false)
        eco.addAttachment(harness.plugin, "geckonomy.balance.coins", false)
        val bob = harness.player("Bob")
        givenCoins(AccountId(bob.uniqueId), "Bob", "0.00")

        harness.dispatch(eco, "eco give Bob 100")

        assertEquals("[Geckonomy] Gave \$100.00 to Bob.", harness.nextMessage(eco))
    }

    @Test
    fun `refuses an amount that is not a number`() {
        val eco = admin()
        harness.player("Bob")

        harness.dispatch(eco, "eco give Bob heaps")

        assertEquals("[Geckonomy] Invalid amount.", harness.nextMessage(eco))
    }

    @Test
    fun `refuses an unknown currency`() {
        val eco = admin()
        harness.player("Bob")

        harness.dispatch(eco, "eco give Bob 5 unobtainium")

        assertEquals("[Geckonomy] Unknown currency: unobtainium.", harness.nextMessage(eco))
    }

    @Test
    fun `reports a player nobody has heard of`() {
        val eco = admin()

        harness.dispatch(eco, "eco give Ghost 5")

        assertEquals("[Geckonomy] No player named Ghost.", harness.nextMessage(eco))
    }

    @Test
    fun `says nothing to a player without the admin node`() {
        val alice = harness.player("Alice")
        val bob = harness.player("Bob")
        givenCoins(AccountId(bob.uniqueId), "Bob", "0.00")

        harness.dispatch(alice, "eco give Bob 1000")

        assertEquals(emptyList<String>(), harness.messages(alice).filter { it.startsWith("[Geckonomy]") })
        assertEquals(BigDecimal("0.00"), balanceOf(AccountId(bob.uniqueId)))
    }
}
