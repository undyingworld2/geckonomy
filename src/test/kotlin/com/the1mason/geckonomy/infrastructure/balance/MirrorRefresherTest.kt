package com.the1mason.geckonomy.infrastructure.balance

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.infrastructure.i18n.LogCapture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.SQLException
import kotlin.time.Duration.Companion.seconds

class MirrorRefresherTest {

    private val fixture = EconomyFixture()
    private val mirror = OnlineBalanceMirror()
    private val refresher = MirrorRefresher(fixture.service, mirror, { 60.seconds }, LogCapture().logger)

    private val coins = TestCurrencies.COINS
    private val gems = TestCurrencies.GEMS

    @Test
    fun `picks up a change no write path reported`() = runBlocking {
        fixture.givenAccount(ALICE)
        mirror.hydrate(ALICE, mapOf(coins.code to BigDecimal("100.00")))
        // Another server, or the async API: the database moved and the mirror was not told.
        fixture.balances.set(ALICE, coins, BigDecimal("40.00"))

        refresher.refreshAll()

        assertEquals(BigDecimal("40.00"), mirror.get(ALICE, coins.code))
    }

    @Test
    fun `fills a currency the mirror was missing`() = runBlocking {
        fixture.givenAccount(ALICE)
        mirror.hydrate(ALICE, emptyMap())
        fixture.balances.set(ALICE, gems, BigDecimal("7"))

        refresher.refreshAll()

        assertEquals(BigDecimal("7"), mirror.get(ALICE, gems.code))
    }

    @Test
    fun `leaves offline players alone`() = runBlocking {
        fixture.givenAccount(BOB)

        refresher.refreshAll()

        assertFalse(mirror.isMirrored(BOB))
        assertEquals(0, fixture.balances.calls)
    }

    @Test
    fun `a failed read keeps the last good value`() = runBlocking {
        fixture.givenAccount(ALICE)
        mirror.hydrate(ALICE, mapOf(coins.code to BigDecimal("100.00")))
        fixture.balances.failWith = SQLException("connection reset")

        refresher.refreshAll()

        assertEquals(BigDecimal("100.00"), mirror.get(ALICE, coins.code))
    }
}
