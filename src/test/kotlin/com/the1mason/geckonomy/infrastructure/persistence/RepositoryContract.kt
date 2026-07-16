package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.Account
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.AccountType
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.Transaction
import com.the1mason.geckonomy.domain.model.TransactionType
import com.the1mason.geckonomy.infrastructure.config.StorageConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The repository suite, written once and run against every dialect (M3 acceptance,
 * ARCHITECTURE.md §8).
 *
 * A subclass per backend supplies only [storageFor] and [dialect]; every test lives here. That is the
 * point — a dialect whose upsert or UUID encoding differs must still satisfy the identical
 * expectations, and a test that passed on SQLite but was never written for MariaDB would prove
 * nothing about the backend most likely to differ.
 *
 * `runBlocking` rather than `runTest`: these are real queries against a real database on a real IO
 * dispatcher, so there is no virtual time to skip and nothing to be gained by pretending otherwise.
 *
 * Amounts are compared with [BigDecimal.compareTo] rather than `equals`, because scale is part of
 * `equals` and `100` from an INTEGER column is not `100.0000` from a DECIMAL one, though both are the
 * same amount of money.
 */
abstract class RepositoryContract {

    /**
     * Where this dialect's database lives.
     *
     * Called once per harness, and must answer with the **same** database for every [serverId] within
     * one test — that is how the scope tests put two servers on one database — and a **fresh** one for
     * each test.
     */
    protected abstract fun storageFor(serverId: String): StorageConfig

    protected abstract val dialect: SqlDialect

    private lateinit var harness: PersistenceHarness
    private val open = mutableListOf<PersistenceHarness>()

    @BeforeEach
    fun setUp() {
        harness = harness(SERVER_A)
    }

    @AfterEach
    fun tearDown() = open.forEach(PersistenceHarness::close)

    /** A stack for [serverId], sharing whatever database [storageFor] names. */
    private fun harness(serverId: String, allowOverdraft: Boolean = false): PersistenceHarness =
        PersistenceHarness(storageFor(serverId), dialect, serverId, allowOverdraft).also(open::add)

    // ── Accounts ────────────────────────────────────────────────────────

    @Test
    fun `creates an account and finds it`() = runBlocking {
        assertTrue(harness.accounts.create(account(ALICE, "Alice")))

        assertTrue(harness.accounts.exists(ALICE))
        assertEquals("Alice", harness.accounts.findName(ALICE))
    }

    @Test
    fun `create is idempotent`() = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))

        // The second caller — a join listener racing a Vault plugin — must be told "already there",
        // not handed a primary key violation (SPEC.md FR-A1).
        assertFalse(harness.accounts.create(account(ALICE, "Alice Renamed")))
        assertEquals("Alice", harness.accounts.findName(ALICE), "an ignored insert must not overwrite")
    }

    @Test
    fun `reports a missing account`() = runBlocking {
        assertFalse(harness.accounts.exists(ALICE))
        assertNull(harness.accounts.findName(ALICE))
    }

    @Test
    fun `maps every account's name`() = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))
        harness.accounts.create(account(BOB, "Bob"))

        assertEquals(mapOf(ALICE to "Alice", BOB to "Bob"), harness.accounts.nameMap())
    }

    @Test
    fun `renames an account`() = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))

        assertTrue(harness.accounts.rename(ALICE, "Alicia"))
        assertEquals("Alicia", harness.accounts.findName(ALICE))
    }

    @Test
    fun `refuses to rename or delete an account that does not exist`() = runBlocking {
        assertFalse(harness.accounts.rename(ALICE, "Alicia"))
        assertFalse(harness.accounts.delete(ALICE))
    }

    @Test
    fun `deletes an account`() = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))

        assertTrue(harness.accounts.delete(ALICE))
        assertFalse(harness.accounts.exists(ALICE))
    }

    @Test
    fun `deleting an account removes its balances`() = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))
        harness.balances.set(ALICE, COINS, BigDecimal("50.00"))

        harness.accounts.delete(ALICE)

        // Guards the ON DELETE CASCADE that SqlAccountRepository.delete relies on instead of a second
        // statement — on SQLite that cascade only fires because DataSourceFactory turns the
        // foreign_keys pragma on, and this is what would notice if it stopped.
        assertEquals(0, balanceRows(ALICE), "balances must not outlive the account that owned them")
    }

    // ── Balances ────────────────────────────────────────────────────────

    @Test
    fun `has no balance until one is stored`() = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))

        // Null rather than zero: "never seeded" and "spent it all" are different facts.
        assertNull(harness.balances.get(ALICE, COINS))
    }

    @Test
    fun `sets and overwrites a balance`() = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))

        harness.balances.set(ALICE, COINS, BigDecimal("50.00"))
        assertSameAmount("50.00", harness.balances.get(ALICE, COINS))

        harness.balances.set(ALICE, COINS, BigDecimal("75.50"))
        assertSameAmount("75.50", harness.balances.get(ALICE, COINS))
    }

    @Test
    fun `keeps currencies apart`() = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))

        harness.balances.set(ALICE, COINS, BigDecimal("50.00"))
        harness.balances.set(ALICE, GEMS, BigDecimal("7"))

        assertSameAmount("50.00", harness.balances.get(ALICE, COINS))
        assertSameAmount("7", harness.balances.get(ALICE, GEMS))
    }

    @Test
    fun `adjusts an existing balance and returns the new amount`() = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))
        harness.balances.set(ALICE, COINS, BigDecimal("50.00"))

        assertSameAmount("70.25", harness.balances.adjust(ALICE, COINS, BigDecimal("20.25")))
        assertSameAmount("70.25", harness.balances.get(ALICE, COINS))
    }

    @Test
    fun `adjusts a balance that has no row yet`() = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))

        // The case a currency added to config after the account existed lands in: no row, and the
        // first deposit must still work rather than update nothing and claim success.
        assertSameAmount("20.00", harness.balances.adjust(ALICE, COINS, BigDecimal("20.00")))
        assertSameAmount("20.00", harness.balances.get(ALICE, COINS))
    }

    @Test
    fun `refuses an adjustment that would overdraw`() = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))
        harness.balances.set(ALICE, COINS, BigDecimal("10.00"))

        assertNull(harness.balances.adjust(ALICE, COINS, BigDecimal("-10.01")))
        assertSameAmount("10.00", harness.balances.get(ALICE, COINS), "a refused adjustment must change nothing")
    }

    @Test
    fun `allows an adjustment to exactly zero`() = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))
        harness.balances.set(ALICE, COINS, BigDecimal("10.00"))

        // Zero is not an overdraft — the boundary the guard's `>= 0` turns on.
        assertSameAmount("0.00", harness.balances.adjust(ALICE, COINS, BigDecimal("-10.00")))
    }

    @Test
    fun `allows overdraft when the policy does`() = runBlocking {
        val permissive = harness(SERVER_A, allowOverdraft = true)
        permissive.accounts.create(account(ALICE, "Alice"))
        permissive.balances.set(ALICE, COINS, BigDecimal("10.00"))

        assertSameAmount("-5.00", permissive.balances.adjust(ALICE, COINS, BigDecimal("-15.00")))
    }

    @Test
    fun `stores an amount at the full scale it allows`() = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))

        harness.balances.set(ALICE, FINE, BigDecimal("1234.5678"))

        // Four fractional digits survive the round trip exactly — the scale the store fixes and
        // ConfigLoader caps currencies at (SqlDialect.MONEY_SCALE).
        assertSameAmount("1234.5678", harness.balances.get(ALICE, FINE))
    }

    // Explicit `: Unit` on the tests below, unlike the rest: their last expression is
    // assertInstanceOf, which *returns* the exception it matched. Without the annotation Kotlin
    // infers that as the method's return type, and JUnit skips a @Test method that returns a value —
    // silently, which is how a test that never runs looks exactly like one that always passes.
    @Test
    fun `refuses an amount beyond what it can store`(): Unit = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))

        // A /eco give typo, not a balance: past this the minor-unit encoding would overflow, and
        // silently wrapping to a negative fortune is the one outcome worth crashing over.
        val thrown = runCatching { harness.balances.set(ALICE, COINS, BigDecimal("1000000000000000.00")) }

        assertInstanceOf(MoneyOutOfRange::class.java, thrown.exceptionOrNull())
    }

    // ── Baltop ──────────────────────────────────────────────────────────

    @Test
    fun `ranks the richest accounts first`() = runBlocking {
        seedBalances(COINS, ALICE to "50.00", BOB to "150.00", CAROL to "100.00")

        assertEquals(listOf(BOB, CAROL, ALICE), harness.balances.top(COINS, 10).map { it.first })
    }

    @Test
    fun `limits the ranking`() = runBlocking {
        seedBalances(COINS, ALICE to "50.00", BOB to "150.00", CAROL to "100.00")

        assertEquals(listOf(BOB, CAROL), harness.balances.top(COINS, 2).map { it.first })
    }

    @Test
    fun `ranks negative balances below positive ones`() = runBlocking {
        val permissive = harness(SERVER_A, allowOverdraft = true)
        permissive.accounts.create(account(ALICE, "Alice"))
        permissive.accounts.create(account(BOB, "Bob"))
        permissive.balances.set(ALICE, COINS, BigDecimal("-9.00"))
        permissive.balances.set(BOB, COINS, BigDecimal("-1.00"))

        // The decimal-string encoding DATA_MODEL.md §3 first proposed would sort "-9.00" above
        // "-1.00" lexically and hand /baltop the wrong order. Minor-unit integers sort numerically.
        assertEquals(listOf(BOB, ALICE), permissive.balances.top(COINS, 10).map { it.first })
    }

    @Test
    fun `ranks only the currency asked for`() = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))
        harness.accounts.create(account(BOB, "Bob"))
        harness.balances.set(ALICE, COINS, BigDecimal("10.00"))
        harness.balances.set(BOB, GEMS, BigDecimal("999"))

        assertEquals(listOf(ALICE), harness.balances.top(COINS, 10).map { it.first })
    }

    // ── Scope keying (DATA_MODEL.md §7) ─────────────────────────────────

    @Test
    fun `a per-server currency keeps one balance per server`() = runBlocking {
        val serverB = harness(SERVER_B)
        harness.accounts.create(account(ALICE, "Alice"))

        harness.balances.set(ALICE, GEMS, BigDecimal("10"))
        serverB.balances.set(ALICE, GEMS, BigDecimal("99"))

        // Same account, same currency, same database — two independent balances, because GEMS is
        // SERVER-scoped and the two servers resolve different scope keys.
        assertSameAmount("10", harness.balances.get(ALICE, GEMS))
        assertSameAmount("99", serverB.balances.get(ALICE, GEMS))
    }

    @Test
    fun `a network currency shares one balance across servers`() = runBlocking {
        val serverB = harness(SERVER_B)
        harness.accounts.create(account(ALICE, "Alice"))

        harness.balances.set(ALICE, COINS, BigDecimal("10.00"))
        serverB.balances.adjust(ALICE, COINS, BigDecimal("5.00"))

        // COINS is NETWORK-scoped: both servers resolve '@global' and touch the same row.
        assertSameAmount("15.00", harness.balances.get(ALICE, COINS))
    }

    @Test
    fun `a per-server baltop ranks only this server's rows`() = runBlocking {
        val serverB = harness(SERVER_B)
        harness.accounts.create(account(ALICE, "Alice"))
        harness.accounts.create(account(BOB, "Bob"))
        harness.balances.set(ALICE, GEMS, BigDecimal("10"))
        serverB.balances.set(BOB, GEMS, BigDecimal("999"))

        // Bob is richer, but his gems are on the other server and must not appear here.
        assertEquals(listOf(ALICE), harness.balances.top(GEMS, 10).map { it.first })
    }

    @Test
    fun `stores the resolved scope key`() = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))

        harness.balances.set(ALICE, COINS, BigDecimal("1.00"))
        harness.balances.set(ALICE, GEMS, BigDecimal("1"))

        assertEquals(ScopeResolver.GLOBAL_SCOPE_KEY, scopeKeyOf(COINS))
        assertEquals(SERVER_A, scopeKeyOf(GEMS))
    }

    // ── Ledger ──────────────────────────────────────────────────────────

    @Test
    fun `appends a ledger row`() = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))

        harness.log.append(transaction(ALICE, "25.00", "75.00", TransactionType.DEPOSIT))

        assertEquals(1, ledgerRows(ALICE))
    }

    @Test
    fun `keeps the ledger after the account is deleted`() = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))
        harness.log.append(transaction(ALICE, "25.00", "75.00", TransactionType.DEPOSIT))

        harness.accounts.delete(ALICE)

        // The audit trail outlives its account when keep-transaction-history is on, which is why
        // gk_transaction has no foreign key to gk_account (DATA_MODEL.md §6).
        assertEquals(1, ledgerRows(ALICE), "deleting an account must not erase its history")
    }

    @Test
    fun `purges an account's ledger`() = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))
        harness.accounts.create(account(BOB, "Bob"))
        harness.log.append(transaction(ALICE, "25.00", "75.00", TransactionType.DEPOSIT))
        harness.log.append(transaction(ALICE, "-5.00", "70.00", TransactionType.WITHDRAW))
        harness.log.append(transaction(BOB, "10.00", "10.00", TransactionType.DEPOSIT))

        val purged = harness.log.purge(ALICE)

        assertEquals(2, purged)
        assertEquals(0, ledgerRows(ALICE))
        assertEquals(1, ledgerRows(BOB), "purging one account must not touch another's history")
    }

    @Test
    fun `purges rows in every scope`() = runBlocking {
        // GEMS is per-server, so a second server writes its rows under a different scope key. The
        // account is not per-server, so erasing its history must not leave the other server's rows.
        val other = harness(SERVER_B)
        harness.accounts.create(account(ALICE, "Alice"))
        harness.log.append(transaction(ALICE, "25.00", "75.00", TransactionType.DEPOSIT))
        other.log.append(transaction(ALICE, "3", "3", TransactionType.DEPOSIT).copy(currency = GEMS.code))

        val purged = harness.log.purge(ALICE)

        assertEquals(2, purged)
        assertEquals(0, ledgerRows(ALICE))
    }

    @Test
    fun `purging an account with no history removes nothing`() = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))

        assertEquals(0, harness.log.purge(ALICE))
    }

    /** See the note above `refuses an amount beyond what it can store` for the explicit `: Unit`. */
    @Test
    fun `refuses to log an unknown currency`(): Unit = runBlocking {
        harness.accounts.create(account(ALICE, "Alice"))
        val unknown = transaction(ALICE, "1.00", "1.00", TransactionType.DEPOSIT)
            .copy(currency = CurrencyCode("unobtainium"))

        val thrown = runCatching { harness.log.append(unknown) }

        assertInstanceOf(LedgerFailure::class.java, thrown.exceptionOrNull())
    }

    // ── Transactions (ARCHITECTURE.md §5) ───────────────────────────────

    @Test
    fun `a transfer commits both sides together`() = runBlocking {
        givenTwoFundedAccounts()

        harness.unitOfWork.transaction { ctx ->
            ctx.balance.adjust(ALICE, COINS, BigDecimal("-30.00"))
            ctx.balance.adjust(BOB, COINS, BigDecimal("30.00"))
        }

        assertSameAmount("70.00", harness.balances.get(ALICE, COINS))
        assertSameAmount("130.00", harness.balances.get(BOB, COINS))
    }

    @Test
    fun `a failed transfer rolls back the debit`() = runBlocking {
        givenTwoFundedAccounts()

        val thrown = runCatching {
            harness.unitOfWork.transaction { ctx ->
                ctx.balance.adjust(ALICE, COINS, BigDecimal("-30.00"))
                error("the credit failed")
            }
        }

        assertInstanceOf(IllegalStateException::class.java, thrown.exceptionOrNull())
        // The debit landed and then the operation blew up. Without the transaction, Alice would be
        // 30 coins poorer and nobody richer — money destroyed.
        assertSameAmount("100.00", harness.balances.get(ALICE, COINS), "the debit must have rolled back")
        assertSameAmount("100.00", harness.balances.get(BOB, COINS))
    }

    @Test
    fun `a failed transfer rolls back the ledger too`() = runBlocking {
        givenTwoFundedAccounts()

        runCatching {
            harness.unitOfWork.transaction { ctx ->
                ctx.balance.adjust(ALICE, COINS, BigDecimal("-30.00"))
                ctx.log.append(transaction(ALICE, "-30.00", "70.00", TransactionType.TRANSFER_OUT))
                error("the credit failed")
            }
        }

        assertEquals(0, ledgerRows(ALICE), "a rolled-back transfer must leave no audit trail")
    }

    @Test
    fun `a transfer sees its own uncommitted writes`() = runBlocking {
        givenTwoFundedAccounts()

        val seen = harness.unitOfWork.transaction { ctx ->
            ctx.balance.adjust(ALICE, COINS, BigDecimal("-30.00"))
            // Proves the TxContext repositories share one connection: a second connection could not
            // see this uncommitted debit, and would answer 100.
            ctx.balance.get(ALICE, COINS)
        }

        assertSameAmount("70.00", seen)
    }

    @Test
    fun `a transfer refused by the guard leaves both sides untouched`() = runBlocking {
        givenTwoFundedAccounts()

        val debited = harness.unitOfWork.transaction { ctx ->
            ctx.balance.adjust(ALICE, COINS, BigDecimal("-500.00"))
        }

        assertNull(debited, "the guard must refuse a transfer the payer cannot afford")
        assertSameAmount("100.00", harness.balances.get(ALICE, COINS))
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private suspend fun givenTwoFundedAccounts() {
        harness.accounts.create(account(ALICE, "Alice"))
        harness.accounts.create(account(BOB, "Bob"))
        harness.balances.set(ALICE, COINS, BigDecimal("100.00"))
        harness.balances.set(BOB, COINS, BigDecimal("100.00"))
    }

    private suspend fun seedBalances(currency: Currency, vararg amounts: Pair<AccountId, String>) {
        amounts.forEach { (id, amount) ->
            harness.accounts.create(account(id, "Player-${id.value}"))
            harness.balances.set(id, currency, BigDecimal(amount))
        }
    }

    private fun account(id: AccountId, name: String) = Account(id, name, AccountType.PLAYER, CREATED_AT)

    private fun transaction(id: AccountId, delta: String, resulting: String, type: TransactionType) = Transaction(
        id = UUID.randomUUID(),
        accountId = id,
        currency = COINS.code,
        delta = BigDecimal(delta),
        resultingBalance = BigDecimal(resulting),
        type = type,
        sourcePlugin = "geckonomy",
        counterparty = null,
        createdAt = CREATED_AT,
    )

    private fun balanceRows(id: AccountId): Int = countRows("gk_balance", id)

    private fun ledgerRows(id: AccountId): Int = countRows("gk_transaction", id)

    private fun countRows(table: String, id: AccountId): Int = harness.query { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE account_id = ?").use { statement ->
            dialect.bindAccountId(statement, 1, id)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    /** The scope key actually written, read raw — no port exposes it, which is the whole design. */
    private fun scopeKeyOf(currency: Currency): String = harness.query { connection ->
        connection.prepareStatement("SELECT scope_key FROM gk_balance WHERE currency_code = ?").use { statement ->
            statement.setString(1, currency.code.value)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getString(1)
            }
        }
    }

    /** Compares amount, not scale: `100` from an INTEGER column and `100.0000` are the same money. */
    private fun assertSameAmount(expected: String, actual: BigDecimal?, message: String? = null) {
        val value = actual ?: fail(message ?: "expected $expected, but there was no balance at all")
        assertEquals(0, BigDecimal(expected).compareTo(value), message ?: "expected $expected, got $value")
    }

    protected companion object {
        val ALICE = AccountId(UUID.fromString("00000000-0000-0000-0000-00000000a11c"))
        val BOB = AccountId(UUID.fromString("00000000-0000-0000-0000-0000000000b0"))
        val CAROL = AccountId(UUID.fromString("00000000-0000-0000-0000-0000000ca201"))

        val COINS = TestCurrencies.COINS
        val GEMS = TestCurrencies.GEMS

        /** A currency using every fractional digit the store allows; see `SqlDialect.MONEY_SCALE`. */
        val FINE = TestCurrencies.COINS.copy(code = CurrencyCode("fine"), fractionalDigits = 4, isDefault = false)

        const val SERVER_A = "server-a"
        const val SERVER_B = "server-b"

        val CREATED_AT: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
