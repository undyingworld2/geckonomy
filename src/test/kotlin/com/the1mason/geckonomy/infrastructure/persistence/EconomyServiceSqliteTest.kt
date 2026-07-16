package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.service.EconomyService
import com.the1mason.geckonomy.application.usecase.AccountExists
import com.the1mason.geckonomy.application.usecase.Amounts
import com.the1mason.geckonomy.application.usecase.CanDeposit
import com.the1mason.geckonomy.application.usecase.CanWithdraw
import com.the1mason.geckonomy.application.usecase.CreateAccount
import com.the1mason.geckonomy.application.usecase.DeleteAccount
import com.the1mason.geckonomy.application.usecase.Deposit
import com.the1mason.geckonomy.application.usecase.FindAccountName
import com.the1mason.geckonomy.application.usecase.FormatMoney
import com.the1mason.geckonomy.application.usecase.GetBalance
import com.the1mason.geckonomy.application.usecase.Has
import com.the1mason.geckonomy.application.usecase.ListAccountNames
import com.the1mason.geckonomy.application.usecase.ListCurrencies
import com.the1mason.geckonomy.application.usecase.RenameAccount
import com.the1mason.geckonomy.application.usecase.SetBalance
import com.the1mason.geckonomy.application.usecase.StorageGuard
import com.the1mason.geckonomy.application.usecase.TransactionFactory
import com.the1mason.geckonomy.application.usecase.Transfer
import com.the1mason.geckonomy.application.usecase.Withdraw
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.policy.CurrencyValidation
import com.the1mason.geckonomy.domain.policy.RoundingPolicy
import com.the1mason.geckonomy.infrastructure.config.ConfigCurrencyRegistry
import com.the1mason.geckonomy.infrastructure.config.PoolConfig
import com.the1mason.geckonomy.infrastructure.config.StorageConfig
import com.the1mason.geckonomy.infrastructure.config.StorageType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * M4's integration acceptance: "wiring real M3 repositories through `EconomyService` on SQLite".
 *
 * The use-case suites run against fakes, which is where the branching is exercised; this is the only
 * place the two halves meet, and so the only place that can catch a fake having drifted from the real
 * contract it imitates — an `adjust` that seeds differently, a transaction that does not really roll
 * back, a scale that survives the round trip when it should not.
 *
 * Assembled the way `Geckonomy.onEnable` assembles it, and over [PersistenceHarness] for the same
 * reason it exists: wiring the repositories by hand here could pass while the composition root's
 * version of the same wiring was broken.
 */
class EconomyServiceSqliteTest {

    @TempDir
    lateinit var directory: Path

    private lateinit var harness: PersistenceHarness
    private lateinit var service: EconomyService
    private var keepHistory = true

    private val alice = AccountId(UUID.fromString("00000000-0000-0000-0000-00000000a11c"))
    private val bob = AccountId(UUID.fromString("00000000-0000-0000-0000-0000000000b0"))
    private val coins = TestCurrencies.COINS.code
    private val gems = TestCurrencies.GEMS.code

    @BeforeEach
    fun setUp() {
        harness = PersistenceHarness(storage(), SqliteDialect)
        service = economyOver(harness)
    }

    @AfterEach
    fun tearDown() = harness.close()

    @Test
    fun `an account's life, end to end`() = runBlocking {
        assertEquals(Outcome.Success(true), service.createAccount(alice, "Alice"))

        // Seeded from config: COINS at 100.00, GEMS at 0 — through the real dialect's encoding.
        assertEquals(BigDecimal("100.00"), balanceOf(alice, coins))
        assertEquals(BigDecimal("0"), balanceOf(alice, gems))

        assertEquals(BigDecimal("125.00"), (service.deposit(alice, BigDecimal("25.00")) as Outcome.Success).value.amount)
        assertEquals(BigDecimal("115.00"), (service.withdraw(alice, BigDecimal("10.00")) as Outcome.Success).value.amount)
        assertEquals(BigDecimal("50.00"), (service.set(alice, BigDecimal("50.00")) as Outcome.Success).value.amount)
        assertEquals(BigDecimal("50.00"), balanceOf(alice, coins))

        assertEquals(3, ledgerRows(alice))
    }

    @Test
    fun `a balance survives the round trip at its own scale`() = runBlocking {
        // SQLite stores minor units at scale 4, so this reads back as 100.0000 unless the use case
        // normalizes it. A Vault integrator showing "100.0000 coins" is the bug this catches.
        service.createAccount(alice, "Alice")

        val balance = (service.balance(alice) as Outcome.Success).value

        assertEquals(BigDecimal("100.00"), balance.amount)
        assertEquals("$100.00", service.format(balance))
    }

    @Test
    fun `a transfer moves both sides and writes both rows`() = runBlocking {
        service.createAccount(alice, "Alice")
        service.createAccount(bob, "Bob")

        val moved = service.transfer(alice, bob, BigDecimal("30.00")) as Outcome.Success

        assertEquals(BigDecimal("70.00"), moved.value.payerBalance.amount)
        assertEquals(BigDecimal("130.00"), moved.value.payeeBalance.amount)
        assertEquals(BigDecimal("70.00"), balanceOf(alice, coins))
        assertEquals(BigDecimal("130.00"), balanceOf(bob, coins))
        assertEquals(1, ledgerRows(alice))
        assertEquals(1, ledgerRows(bob))
    }

    /** The M3 acceptance criterion, now reached through the use case that will actually call it. */
    @Test
    fun `a refused transfer leaves both sides untouched`(): Unit = runBlocking {
        service.createAccount(alice, "Alice")
        service.createAccount(bob, "Bob")

        val result = service.transfer(alice, bob, BigDecimal("500.00"))

        assertInstanceOf(EconomyError.InsufficientFunds::class.java, (result as Outcome.Failure).error)
        assertEquals(BigDecimal("100.00"), balanceOf(alice, coins))
        assertEquals(BigDecimal("100.00"), balanceOf(bob, coins))
        assertEquals(0, ledgerRows(alice))
    }

    /**
     * Proves the `exists` checks earn their extra query: without them the foreign key rejects the
     * write and the payer gets "a storage error occurred" instead of "no such account".
     */
    @Test
    fun `a transfer to a nonexistent account leaves the payer whole`(): Unit = runBlocking {
        service.createAccount(alice, "Alice")

        val result = service.transfer(alice, bob, BigDecimal("30.00"))

        assertEquals(EconomyError.AccountNotFound(bob), (result as Outcome.Failure).error)
        assertEquals(BigDecimal("100.00"), balanceOf(alice, coins))
    }

    @Test
    fun `deposits into a currency added after the account existed`() = runBlocking {
        // No GEMS row until something touches it; adjust seeds at zero on the way through.
        service.createAccount(alice, "Alice")
        harness.query { connection ->
            connection.prepareStatement("DELETE FROM gk_balance WHERE currency_code = 'gems'").use { it.executeUpdate() }
        }

        assertEquals(BigDecimal("0"), balanceOf(alice, gems))
        assertEquals(BigDecimal("3"), (service.deposit(alice, BigDecimal("3"), gems) as Outcome.Success).value.amount)
    }

    @Test
    fun `deleting an account keeps its ledger by default`() = runBlocking {
        service.createAccount(alice, "Alice")
        service.deposit(alice, BigDecimal("25.00"))
        keepHistory = true

        assertEquals(Outcome.Success(Unit), service.delete(alice))

        assertEquals(Outcome.Success(false), service.exists(alice))
        assertEquals(0, balanceRows(alice), "ON DELETE CASCADE removes the balances")
        assertEquals(1, ledgerRows(alice), "the audit trail outlives the account")
    }

    @Test
    fun `deleting an account purges its ledger when the operator asked for that`() = runBlocking {
        service.createAccount(alice, "Alice")
        service.deposit(alice, BigDecimal("25.00"))
        keepHistory = false

        service.delete(alice)

        assertEquals(0, ledgerRows(alice))
    }

    @Test
    fun `reports a missing account rather than a storage error`(): Unit = runBlocking {
        val result = service.balance(bob)

        assertEquals(EconomyError.AccountNotFound(bob), (result as Outcome.Failure).error)
    }

    @Test
    fun `creating an account twice does not reseed it`() = runBlocking {
        service.createAccount(alice, "Alice")
        service.withdraw(alice, BigDecimal("90.00"))

        assertEquals(Outcome.Success(false), service.createAccount(alice, "Alice"))
        assertEquals(BigDecimal("10.00"), balanceOf(alice, coins))
    }

    @Test
    fun `names and renames through the facade`() = runBlocking {
        service.createAccount(alice, "Alice")
        service.createAccount(bob, "Bob")

        assertEquals(Outcome.Success("Alice"), service.name(alice))
        assertEquals(Outcome.Success(mapOf(alice to "Alice", bob to "Bob")), service.nameMap())

        service.rename(alice, "AliceV2")

        assertEquals(Outcome.Success("AliceV2"), service.name(alice))
    }

    @Test
    fun `answers the advisory questions`() = runBlocking {
        service.createAccount(alice, "Alice")

        assertEquals(Outcome.Success(true), service.has(alice, BigDecimal("100.00")))
        assertEquals(Outcome.Success(false), service.has(alice, BigDecimal("100.01")))
        assertEquals(Outcome.Success(true), service.canDeposit(alice, BigDecimal("1.00")))
        assertEquals(Outcome.Success(true), service.canWithdraw(alice, BigDecimal("100.00")))
        assertEquals(Outcome.Success(false), service.canWithdraw(alice, BigDecimal("100.01")))
    }

    @Test
    fun `refuses a withdrawal that would overdraw, on the real guard`() = runBlocking {
        service.createAccount(alice, "Alice")

        assertInstanceOf(Outcome.Failure::class.java, service.withdraw(alice, BigDecimal("100.01")))

        assertEquals(BigDecimal("100.00"), balanceOf(alice, coins))
        assertFalse(harness.query { connection ->
            connection.prepareStatement("SELECT 1 FROM gk_transaction").use { it.executeQuery().next() }
        })
    }

    @Test
    fun `refuses a negative set on the real store`() = runBlocking {
        service.createAccount(alice, "Alice")

        val result = service.set(alice, BigDecimal("-1.00"))

        assertInstanceOf(EconomyError.InvalidAmount::class.java, (result as Outcome.Failure).error)
        assertEquals(BigDecimal("100.00"), balanceOf(alice, coins))
    }

    // ── Wiring ──────────────────────────────────────────────────────────

    private suspend fun balanceOf(id: AccountId, currency: com.the1mason.geckonomy.domain.model.CurrencyCode) =
        (service.balance(id, currency) as Outcome.Success).value.amount

    private fun ledgerRows(id: AccountId): Int = countRows("gk_transaction", id)

    private fun balanceRows(id: AccountId): Int = countRows("gk_balance", id)

    private fun countRows(table: String, id: AccountId): Int = harness.query { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE account_id = ?").use { statement ->
            SqliteDialect.bindAccountId(statement, 1, id)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun storage() = StorageConfig(
        type = StorageType.SQLITE,
        file = directory.resolve("economy.db"),
        host = null,
        port = null,
        database = null,
        username = null,
        password = null,
        properties = emptyMap(),
        pool = PoolConfig(maximumPoolSize = 10, minimumIdle = 1, connectionTimeoutMs = 10_000),
    )

    /** The composition root's assembly, minus Bukkit. Kept in step with `Geckonomy.Economy`. */
    private fun economyOver(harness: PersistenceHarness): EconomyService {
        val currencies = ConfigCurrencyRegistry(listOf(TestCurrencies.COINS, TestCurrencies.GEMS))
        val rounding = { RoundingPolicy() }
        val guard = StorageGuard(Logger.getAnonymousLogger().apply { level = Level.OFF })
        val amounts = Amounts(CurrencyValidation(currencies), rounding)
        val transactions = TransactionFactory(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
        val getBalance = GetBalance(harness.accounts, harness.balances, amounts, guard)
        return EconomyService(
            createAccount = CreateAccount(harness.unitOfWork, currencies, rounding, Clock.systemUTC(), guard),
            accountExists = AccountExists(harness.accounts, guard),
            findAccountName = FindAccountName(harness.accounts, guard),
            listAccountNames = ListAccountNames(harness.accounts, guard),
            getBalance = getBalance,
            has = Has(getBalance, amounts),
            canDeposit = CanDeposit(harness.accounts, amounts, guard),
            canWithdraw = CanWithdraw(getBalance, amounts, harness.overdraft),
            deposit = Deposit(harness.unitOfWork, amounts, transactions, guard),
            withdraw = Withdraw(harness.unitOfWork, amounts, transactions, guard),
            setBalance = SetBalance(harness.unitOfWork, amounts, harness.overdraft, transactions, guard),
            transfer = Transfer(harness.unitOfWork, amounts, transactions, guard),
            renameAccount = RenameAccount(harness.accounts, guard),
            deleteAccount = DeleteAccount(harness.unitOfWork, { keepHistory }, guard),
            listCurrencies = ListCurrencies(currencies),
            formatMoney = FormatMoney(java.util.Locale.US),
            currencies = currencies,
        )
    }
}
