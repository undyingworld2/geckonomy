package com.the1mason.geckonomy.application

import com.the1mason.geckonomy.domain.model.Account
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.Transaction
import com.the1mason.geckonomy.domain.policy.OverdraftPolicy
import com.the1mason.geckonomy.domain.port.AccountRepository
import com.the1mason.geckonomy.domain.port.BalanceRepository
import com.the1mason.geckonomy.domain.port.TransactionLog
import com.the1mason.geckonomy.domain.port.TxContext
import com.the1mason.geckonomy.domain.port.UnitOfWork
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.sql.SQLException
import kotlin.time.Duration

/**
 * In-memory ports for the use-case tests (ARCHITECTURE.md §8: "application — use cases against
 * in-memory fake ports").
 *
 * They live in the `application` package rather than under `usecase` so M6 and M7 can import them
 * without reaching into another milestone's test package.
 *
 * These fakes are only worth anything insofar as they behave like the SQL ones. Where a real
 * repository has a contract that the use cases lean on — `adjust` seeding at zero and refusing with
 * `null`, a missing account failing on the foreign key — the fake reproduces it, with a comment
 * saying which real behavior it is standing in for. `RepositoryContract` is what proves the real
 * side; these keep the two from drifting.
 *
 * The stored scale mimics `SqlDialect.MONEY_SCALE`: every backend keeps money at four decimals, so a
 * 2-digit currency reads back as `100.0000`, and a use case that forgets to normalize it should fail
 * a test here rather than surface `100.0000` to a player.
 */
private const val STORED_SCALE = 4

/** A fake that can be rewound, so [InMemoryUnitOfWork] can roll back. */
internal interface Snapshotting {
    fun snapshot(): Any
    fun restore(state: Any)
}

/**
 * Lets a test make the next call fail, or hang.
 *
 * `var failWith: Exception?` rather than a mock: the tests need "the database dies *now*", and one
 * assignable field says that more plainly than a stubbing DSL.
 *
 * [stall] is the other half of that: "the database stops answering", which is a different failure
 * from an exception and the one M6's bounded main-thread reads exist to survive. It delays rather
 * than sleeps, so a test still costs nothing to run.
 */
internal abstract class Failable {
    var failWith: Exception? = null
    var stall: Duration = Duration.ZERO

    protected suspend fun checkFailure() {
        if (stall > Duration.ZERO) delay(stall)
        failWith?.let { failWith = null; throw it }
    }
}

internal class InMemoryAccountRepository : AccountRepository, Failable(), Snapshotting {

    private val accounts = mutableMapOf<AccountId, Account>()

    /**
     * The whole stored [Account], which no port exposes.
     *
     * The `AccountRepository` contract only ever hands back a name, so the fields a use case sets
     * without being asked about them again — `type`, `createdAt` — would otherwise be unassertable.
     * `RepositoryContract` reaches past the ports the same way, with raw SQL.
     */
    fun find(id: AccountId): Account? = accounts[id]

    /** Idempotent, like the real `INSERT OR IGNORE`: `false` means it was already there. */
    override suspend fun create(account: Account): Boolean {
        checkFailure()
        if (accounts.containsKey(account.id)) return false
        accounts[account.id] = account
        return true
    }

    override suspend fun exists(id: AccountId): Boolean {
        checkFailure()
        return accounts.containsKey(id)
    }

    override suspend fun findName(id: AccountId): String? {
        checkFailure()
        return accounts[id]?.name
    }

    override suspend fun nameMap(): Map<AccountId, String> {
        checkFailure()
        return accounts.mapValues { (_, account) -> account.name }
    }

    override suspend fun rename(id: AccountId, name: String): Boolean {
        checkFailure()
        val account = accounts[id] ?: return false
        accounts[id] = account.copy(name = name)
        return true
    }

    override suspend fun delete(id: AccountId): Boolean {
        checkFailure()
        return accounts.remove(id) != null
    }

    override fun snapshot(): Any = accounts.toMap()

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Any) {
        accounts.clear()
        accounts.putAll(state as Map<AccountId, Account>)
    }
}

/**
 * @param accounts consulted only to reproduce `gk_balance`'s `FOREIGN KEY (account_id) REFERENCES
 *   gk_account (id)`, which SQLite enforces because `DataSourceFactory` sets `foreign_keys=true`. A
 *   balance for an account that does not exist is not an orphan row — it is an [SQLException]. The
 *   use cases check `exists` before writing precisely to turn that into `AccountNotFound`, and
 *   without this the tests could not tell whether they had.
 */
internal class InMemoryBalanceRepository(
    private val accounts: InMemoryAccountRepository,
    private val overdraft: OverdraftPolicy,
) : BalanceRepository, Failable(), Snapshotting {

    private val balances = mutableMapOf<Key, BigDecimal>()

    private data class Key(val id: AccountId, val currency: CurrencyCode)

    override suspend fun get(id: AccountId, currency: Currency): BigDecimal? {
        checkFailure()
        return balances[Key(id, currency.code)]
    }

    override suspend fun set(id: AccountId, currency: Currency, amount: BigDecimal) {
        checkFailure()
        requireAccount(id)
        balances[Key(id, currency.code)] = amount.setScale(STORED_SCALE)
    }

    /**
     * The real contract, reproduced exactly — this is the fake worth reading twice.
     *
     * - A missing row **counts as zero and is created by this call**, so a currency added to config
     *   after an account exists can still be deposited into.
     * - The guard is applied to the *resulting* balance, and a refusal returns `null` rather than
     *   throwing: insufficient funds is routine.
     * - The seeded row survives a refusal. The real one seeds before it guards, and a standalone
     *   `adjust` commits even when the update touches nothing — so a refused withdrawal leaves a zero
     *   row behind. A fake that "cleanly" refused would hide that.
     */
    override suspend fun adjust(id: AccountId, currency: Currency, delta: BigDecimal): BigDecimal? {
        checkFailure()
        requireAccount(id)
        val key = Key(id, currency.code)
        val current = balances.getOrPut(key) { BigDecimal.ZERO.setScale(STORED_SCALE) }
        val updated = current + delta
        if (!overdraft.permits(updated)) return null
        balances[key] = updated.setScale(STORED_SCALE)
        return balances[key]
    }

    override suspend fun top(currency: Currency, limit: Int): List<Pair<AccountId, BigDecimal>> {
        checkFailure()
        return balances.filterKeys { it.currency == currency.code }
            .map { (key, amount) -> key.id to amount }
            .sortedByDescending { it.second }
            .take(limit)
    }

    private suspend fun requireAccount(id: AccountId) {
        if (!accounts.exists(id)) throw SQLException("FOREIGN KEY constraint failed: no account $id")
    }

    override fun snapshot(): Any = balances.toMap()

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Any) {
        balances.clear()
        balances.putAll(state as Map<Key, BigDecimal>)
    }
}

/** Records what was appended, so a test can assert the ledger row a use case wrote. */
internal class RecordingTransactionLog : TransactionLog, Failable(), Snapshotting {

    private val rows = mutableListOf<Transaction>()

    /** Every row appended so far, in order. */
    val entries: List<Transaction> get() = rows.toList()

    override suspend fun append(tx: Transaction) {
        checkFailure()
        rows += tx
    }

    override suspend fun purge(id: AccountId): Int {
        checkFailure()
        val before = rows.size
        rows.removeAll { it.accountId == id }
        return before - rows.size
    }

    override fun snapshot(): Any = rows.toList()

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Any) {
        rows.clear()
        rows.addAll(state as List<Transaction>)
    }
}

/**
 * A transaction over the fakes: snapshot everything, run the block, and put it all back if it throws.
 *
 * Crude next to a real one — it serializes nothing and isolates nothing — but it reproduces the
 * property the use cases depend on and the tests are about: **a throwable undoes every write in the
 * block**. Without it, `Transfer`'s "insufficient funds leaves both sides untouched" would pass
 * against a fake that had simply kept the debit, and the `Abort` mechanism could be deleted with the
 * unit tests still green. `EconomyServiceSqliteTest` proves the real one.
 */
internal class InMemoryUnitOfWork(
    private val accounts: InMemoryAccountRepository,
    private val balances: InMemoryBalanceRepository,
    private val log: RecordingTransactionLog,
) : UnitOfWork {

    private val context = object : TxContext {
        override val accounts: AccountRepository = this@InMemoryUnitOfWork.accounts
        override val balance: BalanceRepository = this@InMemoryUnitOfWork.balances
        override val log: TransactionLog = this@InMemoryUnitOfWork.log
    }

    override suspend fun <T> transaction(block: suspend (TxContext) -> T): T {
        val saved = listOf<Snapshotting>(accounts, balances, log).map { it to it.snapshot() }
        return try {
            block(context)
        } catch (e: Throwable) {
            // Like SqlUnitOfWork: roll back on *any* throwable, then rethrow. Including
            // CancellationException, and including Transfer's Abort.
            saved.forEach { (fake, state) -> fake.restore(state) }
            throw e
        }
    }
}
