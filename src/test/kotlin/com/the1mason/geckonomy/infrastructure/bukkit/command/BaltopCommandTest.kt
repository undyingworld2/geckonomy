package com.the1mason.geckonomy.infrastructure.bukkit.command

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.AccountId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/** `/baltop [currency]` end to end (`docs/tasks/M7-commands.md`). */
class BaltopCommandTest {

    private val fixture = EconomyFixture()
    private val harness = CommandHarness(fixture)

    @AfterEach
    fun tearDown() = harness.unmock()

    private fun givenHolder(name: String, amount: String) = runBlocking {
        val id = AccountId(UUID.randomUUID())
        fixture.givenAccount(id, name)
        fixture.balances.set(id, TestCurrencies.COINS, BigDecimal(amount))
    }

    @Test
    fun `lists the richest first, ranked`() {
        val alice = harness.player("Alice")
        givenHolder("Rich", "900.00")
        givenHolder("Poor", "1.00")
        givenHolder("Middle", "50.00")

        harness.dispatch(alice, "baltop")

        assertEquals(
            listOf(
                "[Geckonomy] Top balances (Coins)",
                "1. Rich — \$900.00",
                "2. Middle — \$50.00",
                "3. Poor — \$1.00",
            ),
            harness.messages(alice),
        )
    }

    @Test
    fun `honours baltop-size`() {
        val alice = harness.player("Alice")
        harness.baltopSize = 2
        givenHolder("Rich", "900.00")
        givenHolder("Poor", "1.00")
        givenHolder("Middle", "50.00")

        harness.dispatch(alice, "baltop")

        assertEquals(3, harness.messages(alice).size) // header + 2 rows
    }

    @Test
    fun `says so when nobody holds any`() {
        val alice = harness.player("Alice")

        harness.dispatch(alice, "baltop")

        assertEquals("[Geckonomy] Nobody has any Coins yet.", harness.nextMessage(alice))
    }

    @Test
    fun `refuses a currency the player lacks the per-currency node for`() {
        val alice = harness.player("Alice")
        alice.addAttachment(harness.plugin, "geckonomy.baltop.gems", false)

        harness.dispatch(alice, "baltop gems")

        assertEquals("[Geckonomy] You can't use Gems here.", harness.nextMessage(alice))
    }

    @Test
    fun `says nothing without the base permission`() {
        val stranger = harness.strangerWithNoPermissions()

        harness.dispatch(stranger, "baltop")

        assertEquals(emptyList<String>(), harness.messages(stranger).filter { it.startsWith("[Geckonomy]") })
    }
}
