package com.the1mason.geckonomy.infrastructure.placeholder

import com.the1mason.geckonomy.domain.model.CurrencyCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What each placeholder renders, over a real economy on in-memory ports. */
class PlaceholderResolverTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val gems = CurrencyCode("gems")

    private fun online(amount: String, currency: CurrencyCode = PlaceholderFixture.COINS) =
        PlaceholderFixture(scope = scope).apply { online(PlaceholderFixture.ALICE, currency, amount) }

    @Test
    fun `currency metadata needs no player`() {
        val f = PlaceholderFixture(scope = scope)

        assertEquals("$", f.resolve("symbol", id = null))
        assertEquals("Coin", f.resolve("name", id = null))
        assertEquals("Coin", f.resolve("name_singular", id = null))
        assertEquals("Coins", f.resolve("name_plural", id = null))
        assertEquals("2", f.resolve("digits", id = null))
    }

    @Test
    fun `balance renders raw, formatted, grouped and truncated`() {
        val f = online("1234.5")

        assertEquals("1234.5", f.resolve("balance"))
        assertEquals("1234.5", f.resolve("balance_raw"))
        assertEquals("$1,234.50", f.resolve("balance_formatted"))
        assertEquals("1,234.50", f.resolve("balance_commas"))
        assertEquals("1234", f.resolve("balance_fixed"))
    }

    @Test
    fun `fixed truncates rather than rounds, so it never shows money that cannot be spent`() {
        assertEquals("1", online("1.99").resolve("balance_fixed"))
    }

    @Test
    fun `balance_name agrees with the balance`() {
        assertEquals("Coin", online("1").resolve("balance_name"))
        assertEquals("Coins", online("2").resolve("balance_name"))
        assertEquals("Coins", online("0").resolve("balance_name"))
        // The case Currency.nameFor exists for: 1.00 is one coin, and must not read "Coins".
        assertEquals("Coin", online("1.00").resolve("balance_name"))
    }

    @Test
    fun `a balance of exactly one renders 1 Gem and not 1_00 Gems`() {
        assertEquals("1 Gem", online("1", gems).resolve("balance_formatted_gems"))
    }

    @Test
    fun `a large balance never renders in scientific notation`() {
        // toString() would give 1E+11 here; a scoreboard showing that is the bug this pins.
        assertEquals("100000000000", online("1E+11").resolve("balance"))
    }

    @Test
    fun `format renders an arbitrary amount through the same formatter a command uses`() {
        val f = PlaceholderFixture(scope = scope)

        assertEquals("$1,000.00", f.resolve("format_1000"))
        assertEquals("$0.50", f.resolve("format_0.5"))
        assertEquals("5 Gems", f.resolve("format_5_gems"))
        assertEquals("1 Gem", f.resolve("format_1_gems"))
    }

    @Test
    fun `an unknown currency renders null rather than the default currency`() {
        assertNull(PlaceholderFixture(scope = scope).resolve("balance_doubloons"))
    }

    /**
     * FR-P7, and the one thing a later refactor could silently break.
     *
     * The assertion that matters is the call count: a resolver that "just reads the balance" would
     * pass every other test in this file and spend a tick every ~500 lookups on a real tab list.
     */
    @Test
    fun `an offline player costs no port call on the calling thread`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val papi = TestScope(dispatcher)
        val f = PlaceholderFixture(scope = papi)
        f.economy.givenAccount(PlaceholderFixture.ALICE)
        f.economy.service.deposit(PlaceholderFixture.ALICE, "50".toBigDecimal(), PlaceholderFixture.COINS)
        val before = f.economy.balances.calls

        // Not mirrored: Alice is offline. The render must return without touching a port.
        assertEquals("0", f.resolve("balance"))
        assertEquals(before, f.economy.balances.calls, "the render itself must make no port call")

        // The fill lands behind it, and the next render tells the truth.
        testScheduler.runCurrent()
        assertEquals("50.00", f.resolve("balance"))
        assertTrue(f.economy.balances.calls > before, "the refresh should have run off-thread")
    }

    @Test
    fun `an offline player renders the configured fallback until the fill lands`() = runTest {
        val f = PlaceholderFixture(scope = TestScope(StandardTestDispatcher(testScheduler)))
        f.fallback = "?"
        f.economy.givenAccount(PlaceholderFixture.ALICE)

        assertEquals("?", f.resolve("balance"))
    }

    @Test
    fun `the mirror wins over the offline cache for an online player`() = runTest {
        val f = PlaceholderFixture(scope = TestScope(StandardTestDispatcher(testScheduler)))
        f.economy.givenAccount(PlaceholderFixture.ALICE)
        f.economy.service.deposit(PlaceholderFixture.ALICE, "50".toBigDecimal(), PlaceholderFixture.COINS)
        f.online(PlaceholderFixture.ALICE, PlaceholderFixture.COINS, "7")
        val before = f.economy.balances.calls

        assertEquals("7", f.resolve("balance"))
        assertEquals(before, f.economy.balances.calls, "a mirrored account must not reach the cache")
    }

    @Test
    fun `a null player renders the fallback for a balance and nothing for a rank`() {
        val f = PlaceholderFixture(scope = scope)

        assertEquals("0", f.resolve("balance", id = null))
        assertEquals("0", f.resolve("baltop_rank", id = null))
    }
}
