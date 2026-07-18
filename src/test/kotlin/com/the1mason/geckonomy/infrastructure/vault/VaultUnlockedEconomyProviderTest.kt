package com.the1mason.geckonomy.infrastructure.vault

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.infrastructure.balance.OnlineBalanceMirror
import com.the1mason.geckonomy.infrastructure.config.StorageType
import com.the1mason.geckonomy.infrastructure.i18n.CurrencyNames
import com.the1mason.geckonomy.infrastructure.i18n.FormatMoney
import com.the1mason.geckonomy.infrastructure.i18n.LanguageRepository
import com.the1mason.geckonomy.infrastructure.i18n.LogCapture
import com.the1mason.geckonomy.infrastructure.i18n.MessageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.milkbowl.vault2.economy.AccountPermission
import net.milkbowl.vault2.economy.EconomyResponse.ResponseType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.util.Locale

/** The v2 adapter over a **real** [com.the1mason.geckonomy.application.service.EconomyService]. */
@Suppress("DEPRECATION") // the deprecated overloads are part of the interface and must be exercised
class VaultUnlockedEconomyProviderTest {

    @TempDir
    lateinit var directory: Path

    private val fixture = EconomyFixture()
    private val mirror = OnlineBalanceMirror()
    private val log = LogCapture()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val format = FormatMoney({ Locale.US }, CurrencyNames { _, _ -> null })

    private val responses by lazy {
        ResponseMapper(
            MessageService(LanguageRepository(directory, log.logger), { "en" }).apply { reload() },
            format,
        )
    }

    private val provider by lazy {
        val sync = VaultSyncPath(fixture.service, mirror, scope, StorageType.SQLITE, log.logger)
        VaultUnlockedEconomyProvider(
            enabled = { true },
            currencies = fixture.currencies,
            sync = sync,
            responses = responses,
            asyncEconomy = GeckonomyAsyncEconomy(fixture.service, fixture.currencies, scope, responses, log.logger),
            formatMoney = format,
            logger = log.logger,
        )
    }

    private fun givenAccounts() = runBlocking {
        fixture.givenAccount(ALICE, "Alice")
        fixture.givenAccount(BOB, "Bob")
    }

    // ── Capabilities ────────────────────────────────────────────────────

    @Test
    fun `advertises the capabilities SPEC FR-V2 fixes`() {
        assertEquals("Geckonomy", provider.name)
        assertTrue(provider.isEnabled)
        assertTrue(provider.hasMultiCurrencySupport())
        assertFalse(provider.hasSharedAccountSupport())
        assertTrue(provider.supportsAsync())
        assertTrue(provider.async().isPresent)
    }

    @Test
    fun `isEnabled tracks the plugin rather than being hard-coded true`() {
        var live = true
        val sync = VaultSyncPath(fixture.service, mirror, scope, StorageType.SQLITE, log.logger)
        val provider = VaultUnlockedEconomyProvider(
            { live }, fixture.currencies, sync, responses,
            GeckonomyAsyncEconomy(fixture.service, fixture.currencies, scope, responses, log.logger), format, log.logger,
        )

        live = false

        assertFalse(provider.isEnabled)
    }

    // ── Currency & formatting ───────────────────────────────────────────

    @Test
    fun `reports every configured currency and the default`() {
        assertEquals(listOf("coins", "gems"), provider.currencies().toList())
        assertEquals("coins", provider.getDefaultCurrency("Shop"))
        assertEquals("Coin", provider.defaultCurrencyNameSingular("Shop"))
        assertEquals("Coins", provider.defaultCurrencyNamePlural("Shop"))
    }

    @Test
    fun `hasCurrency answers for real codes and rejects nonsense without throwing`() {
        assertTrue(provider.hasCurrency("coins"))
        assertTrue(provider.hasCurrency("COINS"), "codes are case-insensitive")
        assertFalse(provider.hasCurrency("doubloons"))
        assertFalse(provider.hasCurrency("not a code!"), "a malformed code must not throw")
    }

    @Test
    fun `fractionalDigits is per currency, not per plugin`() {
        // The interface default ignores the currency argument, which would report 2 digits for Gems.
        assertEquals(2, provider.fractionalDigits("Shop"))
        assertEquals(2, provider.fractionalDigits("Shop", "coins"))
        assertEquals(0, provider.fractionalDigits("Shop", "gems"))
    }

    @Test
    fun `format uses each currency's own template`() {
        assertEquals("$1,000.00", provider.format("Shop", BigDecimal("1000.00")))
        assertEquals("5 Gems", provider.format("Shop", BigDecimal("5"), "gems"))
        assertEquals("1 Gem", provider.format("Shop", BigDecimal("1"), "gems"))
    }

    // ── Accounts ────────────────────────────────────────────────────────

    @Test
    fun `createAccount is idempotent, per FR-A1`() {
        assertTrue(provider.createAccount(ALICE.value, "Alice", true))
        assertTrue(provider.createAccount(ALICE.value, "Alice", true), "creating an existing account is success")
    }

    @Test
    fun `account lookup answers name and existence`() {
        givenAccounts()

        assertTrue(provider.hasAccount(ALICE.value))
        assertEquals("Alice", provider.getAccountName(ALICE.value).orElseThrow())
        assertEquals(mapOf(ALICE.value to "Alice", BOB.value to "Bob"), provider.getUUIDNameMap())
    }

    @Test
    fun `an unknown account has no name and does not exist`() {
        assertFalse(provider.hasAccount(ALICE.value))
        assertTrue(provider.getAccountName(ALICE.value).isEmpty)
    }

    @Test
    fun `rename and delete go through`() {
        givenAccounts()

        assertTrue(provider.renameAccount("Shop", ALICE.value, "Alicia"))
        assertEquals("Alicia", provider.getAccountName(ALICE.value).orElseThrow())

        assertTrue(provider.deleteAccount("Shop", ALICE.value))
        assertFalse(provider.hasAccount(ALICE.value))
    }

    @Test
    fun `every account supports every known currency`() {
        givenAccounts()

        assertTrue(provider.accountSupportsCurrency("Shop", ALICE.value, "gems"))
        assertFalse(provider.accountSupportsCurrency("Shop", ALICE.value, "doubloons"))
    }

    // ── Balances ────────────────────────────────────────────────────────

    @Test
    fun `deposit and withdraw move money and report the resulting balance`() {
        givenAccounts()

        val deposited = provider.deposit("Shop", ALICE.value, BigDecimal("100.00"))
        assertEquals(ResponseType.SUCCESS, deposited.type)
        assertEquals(BigDecimal("100.00"), deposited.balance)
        assertEquals(BigDecimal("100.00"), deposited.amount)

        val withdrawn = provider.withdraw("Shop", ALICE.value, BigDecimal("30.00"))
        assertEquals(BigDecimal("70.00"), withdrawn.balance)
        assertEquals(BigDecimal("70.00"), provider.balance("Shop", ALICE.value))
    }

    @Test
    fun `a second currency is independent of the default`() {
        givenAccounts()

        provider.deposit("Shop", ALICE.value, "", "gems", BigDecimal("5"))

        assertEquals(BigDecimal("5"), provider.balance("Shop", ALICE.value, "", "gems"))
        // 0.00, not 0: a balance comes back at its currency's scale (Amounts.balance).
        assertEquals(BigDecimal("0.00"), provider.balance("Shop", ALICE.value), "coins must be untouched")
    }

    @Test
    fun `overdrawing is refused and says why`() {
        givenAccounts()

        val response = provider.withdraw("Shop", ALICE.value, BigDecimal("10.00"))

        assertEquals(ResponseType.FAILURE, response.type)
        assertFalse(response.transactionSuccess())
        assertTrue(response.errorMessage.contains("doesn't have"), response.errorMessage)
    }

    @Test
    fun `has reflects the balance`() {
        givenAccounts()
        provider.deposit("Shop", ALICE.value, BigDecimal("50.00"))

        assertTrue(provider.has("Shop", ALICE.value, BigDecimal("50.00")))
        assertFalse(provider.has("Shop", ALICE.value, BigDecimal("50.01")))
    }

    @Test
    fun `set replaces the balance rather than adjusting it`() {
        givenAccounts()
        provider.deposit("Shop", ALICE.value, BigDecimal("100.00"))

        val response = provider.set("Shop", ALICE.value, BigDecimal("7.00"))

        assertEquals(ResponseType.SUCCESS, response.type)
        assertEquals(BigDecimal("7.00"), response.balance)
    }

    @Test
    fun `canWithdraw and canDeposit answer for real and change nothing`() {
        givenAccounts()
        provider.deposit("Shop", ALICE.value, BigDecimal("50.00"))

        val can = provider.canWithdraw("Shop", ALICE.value, BigDecimal("20.00"))
        val cannot = provider.canWithdraw("Shop", ALICE.value, BigDecimal("500.00"))

        assertNotEquals(ResponseType.NOT_IMPLEMENTED, can.type, "the interface default answers NOT_IMPLEMENTED")
        assertEquals(ResponseType.SUCCESS, can.type)
        assertEquals(ResponseType.FAILURE, cannot.type)
        assertEquals(ResponseType.SUCCESS, provider.canDeposit("Shop", ALICE.value, BigDecimal("1.00")).type)
        assertEquals(BigDecimal("50.00"), provider.balance("Shop", ALICE.value), "a pre-flight check must not mutate")
    }

    // ── Transfers ───────────────────────────────────────────────────────

    @Test
    fun `transfer moves funds and reports both resulting balances`() {
        givenAccounts()
        provider.deposit("Shop", ALICE.value, BigDecimal("100.00"))

        val response = provider.transfer("Shop", ALICE.value, BOB.value, BigDecimal("40.00"))

        assertEquals(ResponseType.SUCCESS, response.type)
        assertEquals(BigDecimal("60.00"), response.balance(ALICE.value).orElseThrow())
        assertEquals(BigDecimal("40.00"), response.balance(BOB.value).orElseThrow())
    }

    @Test
    fun `a refused transfer moves nothing`() {
        givenAccounts()
        provider.deposit("Shop", ALICE.value, BigDecimal("10.00"))

        val response = provider.transfer("Shop", ALICE.value, BOB.value, BigDecimal("40.00"))

        assertEquals(ResponseType.FAILURE, response.type)
        assertEquals(BigDecimal("10.00"), provider.balance("Shop", ALICE.value))
        assertEquals(BigDecimal("0.00"), provider.balance("Shop", BOB.value))
    }

    // ── Unknown currency ────────────────────────────────────────────────

    @Test
    fun `an unknown currency fails every write rather than silently using the default`() {
        givenAccounts()
        provider.deposit("Shop", ALICE.value, BigDecimal("100.00"))

        listOf(
            provider.deposit("Shop", ALICE.value, "", "doubloons", BigDecimal("1")),
            provider.withdraw("Shop", ALICE.value, "", "doubloons", BigDecimal("1")),
            provider.set("Shop", ALICE.value, "", "doubloons", BigDecimal("1")),
        ).forEach { response ->
            assertEquals(ResponseType.FAILURE, response.type)
            assertTrue(response.errorMessage.contains("doubloons"), response.errorMessage)
        }

        assertEquals(BigDecimal("100.00"), provider.balance("Shop", ALICE.value))
    }

    @Test
    fun `an unknown currency reads as zero`() {
        givenAccounts()

        assertEquals(BigDecimal.ZERO, provider.balance("Shop", ALICE.value, "", "doubloons"))
        assertFalse(provider.has("Shop", ALICE.value, "", "doubloons", BigDecimal.ONE))
    }

    @Test
    fun `an unknown currency fails a transfer`() {
        givenAccounts()

        val response = provider.transfer("Shop", ALICE.value, BOB.value, "", "doubloons", BigDecimal("1"))

        assertEquals(ResponseType.FAILURE, response.type)
        assertTrue(response.errorMessage.contains("doubloons"))
    }

    // ── World is ignored ────────────────────────────────────────────────

    @Test
    fun `the world argument is accepted and ignored`() {
        givenAccounts()
        provider.deposit("Shop", ALICE.value, "world_nether", BigDecimal("25.00"))

        assertEquals(BigDecimal("25.00"), provider.balance("Shop", ALICE.value, "world_the_end"))
        assertEquals(BigDecimal("25.00"), provider.balance("Shop", ALICE.value))
    }

    // ── Shared accounts ─────────────────────────────────────────────────

    @Test
    fun `every shared-account method declines`() {
        givenAccounts()

        assertFalse(provider.createSharedAccount("Shop", ALICE.value, "Guild", BOB.value))
        assertFalse(provider.isAccountOwner("Shop", ALICE.value, BOB.value))
        assertFalse(provider.setOwner("Shop", ALICE.value, BOB.value))
        assertFalse(provider.isAccountMember("Shop", ALICE.value, BOB.value))
        assertFalse(provider.addAccountMember("Shop", ALICE.value, BOB.value))
        assertFalse(provider.addAccountMember("Shop", ALICE.value, BOB.value, AccountPermission.DEPOSIT))
        assertFalse(provider.removeAccountMember("Shop", ALICE.value, BOB.value))
        assertFalse(provider.hasAccountPermission("Shop", ALICE.value, BOB.value, AccountPermission.BALANCE))
        assertFalse(provider.updateAccountPermission("Shop", ALICE.value, BOB.value, AccountPermission.BALANCE, true))
    }

    @Test
    fun `shared-account listings are empty, including the ones Vault derives`() {
        assertTrue(provider.accountsWithAccessTo("Shop", ALICE.value, AccountPermission.OWNER).isEmpty())
        assertTrue(provider.accountsAccessTo("Shop", ALICE.value, AccountPermission.OWNER).isEmpty())
        assertTrue(provider.accountsWithOwnerOf("Shop", ALICE.value).isEmpty())
        assertTrue(provider.accountsWithMembershipTo("Shop", ALICE.value).isEmpty())
        assertTrue(provider.accountsOwnedBy("Shop", ALICE.value).isEmpty())
        assertTrue(provider.accountsMemberOf("Shop", ALICE.value).isEmpty())
    }

    // ── Attribution ─────────────────────────────────────────────────────

    @Test
    fun `the calling plugin is recorded on the ledger row`() {
        // Attribution exists so an admin reading history can tell a shop's withdrawal from /eco take.
        givenAccounts()

        provider.deposit("SuperShop", ALICE.value, BigDecimal("10.00"))

        assertEquals("SuperShop", fixture.log.entries.last().sourcePlugin)
    }
}
