package com.the1mason.geckonomy.infrastructure.vault

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.application.usecase.FormatMoney
import com.the1mason.geckonomy.domain.policy.RoundingPolicy
import com.the1mason.geckonomy.infrastructure.balance.OnlineBalanceMirror
import com.the1mason.geckonomy.infrastructure.config.StorageType
import com.the1mason.geckonomy.infrastructure.i18n.LanguageRepository
import com.the1mason.geckonomy.infrastructure.i18n.LogCapture
import com.the1mason.geckonomy.infrastructure.i18n.MessageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse.ResponseType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.OfflinePlayerMock
import java.nio.file.Path
import java.util.Locale

/** The legacy v1 adapter (FR-V6): default currency only, `double` amounts, no banks. */
@Suppress("DEPRECATION")
class LegacyVaultEconomyProviderTest {

    @TempDir
    lateinit var directory: Path

    private lateinit var server: ServerMock

    private val fixture = EconomyFixture()
    private val mirror = OnlineBalanceMirror()
    private val log = LogCapture()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val alice = OfflinePlayerMock(ALICE.value, "Alice")
    private val bob = OfflinePlayerMock(BOB.value, "Bob")

    private val provider by lazy {
        val responses = ResponseMapper(
            MessageService(LanguageRepository(directory, log.logger), { "en" }).apply { reload() },
            FormatMoney { Locale.US },
        )
        val sync = VaultSyncPath(fixture.service, mirror, scope, StorageType.SQLITE, log.logger)
        LegacyVaultEconomyProvider(
            enabled = { true },
            economy = fixture.service,
            currencies = fixture.currencies,
            sync = sync,
            responses = LegacyResponseMapper(responses),
            players = PlayerResolver(server, sync),
            rounding = { RoundingPolicy() },
        )
    }

    @BeforeEach
    fun start() {
        server = MockBukkit.mock()
        server.playerList.addOfflinePlayer(alice)
        server.playerList.addOfflinePlayer(bob)
        runBlocking {
            fixture.givenAccount(ALICE, "Alice")
            fixture.givenAccount(BOB, "Bob")
        }
    }

    @AfterEach
    fun stop() {
        MockBukkit.unmock()
    }

    // ── Capabilities ────────────────────────────────────────────────────

    @Test
    fun `advertises no bank support and the default currency's shape`() {
        assertEquals("Geckonomy", provider.name)
        assertTrue(provider.isEnabled)
        assertFalse(provider.hasBankSupport())
        assertEquals(2, provider.fractionalDigits())
        assertEquals("Coin", provider.currencyNameSingular())
        assertEquals("Coins", provider.currencyNamePlural())
        assertEquals("$1,000.00", provider.format(1000.0))
    }

    // ── Balances via OfflinePlayer ──────────────────────────────────────

    @Test
    fun `deposit and withdraw move the default currency`() {
        val deposited = provider.depositPlayer(alice, 100.0)

        assertEquals(ResponseType.SUCCESS, deposited.type)
        assertTrue(deposited.transactionSuccess())
        assertEquals(100.0, deposited.balance)
        assertEquals(100.0, deposited.amount)

        assertEquals(70.0, provider.withdrawPlayer(alice, 30.0).balance)
        assertEquals(70.0, provider.getBalance(alice))
    }

    @Test
    fun `overdrawing is refused with a localized reason`() {
        val response = provider.withdrawPlayer(alice, 10.0)

        assertEquals(ResponseType.FAILURE, response.type)
        assertEquals(0.0, response.amount)
        assertTrue(response.errorMessage.contains("doesn't have"), response.errorMessage)
    }

    @Test
    fun `has reflects the balance`() {
        provider.depositPlayer(alice, 50.0)

        assertTrue(provider.has(alice, 50.0))
        assertFalse(provider.has(alice, 50.01))
    }

    @Test
    fun `accounts exist and can be created idempotently`() {
        assertTrue(provider.hasAccount(alice))
        assertTrue(provider.createPlayerAccount(alice))
        assertTrue(provider.createPlayerAccount(alice))
    }

    // ── Name overloads ──────────────────────────────────────────────────

    @Test
    fun `the deprecated name overloads resolve through the usercache`() {
        provider.depositPlayer("Alice", 25.0)

        assertEquals(25.0, provider.getBalance("Alice"))
        assertTrue(provider.hasAccount("Alice"))
        assertTrue(provider.has("Alice", 25.0))
    }

    @Test
    fun `an unresolvable name fails rather than inventing an account`() {
        assertFalse(provider.hasAccount("Nobody"))
        assertEquals(0.0, provider.getBalance("Nobody"))
        assertFalse(provider.has("Nobody", 1.0))
        assertFalse(provider.createPlayerAccount("Nobody"))

        val response = provider.depositPlayer("Nobody", 5.0)
        assertEquals(ResponseType.FAILURE, response.type)
        assertTrue(response.errorMessage.contains("Nobody"), response.errorMessage)
    }

    // ── double conversion ───────────────────────────────────────────────

    @Test
    fun `a double round-trips through BigDecimal without binary noise`() {
        // 0.1 + 0.2 in doubles is 0.30000000000000004. Three deposits must still read as 0.30, and
        // the ledger must not be carrying the difference.
        provider.depositPlayer(alice, 0.1)
        provider.depositPlayer(alice, 0.2)

        assertEquals(0.30, provider.getBalance(alice))
    }

    @Test
    fun `an amount finer than the currency is rounded, not silently dropped`() {
        val response = provider.depositPlayer(alice, 1.005)

        assertEquals(ResponseType.SUCCESS, response.type)
        assertEquals(1.01, response.balance)
    }

    @Test
    fun `dust that rounds to nothing is refused rather than reported as success`() {
        val response = provider.depositPlayer(alice, 0.004)

        assertEquals(ResponseType.FAILURE, response.type)
        assertEquals(0.0, provider.getBalance(alice))
    }

    // ── World ignored ───────────────────────────────────────────────────

    @Test
    fun `the world argument is accepted and ignored`() {
        provider.depositPlayer(alice, "world_nether", 15.0)

        assertEquals(15.0, provider.getBalance(alice, "world_the_end"))
    }

    // ── Banks ───────────────────────────────────────────────────────────

    @Test
    fun `every bank method declines`() {
        val responses = listOf(
            provider.createBank("Guild", alice),
            provider.createBank("Guild", "Alice"),
            provider.deleteBank("Guild"),
            provider.bankBalance("Guild"),
            provider.bankHas("Guild", 1.0),
            provider.bankWithdraw("Guild", 1.0),
            provider.bankDeposit("Guild", 1.0),
            provider.isBankOwner("Guild", alice),
            provider.isBankOwner("Guild", "Alice"),
            provider.isBankMember("Guild", alice),
            provider.isBankMember("Guild", "Alice"),
        )

        responses.forEach {
            assertEquals(ResponseType.NOT_IMPLEMENTED, it.type)
            assertFalse(it.transactionSuccess())
            assertEquals("Banks not supported", it.errorMessage)
        }
        assertTrue(provider.banks.isEmpty())
    }

    // ── Attribution ─────────────────────────────────────────────────────

    @Test
    fun `legacy writes are attributed to the v1 bridge`() {
        // The interface carries no plugin name on any method, so "vault" is the most we can honestly say.
        provider.depositPlayer(alice, 10.0)

        assertEquals("vault", fixture.log.entries.last().sourcePlugin)
    }

    // ── Completeness ────────────────────────────────────────────────────

    @TestFactory
    fun `every legacy Economy method is implemented here`(): List<DynamicTest> =
        Economy::class.java.methods.map { declared ->
            DynamicTest.dynamicTest("${declared.name}(${declared.parameterCount})") {
                val method = LegacyVaultEconomyProvider::class.java.getMethod(declared.name, *declared.parameterTypes)

                assertEquals(LegacyVaultEconomyProvider::class.java, method.declaringClass)
            }
        }
}
