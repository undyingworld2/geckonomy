package com.the1mason.geckonomy.infrastructure.vault

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.application.EconomyFixture.Companion.CAROL
import com.the1mason.geckonomy.infrastructure.config.StorageType
import com.the1mason.geckonomy.infrastructure.i18n.LogCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.OfflinePlayerMock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Name resolution for the legacy API's deprecated `String playerName` overloads.
 *
 * The security-relevant assertion is what is *absent*: nothing here reaches
 * `Server.getOfflinePlayer(String)`, whose own javadoc admits to a blocking web request.
 */
class PlayerResolverTest {

    private lateinit var server: ServerMock

    private val fixture = EconomyFixture()
    private val log = LogCapture()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    /** Advanced by hand, so the ttl is tested rather than waited out. */
    private var now: Instant = Instant.parse("2026-07-17T00:00:00Z")
    private val clock = object : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneOffset = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
    }

    private val sync by lazy {
        VaultSyncPath(fixture.service, OnlineBalanceMirror(), scope, StorageType.SQLITE, log.logger)
    }

    private fun resolver(ttl: Duration = Duration.ofMinutes(1)) = PlayerResolver(server, sync, clock, ttl)

    @BeforeEach
    fun start() {
        server = MockBukkit.mock()
    }

    @AfterEach
    fun stop() {
        MockBukkit.unmock()
    }

    @Test
    fun `an OfflinePlayer resolves by uuid, with no lookup at all`() {
        val player = OfflinePlayerMock(ALICE.value, "Alice")

        assertEquals(ALICE, resolver().resolve(player))
    }

    @Test
    fun `an online player resolves exactly`() {
        val player = server.addPlayer("Alice")

        assertEquals(player.uniqueId, resolver().resolve("Alice")?.value)
    }

    @Test
    fun `an offline player resolves from the usercache`() {
        server.playerList.addOfflinePlayer(OfflinePlayerMock(BOB.value, "Bob"))

        assertEquals(BOB, resolver().resolve("Bob"))
    }

    @Test
    fun `a player only we know of resolves from the account name map`() {
        // The usercache can be cleared; our accounts outlive it.
        runBlocking { fixture.givenAccount(CAROL, "Carol") }

        assertEquals(CAROL, resolver().resolve("Carol"))
    }

    @Test
    fun `the account name map is matched case-insensitively`() {
        runBlocking { fixture.givenAccount(CAROL, "Carol") }

        assertEquals(CAROL, resolver().resolve("carol"))
    }

    @Test
    fun `an unknown name resolves to null rather than a manufactured uuid`() {
        // getOfflinePlayer(name) would invent an OfflinePlayer here and the caller would act on it.
        assertNull(resolver().resolve("Nobody"))
    }

    @Test
    fun `the name map is not re-read on every miss`() {
        runBlocking { fixture.givenAccount(CAROL, "Carol") }
        val resolver = resolver(ttl = Duration.ofMinutes(1))
        resolver.resolve("Carol")

        // A new account inside the ttl window stays invisible — harmless, because gaining an account
        // means joining, and a player who just joined is online and resolves before we get here.
        runBlocking { fixture.givenAccount(BOB, "Bob") }

        assertNull(resolver.resolve("Bob"))
    }

    @Test
    fun `the name map is re-read once the ttl expires`() {
        runBlocking { fixture.givenAccount(CAROL, "Carol") }
        val resolver = resolver(ttl = Duration.ofMinutes(1))
        resolver.resolve("Carol")
        runBlocking { fixture.givenAccount(BOB, "Bob") }

        now = now.plus(Duration.ofMinutes(2))

        assertEquals(BOB, resolver.resolve("Bob"))
    }

    @Test
    fun `an online player wins over a stale usercache entry`() {
        // The same name under a different uuid: an offline-mode server renaming, or a cache that is
        // simply out of date. The live session is the truth.
        server.playerList.addOfflinePlayer(OfflinePlayerMock(BOB.value, "Alice"))
        val online = server.addPlayer("Alice")

        assertEquals(online.uniqueId, resolver().resolve("Alice")?.value)
    }
}
