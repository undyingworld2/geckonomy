package com.the1mason.geckonomy.infrastructure.bukkit.listener

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import com.the1mason.geckonomy.infrastructure.balance.OnlineBalanceMirror
import com.the1mason.geckonomy.infrastructure.config.StorageType
import com.the1mason.geckonomy.infrastructure.i18n.LogCapture
import com.the1mason.geckonomy.infrastructure.vault.VaultSyncPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import java.math.BigDecimal
import java.net.InetAddress
import java.sql.SQLException
import java.util.logging.Level

/**
 * Join/quit against the real event objects.
 *
 * `AsyncPlayerPreLoginEvent` is constructed directly rather than driven through a `ServerMock` login:
 * the listener's contract is "given this event, do this", and building the event says that without a
 * whole simulated handshake in between.
 */
class PlayerConnectionListenerTest {

    private val fixture = EconomyFixture()
    private val mirror = OnlineBalanceMirror()
    private val log = LogCapture()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val sync = VaultSyncPath(fixture.service, mirror, scope, StorageType.SQLITE, log.logger)
    private val listener = PlayerConnectionListener(fixture.service, sync, mirror, log.logger)

    /** MockBukkit is needed only because the event's constructor reaches for `Bukkit.createProfile`. */
    @BeforeEach
    fun start() {
        MockBukkit.mock()
    }

    @AfterEach
    fun stop() {
        MockBukkit.unmock()
    }

    @Suppress("DEPRECATION")
    private fun preLogin(name: String = "Alice", result: AsyncPlayerPreLoginEvent.Result? = null) =
        AsyncPlayerPreLoginEvent(name, InetAddress.getLoopbackAddress(), ALICE.value, false)
            .apply { if (result != null) disallow(result, Component.text("no")) }

    @Test
    fun `a first join creates the account and seeds every starting balance`() {
        listener.onPreLogin(preLogin())

        assertTrue(runBlocking { fixture.service.exists(ALICE) } is Outcome.Success)
        assertEquals(TestCurrencies.COINS.startingBalance, mirror.get(ALICE, TestCurrencies.COINS.code))
        assertEquals(BigDecimal("0"), mirror.get(ALICE, TestCurrencies.GEMS.code))
    }

    @Test
    fun `the mirror is warm before the player exists to anyone else`() {
        // The point of doing this at pre-login: a plugin asking on join must not hit the fallback.
        listener.onPreLogin(preLogin())

        assertTrue(mirror.isMirrored(ALICE))
    }

    @Test
    fun `a returning player keeps their balance`() {
        runBlocking {
            fixture.givenAccount(ALICE, "Alice")
            fixture.service.deposit(ALICE, BigDecimal("42.00"), TestCurrencies.COINS.code)
        }

        listener.onPreLogin(preLogin())

        assertEquals(BigDecimal("42.00"), mirror.get(ALICE, TestCurrencies.COINS.code))
    }

    @Test
    fun `a renamed player has their account name updated`() {
        runBlocking { fixture.givenAccount(ALICE, "OldName") }

        listener.onPreLogin(preLogin("Alice"))

        assertEquals("Alice", (runBlocking { fixture.service.name(ALICE) } as Outcome.Success).value)
    }

    @Test
    fun `a disallowed login is ignored entirely`() {
        listener.onPreLogin(preLogin(result = AsyncPlayerPreLoginEvent.Result.KICK_BANNED))

        assertFalse(mirror.isMirrored(ALICE))
        assertTrue(runBlocking { fixture.service.exists(ALICE) }.let { it is Outcome.Success && !it.value })
    }

    @Test
    fun `a database failure at join warns but does not refuse the login`() {
        // Refusing entry because the economy is sick would be a worse outage than a missing balance:
        // the sync path falls back to reading the database directly until it recovers.
        fixture.accounts.failWith = SQLException("connection reset")

        listener.onPreLogin(preLogin())

        assertTrue(log.warnings().any { it.contains("could not prepare an account") }, "${log.warnings()}")
    }

    /**
     * The other half of the one above: not a *reported* failure but a thrown one, from the part of
     * hydration that has no guard behind it. Whatever it is, it must not reach Bukkit — this runs
     * inside the login dispatch, and a player kept out of the server because a cache would not warm is
     * the worst trade in the plugin.
     */
    @Test
    fun `a throw while warming the mirror does not escape into the login`() {
        val broken = EconomyFixture(currencies = object : CurrencyRegistry {
            override fun all(): Collection<Currency> = throw IllegalStateException("registry is unhappy")
            override fun default(): Currency = TestCurrencies.COINS
            override fun byCode(code: CurrencyCode): Currency = TestCurrencies.COINS
        })
        val brokenSync = VaultSyncPath(broken.service, mirror, scope, StorageType.SQLITE, log.logger)
        val listener = PlayerConnectionListener(broken.service, brokenSync, mirror, log.logger)

        assertDoesNotThrow { listener.onPreLogin(preLogin()) }

        val severe = log.records.single { it.level == Level.SEVERE }
        assertTrue(severe.message.contains("preparing Alice for login"), severe.message)
    }
}
