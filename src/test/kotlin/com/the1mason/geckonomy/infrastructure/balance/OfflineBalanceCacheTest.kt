package com.the1mason.geckonomy.infrastructure.balance

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.domain.model.CurrencyCode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The offline half of the placeholder read path (SPEC.md FR-P7).
 *
 * The rule under test is one sentence: **[OfflineBalanceCache.get] never queries.** It answers from
 * memory and fills behind the caller, so a tab list of offline players costs the main thread nothing.
 */
class OfflineBalanceCacheTest {

    private val coins = CurrencyCode("coins")
    private var now = 0L

    private fun cache(economy: EconomyFixture, scope: TestScope, ttl: Duration = 60.seconds) =
        OfflineBalanceCache(
            economy = economy.service,
            scope = scope,
            ttl = { ttl },
            logger = Logger.getAnonymousLogger().apply { level = Level.OFF },
            nanos = { now },
        )

    private suspend fun EconomyFixture.accountWith(coins: String): EconomyFixture {
        givenAccount(EconomyFixture.ALICE, "Alice")
        service.deposit(EconomyFixture.ALICE, coins.toBigDecimal(), CurrencyCode("coins"))
        return this
    }

    @Test
    fun `the first get answers null without querying, then fills behind it`() = runTest {
        val economy = EconomyFixture().accountWith("50")
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val cache = cache(economy, scope)
        val before = economy.balances.calls

        assertNull(cache.get(EconomyFixture.ALICE, coins), "nothing is known yet")
        assertEquals(before, economy.balances.calls, "get() must not query on the calling thread")

        testScheduler.runCurrent()
        assertEquals("50.00", cache.get(EconomyFixture.ALICE, coins)?.toPlainString())
    }

    @Test
    fun `concurrent gets queue one refresh, not sixty`() = runTest {
        val economy = EconomyFixture().accountWith("50")
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val cache = cache(economy, scope)
        val before = economy.balances.calls

        // A scoreboard rendering the same placeholder for sixty viewers in one tick.
        repeat(60) { cache.get(EconomyFixture.ALICE, coins) }
        testScheduler.runCurrent()

        assertEquals(1, economy.balances.calls - before, "the in-flight guard should collapse these to one")
    }

    @Test
    fun `a cached value is reused until the ttl expires`() = runTest {
        val economy = EconomyFixture().accountWith("50")
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val cache = cache(economy, scope, ttl = 60.seconds)

        cache.get(EconomyFixture.ALICE, coins)
        testScheduler.runCurrent()
        val afterFill = economy.balances.calls

        now += 30.seconds.inWholeNanoseconds
        cache.get(EconomyFixture.ALICE, coins)
        testScheduler.runCurrent()
        assertEquals(afterFill, economy.balances.calls, "still fresh - no re-read")

        now += 31.seconds.inWholeNanoseconds
        cache.get(EconomyFixture.ALICE, coins)
        testScheduler.runCurrent()
        assertEquals(afterFill + 1, economy.balances.calls, "past the ttl - one re-read")
    }

    @Test
    fun `a stale value is still served while its refresh is in flight`() = runTest {
        val economy = EconomyFixture().accountWith("50")
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val cache = cache(economy, scope)

        cache.get(EconomyFixture.ALICE, coins)
        testScheduler.runCurrent()

        now += 61.seconds.inWholeNanoseconds
        // Stale, and the refresh has not run yet: the old number beats blinking to the fallback.
        assertEquals("50.00", cache.get(EconomyFixture.ALICE, coins)?.toPlainString())
    }

    @Test
    fun `sweep drops what nobody asked for and keeps what they did`() = runTest {
        val economy = EconomyFixture().accountWith("50")
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val cache = cache(economy, scope)

        cache.get(EconomyFixture.ALICE, coins)
        testScheduler.runCurrent()
        assertEquals(1, cache.size())

        // Asked recently: survives, or a tab list would evict its own working set.
        now += 30.seconds.inWholeNanoseconds
        cache.get(EconomyFixture.ALICE, coins)
        cache.sweep()
        assertEquals(1, cache.size())

        // Not asked for two ttls: gone, or the map grows with every player ever rendered.
        now += 121.seconds.inWholeNanoseconds
        cache.sweep()
        assertEquals(0, cache.size())
    }

    @Test
    fun `evict drops a player the mirror has taken over`() = runTest {
        val economy = EconomyFixture().accountWith("50")
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val cache = cache(economy, scope)

        cache.get(EconomyFixture.ALICE, coins)
        testScheduler.runCurrent()
        assertEquals(1, cache.size())

        cache.evict(EconomyFixture.ALICE)
        assertEquals(0, cache.size())
    }

    @Test
    fun `a failed read leaves the last good value rather than blanking it`() = runTest {
        val economy = EconomyFixture().accountWith("50")
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val cache = cache(economy, scope)

        cache.get(EconomyFixture.ALICE, coins)
        testScheduler.runCurrent()

        economy.balances.failWith = java.sql.SQLException("down")
        now += 61.seconds.inWholeNanoseconds
        cache.get(EconomyFixture.ALICE, coins)
        testScheduler.runCurrent()

        assertEquals("50.00", cache.get(EconomyFixture.ALICE, coins)?.toPlainString())
    }
}
