package com.the1mason.geckonomy.infrastructure.bukkit.command

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.infrastructure.bukkit.CurrencyAccess.Action
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.bukkit.permissions.PermissionDefault
import java.math.BigDecimal

/**
 * The per-currency nodes, and the trap they exist for.
 *
 * An unregistered permission node defaults to **op**, so without registration every per-currency check
 * would refuse every normal player — while working perfectly for the admin running the live smoke.
 * These tests are the reason that bug cannot ship.
 */
class GeckonomyPermissionsTest {

    private val fixture = EconomyFixture()
    private val harness = CommandHarness(fixture)

    @AfterEach
    fun tearDown() = harness.unmock()

    @Test
    fun `registers a node per currency per action, defaulting to true`() {
        val registered = harness.server.pluginManager.getPermission(Action.PAY.node(com.the1mason.geckonomy.domain.TestCurrencies.COINS))

        assertNotNull(registered, "geckonomy.pay.coins should be registered")
        assertEquals(PermissionDefault.TRUE, registered!!.default)
    }

    /** The wildcard grants its children only because it is registered holding them. */
    @Test
    fun `registers wildcard parents holding every currency node`() {
        val wildcard = harness.server.pluginManager.getPermission(Action.PAY.wildcard)

        assertNotNull(wildcard, "geckonomy.pay.* should be registered")
        assertEquals(PermissionDefault.OP, wildcard!!.default, "the wildcard is a deliberate grant, not a default")
        assertTrue(wildcard.children.keys.contains("geckonomy.pay.coins"))
        assertTrue(wildcard.children.keys.contains("geckonomy.pay.gems"))
    }

    /**
     * The whole point: a **non-op** must pass a per-currency node. Run as an op, every one of these
     * would pass whether or not the nodes were ever registered.
     */
    @Test
    fun `a non-op passes the per-currency nodes`() {
        val alice = harness.player("Alice")
        assertFalse(alice.isOp, "this test is meaningless if the player is an op")
        runBlocking {
            fixture.givenAccount(AccountId(alice.uniqueId), "Alice")
            fixture.balances.set(AccountId(alice.uniqueId), com.the1mason.geckonomy.domain.TestCurrencies.COINS, BigDecimal("5.00"))
        }

        harness.dispatch(alice, "balance")

        assertEquals("[Geckonomy] Balance: \$5.00", harness.nextMessage(alice))
    }

    @Test
    fun `unregisters every node it added`() {
        val permissions = GeckonomyPermissions(harness.server.pluginManager, harness.currencies)
        permissions.register()

        permissions.unregister()

        assertNull(harness.server.pluginManager.getPermission(Action.PAY.wildcard))
    }

    /** Re-registering must not throw: `addPermission` rejects a duplicate, and a reload calls it again. */
    @Test
    fun `registering twice is idempotent`() {
        val permissions = GeckonomyPermissions(harness.server.pluginManager, harness.currencies)

        permissions.register()
        permissions.register()

        assertNotNull(harness.server.pluginManager.getPermission(Action.PAY.wildcard))
    }
}
