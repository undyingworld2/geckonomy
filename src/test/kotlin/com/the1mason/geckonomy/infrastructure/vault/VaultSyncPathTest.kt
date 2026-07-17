package com.the1mason.geckonomy.infrastructure.vault

import com.the1mason.geckonomy.application.Attribution
import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.infrastructure.balance.OnlineBalanceMirror
import com.the1mason.geckonomy.infrastructure.config.StorageType
import com.the1mason.geckonomy.infrastructure.i18n.LogCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.SQLException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration

/**
 * The mirror rules and the main-thread bridge.
 *
 * `Dispatchers.Unconfined` for the plugin scope so a refresh launched behind a read has already run
 * by the time the test asserts — the fakes never really suspend, so there is nothing to wait for and
 * nothing to make flaky.
 */
class VaultSyncPathTest {

    private val fixture = EconomyFixture()
    private val mirror = OnlineBalanceMirror()
    private val log = LogCapture()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val coins = TestCurrencies.COINS
    private val gems = TestCurrencies.GEMS

    private fun path(storage: StorageType = StorageType.SQLITE, timeout: kotlin.time.Duration = 2.seconds) =
        VaultSyncPath(fixture.service, mirror, scope, storage, log.logger, timeout)

    @Test
    fun `a mirrored read never asks the database`() {
        // The mirror deliberately disagrees with storage. Reading the mirrored value is the proof that
        // no query happened — this is the path that keeps DB IO off the main thread (NFR-1).
        runBlocking { fixture.givenAccount(ALICE) }
        mirror.hydrate(ALICE, mapOf(coins.code to BigDecimal("42.00")))

        assertEquals(BigDecimal("42.00"), path().balance(ALICE, coins))
    }

    @Test
    fun `an unmirrored read falls back to the database`() {
        runBlocking {
            fixture.givenAccount(ALICE)
            fixture.service.deposit(ALICE, BigDecimal("25.00"), coins.code)
        }

        assertEquals(BigDecimal("25.00"), path().balance(ALICE, coins))
    }

    @Test
    fun `says out loud when the main thread went to storage`() {
        // M8 asks for this by name. The cost of a fallback is invisible from the outside — it shows up
        // as a stutter, and this line is the only thing connecting the two.
        runBlocking { fixture.givenAccount(ALICE) }

        path().balance(ALICE, coins)

        val warning = log.warnings().single()
        assertTrue(warning.contains(coins.code.value), warning)
        assertTrue(warning.contains(ALICE.value.toString()), warning)
    }

    @Test
    fun `does not warn about a mirrored read`() {
        mirror.hydrate(ALICE, mapOf(coins.code to BigDecimal("42.00")))

        path().balance(ALICE, coins)

        assertTrue(log.warnings().isEmpty(), "the mirrored path is the normal one and must stay silent")
    }

    /**
     * The throttle is the difference between a hint and a reason to stop reading the console: a plugin
     * looping over offline players hits this path once per player.
     */
    @Test
    fun `throttles the fallback warning and counts what it swallowed`() {
        runBlocking { fixture.givenAccount(ALICE) }
        val path = path()

        repeat(50) { path.balance(ALICE, coins) }

        assertEquals(1, log.warnings().size, "fifty misses inside the window are one warning")
    }

    @Test
    fun `a read for an account that does not exist answers zero rather than throwing`() {
        // Vault's getBalance returns a BigDecimal. There is no way to say "no such account", and an
        // exception into a third-party plugin is never acceptable.
        assertEquals(BigDecimal.ZERO, path().balance(ALICE, coins))
    }

    @Test
    fun `on SQLite a network currency is not refreshed behind the read`() {
        // A SQLite file cannot be shared, so nothing else can have written it. The stale mirror value
        // survives, which is what proves no refresh was scheduled.
        runBlocking {
            fixture.givenAccount(ALICE)
            fixture.service.deposit(ALICE, BigDecimal("25.00"), coins.code)
        }
        mirror.hydrate(ALICE, mapOf(coins.code to BigDecimal("42.00")))

        val read = path(StorageType.SQLITE).balance(ALICE, coins)

        assertEquals(BigDecimal("42.00"), read)
        assertEquals(BigDecimal("42.00"), mirror.get(ALICE, coins.code))
    }

    @Test
    fun `on MariaDB a network currency is refreshed behind the read`() {
        // Another server sharing the database may have written it, so the mirror converges: this read
        // still answers from the mirror, and the next one is correct.
        runBlocking {
            fixture.givenAccount(ALICE)
            fixture.service.deposit(ALICE, BigDecimal("25.00"), coins.code)
        }
        mirror.hydrate(ALICE, mapOf(coins.code to BigDecimal("42.00")))
        val path = path(StorageType.MARIADB)

        assertEquals(BigDecimal("42.00"), path.balance(ALICE, coins), "the read itself must not block")

        assertEquals(BigDecimal("25.00"), mirror.get(ALICE, coins.code), "the refresh should have corrected it")
        assertEquals(BigDecimal("25.00"), path.balance(ALICE, coins))
    }

    @Test
    fun `on MariaDB a server-scoped currency is not refreshed`() {
        // Only this server writes a server-scoped balance, whatever the backend.
        runBlocking { fixture.givenAccount(ALICE) }
        mirror.hydrate(ALICE, mapOf(gems.code to BigDecimal("9")))

        path(StorageType.MARIADB).balance(ALICE, gems)

        assertEquals(BigDecimal("9"), mirror.get(ALICE, gems.code))
    }

    @Test
    fun `a write updates the mirror from the authoritative balance`() {
        runBlocking {
            fixture.givenAccount(ALICE)
            fixture.service.deposit(ALICE, BigDecimal("100.00"), coins.code)
        }
        mirror.hydrate(ALICE, mapOf(coins.code to BigDecimal("100.00")))

        val result = path().deposit(ALICE, BigDecimal("5.00"), coins, Attribution("Shop"))

        assertInstanceOf(Outcome.Success::class.java, result)
        assertEquals(BigDecimal("105.00"), mirror.get(ALICE, coins.code))
    }

    @Test
    fun `a write ignores a stale mirror and reports what storage did`() {
        // The mirror claims 100; storage holds 0. An adapter that computed the result itself would
        // answer 105 and be wrong forever. Awaiting the use case is what makes the response true and
        // repairs the mirror on the way past.
        runBlocking { fixture.givenAccount(ALICE) }
        mirror.hydrate(ALICE, mapOf(coins.code to BigDecimal("100.00")))

        val result = path().deposit(ALICE, BigDecimal("5.00"), coins, Attribution("Shop"))

        assertEquals(BigDecimal("5.00"), (result as Outcome.Success).value.amount)
        assertEquals(BigDecimal("5.00"), mirror.get(ALICE, coins.code))
    }

    @Test
    fun `a refused write leaves the mirror untouched`() {
        // The use case decides, not the adapter: an overdraft refusal must not move the mirror, or the
        // next read would report money that was never taken.
        runBlocking { fixture.givenAccount(ALICE) }
        mirror.hydrate(ALICE, mapOf(coins.code to BigDecimal("100.00")))

        val result = path().withdraw(ALICE, BigDecimal("500.00"), coins, Attribution("Shop"))

        assertInstanceOf(Outcome.Failure::class.java, result)
        assertEquals(BigDecimal("100.00"), mirror.get(ALICE, coins.code))
    }

    @Test
    fun `the use case owns the dust rule, not the adapter`() {
        // 0.004 is positive going in and 0.00 after rounding to a 2-digit currency. The adapter never
        // re-implements this; it just reports what the use case said.
        runBlocking { fixture.givenAccount(ALICE) }
        mirror.hydrate(ALICE, mapOf(coins.code to BigDecimal("100.00")))

        val result = path().deposit(ALICE, BigDecimal("0.004"), coins, Attribution("Shop"))

        assertInstanceOf(Outcome.Failure::class.java, result)
        assertInstanceOf(EconomyError.InvalidAmount::class.java, (result as Outcome.Failure).error)
        assertEquals(BigDecimal("100.00"), mirror.get(ALICE, coins.code))
    }

    @Test
    fun `a transfer mirrors both sides`() {
        runBlocking {
            fixture.givenAccount(ALICE)
            fixture.givenAccount(BOB)
            fixture.service.deposit(ALICE, BigDecimal("100.00"), coins.code)
        }
        mirror.hydrate(ALICE, mapOf(coins.code to BigDecimal("100.00")))
        mirror.hydrate(BOB, mapOf(coins.code to BigDecimal("0.00")))

        val result = path().transfer(ALICE, BOB, BigDecimal("30.00"), coins, Attribution("Shop"))

        assertInstanceOf(Outcome.Success::class.java, result)
        assertEquals(BigDecimal("70.00"), mirror.get(ALICE, coins.code))
        assertEquals(BigDecimal("30.00"), mirror.get(BOB, coins.code))
    }

    @Test
    fun `a storage exception becomes a typed failure, never an escape`() {
        // Nothing may propagate into a Vault caller (CODING_STANDARDS §4).
        runBlocking { fixture.givenAccount(ALICE) }
        fixture.accounts.failWith = SQLException("connection reset")

        val result = path().createAccount(ALICE, "Alice")

        assertInstanceOf(Outcome.Failure::class.java, result)
        assertInstanceOf(EconomyError.StorageFailure::class.java, (result as Outcome.Failure).error)
    }

    @Test
    fun `a database that stops answering times out instead of parking the main thread forever`() {
        runBlocking { fixture.givenAccount(ALICE) }
        fixture.balances.stall = 10.seconds

        val result = path(timeout = 50.milliseconds).balance(ALICE, coins)

        assertEquals(BigDecimal.ZERO, result)
        assertTrue(
            log.warnings().any { it.contains("gave up") },
            "a timeout on the main thread must be visible in the console: ${log.warnings()}",
        )
    }

    @Test
    fun `hydrate loads every currency`() {
        runBlocking {
            fixture.givenAccount(ALICE)
            fixture.service.deposit(ALICE, BigDecimal("10.00"), coins.code)
            fixture.service.deposit(ALICE, BigDecimal("3"), gems.code)

            path().hydrate(ALICE)
        }

        assertEquals(BigDecimal("10.00"), mirror.get(ALICE, coins.code))
        assertEquals(BigDecimal("3"), mirror.get(ALICE, gems.code))
    }

    @Test
    fun `hydrate omits a currency it could not read rather than mirroring a zero`() {
        // Absent means "ask the database"; zero would mean "they're broke". Only one of those is true.
        runBlocking {
            fixture.givenAccount(ALICE)
            fixture.balances.failWith = SQLException("connection reset")

            path().hydrate(ALICE)
        }

        assertFalse(mirror.snapshot(ALICE)!!.containsKey(coins.code))
    }

    @Test
    fun `delete evicts the account from the mirror`() {
        runBlocking { fixture.givenAccount(ALICE) }
        mirror.hydrate(ALICE, mapOf(coins.code to BigDecimal("100.00")))

        assertTrue(path().delete(ALICE))

        assertFalse(mirror.isMirrored(ALICE))
    }

    @Test
    fun `an offline player can be paid`() {
        // Nothing about a write needs the mirror: it goes to storage, which does not care who is online.
        runBlocking { fixture.givenAccount(ALICE) }

        val result = path().deposit(ALICE, BigDecimal("50.00"), coins, Attribution("shop"))

        assertEquals(BigDecimal("50.00"), (result as Outcome.Success).value.amount)
        assertEquals(0, BigDecimal("50.00").compareTo(storedCoins()))
    }

    @Test
    fun `paying an offline player leaves nothing behind in the mirror`() {
        // The put after a write is guarded by isMirrored, so an offline payee cannot be resurrected into
        // the mirror by the payment itself — there would be no quit event to evict them again.
        runBlocking { fixture.givenAccount(ALICE) }

        path().deposit(ALICE, BigDecimal("50.00"), coins, Attribution("shop"))

        assertFalse(mirror.isMirrored(ALICE))
        assertEquals(null, mirror.get(ALICE, coins.code))
    }

    @Test
    fun `a player who has never joined cannot be paid`() {
        val result = path().deposit(ALICE, BigDecimal("50.00"), coins, Attribution("shop"))

        assertInstanceOf(EconomyError.AccountNotFound::class.java, (result as Outcome.Failure).error)
    }

    @Test
    fun `money paid during a login is not lost from the mirror`() = runBlocking {
        // The race: hydrate reads the balances, then replaces the whole map. A payment landing between
        // those two moments writes to storage but no-ops against the not-yet-mirrored account, and
        // hydrate then installs the value it read *before* the payment. On SQLite no refresh ever fires,
        // so a mirror that loses it here stays wrong for the player's whole session.
        fixture.givenAccount(ALICE)
        fixture.balances.stall = 50.milliseconds

        // hydrate reads coins (~t=50), then gems (~t=100), then installs both. Landing the payment at
        // t=60 puts it after coins was read and before the map is installed — the whole window.
        val hydrating = launch(Dispatchers.Default) { path().hydrate(ALICE) }
        delay(60)
        fixture.balances.stall = Duration.ZERO
        path().deposit(ALICE, BigDecimal("50.00"), coins, Attribution("shop"))
        hydrating.join()

        assertEquals(0, BigDecimal("50.00").compareTo(storedCoins()), "storage is authoritative")
        assertEquals(
            0,
            BigDecimal("50.00").compareTo(mirror.get(ALICE, coins.code)),
            "the mirror installed a balance read before the payment landed",
        )
    }

    @Test
    fun `a player who quits mid-login is not resurrected by the hydration finishing`() {
        // No quit event will follow, so an entry installed after the eviction would never be evicted
        // again — the mirror would answer for a player who is not on the server.
        runBlocking {
            fixture.givenAccount(ALICE)
            fixture.balances.stall = 50.milliseconds

            val hydrating = launch(Dispatchers.Default) { path().hydrate(ALICE) }
            delay(20)
            mirror.evict(ALICE)
            hydrating.join()
        }

        assertFalse(mirror.isMirrored(ALICE))
    }

    private fun storedCoins() = runBlocking { fixture.balances.get(ALICE, coins) }
}
