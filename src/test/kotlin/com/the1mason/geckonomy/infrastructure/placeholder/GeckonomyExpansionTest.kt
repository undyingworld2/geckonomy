package com.the1mason.geckonomy.infrastructure.placeholder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * The PAPI-facing shell.
 *
 * A spike settled that this is testable at all: unlike M7's Cloud command managers, whose
 * constructors reflect into NMS, `PlaceholderExpansion`'s constructor only sets a field — so the
 * expansion can be built with no PlaceholderAPI plugin running. Only `register()` cannot be tested,
 * because it reaches for `PlaceholderAPIPlugin.getInstance()`.
 */
class GeckonomyExpansionTest {

    private lateinit var server: ServerMock
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun expansion(): Pair<GeckonomyExpansion, PlaceholderFixture> {
        val fixture = PlaceholderFixture(scope = scope)
        return GeckonomyExpansion(fixture.resolver, version = "1.0.0", author = "the1mason") to fixture
    }

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() = MockBukkit.unmock()

    @Test
    fun `identifies as geckonomy`() {
        assertEquals("geckonomy", expansion().first.identifier)
    }

    /**
     * The one the M9 doc predicted would be wrong.
     *
     * PlaceholderAPI unregisters a non-persistent expansion on `/papi reload`, and nothing would ever
     * register ours again short of a restart — so every placeholder on the server would silently go
     * back to raw text the first time an admin reloaded.
     */
    @Test
    fun `persists across a papi reload`() {
        assertTrue(expansion().first.persist(), "a plugin-registered expansion must survive /papi reload")
    }

    @Test
    fun `advertises the whole table`() {
        val advertised = expansion().first.placeholders

        assertEquals(PlaceholderVariant.entries.size, advertised.size)
        assertTrue("%geckonomy_balance[_<currency>]%" in advertised)
        assertTrue("%geckonomy_baltop_player_<n>[_<currency>]%" in advertised)
        assertTrue("%geckonomy_format_<amount>[_<currency>]%" in advertised)
    }

    @Test
    fun `delegates to the resolver and holds no logic of its own`() {
        val (expansion, fixture) = expansion()
        val alice = server.addPlayer("Alice")
        fixture.online(com.the1mason.geckonomy.domain.model.AccountId(alice.uniqueId), PlaceholderFixture.COINS, "42")

        assertEquals("42", expansion.onRequest(alice, "balance"))
        assertNull(expansion.onRequest(alice, "not_a_placeholder"))
    }

    @Test
    fun `a null player still answers currency metadata`() {
        assertEquals("$", expansion().first.onRequest(null, "symbol"))
    }

    /**
     * `onRequest`, not `onPlaceholderRequest`.
     *
     * `PlaceholderHook.onRequest` passes `null` to `onPlaceholderRequest` whenever the player is
     * offline, throwing away *which* player was asked about. Had the expansion overridden that one
     * instead, every offline placeholder would be unanswerable — which is most of a tab list.
     */
    @Test
    fun `an offline player keeps its identity through onRequest`() {
        val (expansion, fixture) = expansion()
        val offline = server.getOfflinePlayer(java.util.UUID.fromString(PlaceholderFixture.ALICE.value.toString()))
        fixture.online(PlaceholderFixture.ALICE, PlaceholderFixture.COINS, "13")

        assertEquals("13", expansion.onRequest(offline, "balance"))
    }
}
