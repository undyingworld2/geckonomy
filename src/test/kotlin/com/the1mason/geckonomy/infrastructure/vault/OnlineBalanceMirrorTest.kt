package com.the1mason.geckonomy.infrastructure.vault

import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.domain.TestCurrencies
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OnlineBalanceMirrorTest {

    private val coins = TestCurrencies.COINS.code
    private val gems = TestCurrencies.GEMS.code

    @Test
    fun `hydrate then get returns the mirrored balance`() {
        val mirror = OnlineBalanceMirror()

        mirror.hydrate(ALICE, mapOf(coins to BigDecimal("100.00"), gems to BigDecimal("5")))

        assertEquals(BigDecimal("100.00"), mirror.get(ALICE, coins))
        assertEquals(BigDecimal("5"), mirror.get(ALICE, gems))
    }

    @Test
    fun `an unmirrored account reads null rather than zero`() {
        // The distinction is the whole point: null means "ask the database", zero means "they're broke".
        // Collapsing the two would report every offline player as having nothing.
        val mirror = OnlineBalanceMirror()

        assertNull(mirror.get(ALICE, coins))
        assertFalse(mirror.isMirrored(ALICE))
    }

    @Test
    fun `a currency absent from a mirrored account reads null`() {
        val mirror = OnlineBalanceMirror()
        mirror.hydrate(ALICE, mapOf(coins to BigDecimal("100.00")))

        assertNull(mirror.get(ALICE, gems))
        assertTrue(mirror.isMirrored(ALICE))
    }

    @Test
    fun `evict drops the account`() {
        val mirror = OnlineBalanceMirror()
        mirror.hydrate(ALICE, mapOf(coins to BigDecimal("100.00")))

        mirror.evict(ALICE)

        assertNull(mirror.get(ALICE, coins))
        assertFalse(mirror.isMirrored(ALICE))
    }

    @Test
    fun `put after evict does not resurrect the account`() {
        // A write dispatched just before a quit lands just after it. If put recreated the account's
        // map, the entry would outlive the player and never be evicted — eviction already happened.
        val mirror = OnlineBalanceMirror()
        mirror.hydrate(ALICE, mapOf(coins to BigDecimal("100.00")))
        mirror.evict(ALICE)

        mirror.put(ALICE, coins, BigDecimal("50.00"))

        assertFalse(mirror.isMirrored(ALICE))
        assertNull(mirror.get(ALICE, coins))
    }

    @Test
    fun `hydrate replaces rather than merges`() {
        // A rejoin must not inherit a stale currency from the previous session.
        val mirror = OnlineBalanceMirror()
        mirror.hydrate(ALICE, mapOf(coins to BigDecimal("100.00"), gems to BigDecimal("5")))

        mirror.hydrate(ALICE, mapOf(coins to BigDecimal("7.00")))

        assertEquals(BigDecimal("7.00"), mirror.get(ALICE, coins))
        assertNull(mirror.get(ALICE, gems))
    }

    @Test
    fun `accounts are independent`() {
        val mirror = OnlineBalanceMirror()
        mirror.hydrate(ALICE, mapOf(coins to BigDecimal("100.00")))
        mirror.hydrate(BOB, mapOf(coins to BigDecimal("2.00")))

        mirror.evict(ALICE)

        assertEquals(BigDecimal("2.00"), mirror.get(BOB, coins))
    }

    @Test
    fun `snapshot is a copy, not a live view`() {
        val mirror = OnlineBalanceMirror()
        mirror.hydrate(ALICE, mapOf(coins to BigDecimal("100.00")))

        val snapshot = mirror.snapshot(ALICE)
        mirror.put(ALICE, coins, BigDecimal("1.00"))

        assertEquals(BigDecimal("100.00"), snapshot?.get(coins))
    }
}
