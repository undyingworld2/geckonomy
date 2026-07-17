package com.the1mason.geckonomy.infrastructure.placeholder

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.infrastructure.i18n.LogCapture
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.logging.Logger
import kotlin.time.Duration.Companion.seconds

/**
 * The leaderboard cache and its timer.
 *
 * Virtual time throughout (CODING_STANDARDS §6) — a 60s refresh cannot be tested with real sleeps.
 * The first virtual-time suite in the repo; the persistence suites stay on `runBlocking` because
 * real queries have no time to skip.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BaltopSnapshotTest {

    private val logger: Logger = PlaceholderFixture.silent()

    private suspend fun EconomyFixture.richAccount(id: com.the1mason.geckonomy.domain.model.AccountId, name: String, coins: String) {
        givenAccount(id, name)
        service.deposit(id, coins.toBigDecimal(), PlaceholderFixture.COINS)
    }

    private fun snapshot(economy: EconomyFixture, size: () -> Int = { 10 }, log: Logger = logger) = BaltopSnapshot(
        economy = economy.service,
        currencies = economy.currencies,
        size = size,
        interval = { 60.seconds },
        logger = log,
    )

    @Test
    fun `serves an empty snapshot before the first refresh`() = runTest {
        val economy = EconomyFixture()
        economy.richAccount(EconomyFixture.ALICE, "Alice", "500")
        val baltop = snapshot(economy)

        // Started but not yet run: nothing to serve, and the resolver renders the fallback.
        assertNull(baltop.at(PlaceholderFixture.COINS, 1))
        assertNull(baltop.rankOf(PlaceholderFixture.COINS, EconomyFixture.ALICE))
    }

    @Test
    fun `ranks richest first`() = runTest {
        val economy = EconomyFixture()
        economy.richAccount(EconomyFixture.ALICE, "Alice", "100")
        economy.richAccount(EconomyFixture.BOB, "Bob", "900")
        val baltop = snapshot(economy)
        baltop.refreshAll()

        assertEquals("Bob", baltop.at(PlaceholderFixture.COINS, 1)?.name)
        assertEquals("Alice", baltop.at(PlaceholderFixture.COINS, 2)?.name)
        assertEquals(1, baltop.rankOf(PlaceholderFixture.COINS, EconomyFixture.BOB))
        assertEquals(2, baltop.rankOf(PlaceholderFixture.COINS, EconomyFixture.ALICE))
    }

    @Test
    fun `a rank beyond the rows is null, not an index crash`() = runTest {
        val economy = EconomyFixture()
        economy.richAccount(EconomyFixture.ALICE, "Alice", "100")
        val baltop = snapshot(economy)
        baltop.refreshAll()

        // baltop-size is 10 but only one account exists: ListTopBalances returns a short list.
        assertNull(baltop.at(PlaceholderFixture.COINS, 2))
        assertNull(baltop.rankOf(PlaceholderFixture.COINS, EconomyFixture.BOB))
    }

    @Test
    fun `runs one query per currency per interval regardless of read count`() = runTest {
        val economy = EconomyFixture()
        economy.richAccount(EconomyFixture.ALICE, "Alice", "100")
        val baltop = snapshot(economy)
        // After the setup deposit, so this counts the loop's queries and not the fixture's.
        val before = economy.balances.calls

        val job = baltop.start(this)
        runCurrent()

        // Two currencies in the fixture registry, so one cycle is two top() calls.
        assertEquals(2, economy.balances.calls - before, "one top() per currency on the first cycle")

        val afterFirst = economy.balances.calls
        repeat(100) { baltop.at(PlaceholderFixture.COINS, 1) }
        assertEquals(afterFirst, economy.balances.calls, "reads are free - they must not query")

        advanceTimeBy(61.seconds)
        runCurrent()
        assertEquals(4, economy.balances.calls - before, "exactly one more cycle after one interval")

        job.cancel()
    }

    @Test
    fun `a failed refresh keeps the last good snapshot rather than blanking it`() = runTest {
        val economy = EconomyFixture()
        economy.richAccount(EconomyFixture.ALICE, "Alice", "500")
        val baltop = snapshot(economy)
        baltop.refreshAll()
        assertEquals("Alice", baltop.at(PlaceholderFixture.COINS, 1)?.name)

        economy.balances.failWith = SQLException("the database blinked")
        baltop.refreshAll()

        // Stale beats blank: a scoreboard flashing the fallback on every hiccup is worse.
        assertEquals("Alice", baltop.at(PlaceholderFixture.COINS, 1)?.name)
    }

    /**
     * The `launchGuarded` trap in M9 shape.
     *
     * A throw here would reach the scope's `SupervisorJob`, which cancels this one child and logs
     * nothing — the snapshot would freeze at its last value forever and serve it to every scoreboard,
     * with no line anywhere connecting a stale leaderboard to the night it stopped moving.
     *
     * The throw has to come from the **supplier**, not the ports: `StorageGuard` catches `Exception`
     * wholesale, so no use-case call can throw at all, and `ListTopBalances` only leaves
     * `amounts.currency()` and `size()` outside its guard. M8 found the same edge from the other
     * side — `CommandFailureTest` throws from this very supplier.
     */
    @Test
    fun `a throwing size supplier is caught, logged, and the loop survives`() = runTest {
        val economy = EconomyFixture()
        economy.richAccount(EconomyFixture.ALICE, "Alice", "500")
        val capture = LogCapture()
        var explode = false
        val baltop = snapshot(economy, size = { if (explode) throw IllegalStateException("boom") else 10 }, log = capture.logger)

        val job = baltop.start(this)
        runCurrent()
        assertEquals("Alice", baltop.at(PlaceholderFixture.COINS, 1)?.name)

        explode = true
        advanceTimeBy(61.seconds)
        runCurrent()

        assertTrue(job.isActive, "the refresh loop must survive a throw, or the snapshot freezes forever")
        assertTrue(
            capture.warnings().any { "leaderboard snapshot" in it },
            "a swallowed throw must at least say so: ${capture.warnings()}",
        )
        // Stale, not blank: the last good rows are still what a scoreboard reads.
        assertEquals("Alice", baltop.at(PlaceholderFixture.COINS, 1)?.name)

        // And it recovers on the next cycle.
        explode = false
        advanceTimeBy(61.seconds)
        runCurrent()
        assertNotNull(baltop.at(PlaceholderFixture.COINS, 1))
        job.cancel()
    }

    @Test
    fun `baltop-size is read per call, so a reload applies`() = runTest {
        val economy = EconomyFixture()
        economy.richAccount(EconomyFixture.ALICE, "Alice", "100")
        economy.richAccount(EconomyFixture.BOB, "Bob", "900")
        var size = 1
        val baltop = snapshot(economy, size = { size })

        baltop.refreshAll()
        assertNull(baltop.at(PlaceholderFixture.COINS, 2), "size 1 holds one row")

        size = 10
        baltop.refreshAll()
        assertEquals("Alice", baltop.at(PlaceholderFixture.COINS, 2)?.name)
    }
}
