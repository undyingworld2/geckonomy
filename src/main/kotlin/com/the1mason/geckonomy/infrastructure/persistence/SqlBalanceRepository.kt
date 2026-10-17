package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.policy.OverdraftPolicy
import com.the1mason.geckonomy.domain.port.BalanceRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException

/**
 * [BalanceRepository] over SQL.
 *
 * Every statement here is keyed by `(account_id, currency_code, scope_key)`, with the scope key
 * resolved from the currency by [ScopeResolver] — which is why the port takes a whole [Currency] and
 * why no caller above this layer knows a server id exists (DATA_MODEL.md §7).
 *
 * @param overdraft captured at startup, not read per call: the rule is compiled into [adjust]'s
 *   `WHERE` clause, because the check has to happen inside the same statement as the update it
 *   guards. A config reload cannot change it, and `ConfigService` warns accordingly.
 */
class SqlBalanceRepository(
    private val connections: ConnectionSource,
    private val dialect: SqlDialect,
    private val scopes: ScopeResolver,
    private val overdraft: OverdraftPolicy,
    private val dispatcher: CoroutineDispatcher,
) : BalanceRepository {

    override suspend fun get(id: AccountId, currency: Currency): BigDecimal? = withContext(dispatcher) {
        connections.use { connection -> read(connection, id, currency) }
    }

    /**
     * Unguarded on purpose: an admin `/eco set` to a negative balance is theirs to make, and
     * `SetBalance` applies [OverdraftPolicy] before calling. The guard belongs in [adjust], where the
     * caller cannot know the result in advance.
     */
    override suspend fun set(id: AccountId, currency: Currency, amount: BigDecimal) {
        withContext(dispatcher) {
            connections.use { connection ->
                connection.prepareStatement(
                    dialect.upsert(
                        table = "gk_balance",
                        columns = listOf("account_id", "currency_code", "scope_key", "amount"),
                        keyColumns = listOf("account_id", "currency_code", "scope_key"),
                        updateColumns = listOf("amount"),
                    ),
                ).use { statement ->
                    bindKey(statement, id, currency)
                    dialect.bindMoney(statement, 4, amount)
                    statement.executeUpdate()
                }
            }
        }
    }

    /**
     * Atomic, guarded, and seeding — the three things that make this the only safe way to move money.
     *
     * Lock, then write, in one transaction:
     *
     * 1. **Lock.** `SELECT … FOR UPDATE` takes the row's *exclusive* lock on first touch and reads the
     *    balance under it. A missing row is created at zero instead ([lockRow]).
     * 2. **Guard and update.** `SET amount = amount + delta WHERE … AND amount + delta >= 0`. Zero rows
     *    updated means the guard refused. With the lock held nothing can move the balance between the
     *    two statements; the guard stays as the one place the overdraft rule is written, rather than a
     *    second copy of it in Kotlin.
     * 3. **No read-back.** The new balance is the locked one plus [delta], exactly.
     *
     * **Why the lock comes first.** M2 seeded with `INSERT IGNORE` and then updated, and that order
     * deadlocked in production: an ignored duplicate takes a *shared* lock on the existing row, the
     * update needs an exclusive one, and two deposits to one player each held S while waiting for X.
     * InnoDB killed one and the deposit was lost. Taking X first leaves nothing to upgrade, so two
     * writers to one row simply queue (DATA_MODEL.md §4).
     *
     * Returns `null` when the guard refused, which is a routine outcome and not an error
     * (CODING_STANDARDS.md §4).
     */
    override suspend fun adjust(id: AccountId, currency: Currency, delta: BigDecimal): BigDecimal? =
        withContext(dispatcher) {
            connections.use { connection ->
                inTransaction(connection) {
                    val current = lockRow(connection, id, currency)
                    if (applyDelta(connection, id, currency, delta)) current + delta else null
                }
            }
        }

    /** Locks in [ROW_ORDER] — see the port for why the order is the whole point. */
    override suspend fun lock(rows: Collection<Pair<AccountId, Currency>>) {
        withContext(dispatcher) {
            connections.use { connection ->
                inTransaction(connection) {
                    rows.distinctBy { (id, currency) -> Triple(id, currency.code, scopes.keyFor(currency)) }
                        .sortedWith(ROW_ORDER)
                        .forEach { (id, currency) -> lockRow(connection, id, currency) }
                }
            }
        }
    }

    /** Chunked so a full server's worth of ids never exceeds a driver's bind-parameter limit. */
    override suspend fun getAll(ids: Collection<AccountId>, currency: Currency): Map<AccountId, BigDecimal> =
        withContext(dispatcher) {
            connections.use { connection ->
                ids.distinct().chunked(IN_CHUNK).flatMap { chunk ->
                    connection.prepareStatement(
                        "SELECT account_id, amount FROM gk_balance WHERE currency_code = ? AND scope_key = ? " +
                            "AND account_id IN (${chunk.joinToString(", ") { "?" }})",
                    ).use { statement ->
                        statement.setString(1, currency.code.value)
                        statement.setString(2, scopes.keyFor(currency))
                        chunk.forEachIndexed { index, id -> dialect.bindAccountId(statement, index + 3, id) }
                        statement.executeQuery().use { rows ->
                            buildList {
                                while (rows.next()) {
                                    add(dialect.readAccountId(rows, "account_id") to dialect.readMoney(rows, "amount"))
                                }
                            }
                        }
                    }
                }.toMap()
            }
        }

    /**
     * The [limit] richest accounts in [currency], within that currency's scope.
     *
     * Ranks only rows sharing the resolved scope key, so a per-server currency's `/baltop` shows this
     * server's players and a network currency's shows everyone (DATA_MODEL.md §7). Ordering by
     * `amount` is correct on SQLite only because the column is an INTEGER of minor units — the
     * decimal-string encoding considered in DATA_MODEL.md §3 would have sorted `-9` above `-1`.
     */
    override suspend fun top(currency: Currency, limit: Int): List<Pair<AccountId, BigDecimal>> =
        withContext(dispatcher) {
            connections.use { connection ->
                connection.prepareStatement(
                    "SELECT account_id, amount FROM gk_balance WHERE currency_code = ? AND scope_key = ? " +
                        "ORDER BY amount DESC LIMIT ?",
                ).use { statement ->
                    statement.setString(1, currency.code.value)
                    statement.setString(2, scopes.keyFor(currency))
                    statement.setInt(3, limit)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(dialect.readAccountId(rows, "account_id") to dialect.readMoney(rows, "amount"))
                            }
                        }
                    }
                }
            }
        }

    /**
     * Takes the row's write lock and returns its balance, creating the row at zero if it is missing.
     *
     * The create is a plain `INSERT`, not `INSERT IGNORE` — the ignore is precisely the shared lock
     * this class no longer takes. If another transaction created the row meanwhile, the insert waits
     * for it to commit and then fails on the duplicate key; that is a [WriteConflict], and the retry
     * finds the row and locks it normally. A missing *account* fails the foreign key the same way, and
     * the retry's `exists` check reports it as `AccountNotFound`.
     *
     * Relies on READ COMMITTED on MariaDB (`DataSourceFactory`): under REPEATABLE READ a locking read
     * that finds nothing takes a *gap* lock, and two transactions holding one gap would deadlock on
     * the inserts that follow — the very thing this exists to stop.
     */
    private fun lockRow(connection: Connection, id: AccountId, currency: Currency): BigDecimal {
        read(connection, id, currency, dialect.lockingRead)?.let { return it }
        try {
            connection.prepareStatement(
                "INSERT INTO gk_balance (account_id, currency_code, scope_key, amount) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                bindKey(statement, id, currency)
                dialect.bindMoney(statement, 4, BigDecimal.ZERO)
                statement.executeUpdate()
            }
        } catch (e: SQLException) {
            if (dialect.isConstraintViolation(e)) throw WriteConflict(e)
            throw e
        }
        return ZERO
    }

    /**
     * Applies [delta] if the overdraft rule allows the result.
     *
     * The guard clause is omitted entirely when overdraft is on, rather than passed a flag: a
     * `WHERE ... AND (1 = 1 OR ...)` would leave the database planning around a predicate that is
     * never false.
     *
     * @return whether the row was updated; `false` means the guard refused it.
     */
    private fun applyDelta(connection: Connection, id: AccountId, currency: Currency, delta: BigDecimal): Boolean {
        val guard = if (overdraft.allowsNegativeBalances()) "" else " AND amount + ? >= 0"
        return connection.prepareStatement(
            "UPDATE gk_balance SET amount = amount + ? " +
                "WHERE account_id = ? AND currency_code = ? AND scope_key = ?$guard",
        ).use { statement ->
            dialect.bindMoney(statement, 1, delta)
            bindKey(statement, id, currency, offset = 1)
            if (guard.isNotEmpty()) dialect.bindMoney(statement, 5, delta)
            statement.executeUpdate() > 0
        }
    }

    /** The row's balance, or `null` if there is none; [suffix] makes it a locking read. */
    private fun read(connection: Connection, id: AccountId, currency: Currency, suffix: String = ""): BigDecimal? =
        connection.prepareStatement(
            "SELECT amount FROM gk_balance WHERE account_id = ? AND currency_code = ? AND scope_key = ?$suffix",
        ).use { statement ->
            bindKey(statement, id, currency)
            statement.executeQuery().use { rows -> if (rows.next()) dialect.readMoney(rows, "amount") else null }
        }

    /** Binds the `(account_id, currency_code, scope_key)` key at [offset]+1..[offset]+3. */
    private fun bindKey(statement: PreparedStatement, id: AccountId, currency: Currency, offset: Int = 0) {
        dialect.bindAccountId(statement, offset + 1, id)
        statement.setString(offset + 2, currency.code.value)
        statement.setString(offset + 3, scopes.keyFor(currency))
    }

    /**
     * Runs [block] in a transaction, unless one is already open.
     *
     * Inside [SqlUnitOfWork.transaction] the connection is already non-autocommit and owned by the
     * unit of work, and committing here would break the transfer's atomicity by committing its debit
     * before its credit. Deferring to the ambient transaction when there is one is what lets [adjust]
     * be equally correct called directly or as one step of a transfer — and then the unit of work,
     * not this, decides whether a conflict is retried.
     *
     * Standalone, a conflict is retried here: [block] is all there is to re-run, and the writer it
     * lost to has committed by the time we hear of it, so the next attempt finds the row and wins.
     */
    private fun <T> inTransaction(connection: Connection, block: () -> T): T {
        if (!connection.autoCommit) return block()
        connection.autoCommit = false
        try {
            var attempt = 1
            while (true) {
                try {
                    val result = block()
                    connection.commit()
                    return result
                } catch (e: Throwable) {
                    runCatching { connection.rollback() }
                    if (attempt++ >= MAX_WRITE_ATTEMPTS || !isWriteConflict(e)) throw e
                }
            }
        } finally {
            connection.autoCommit = true
        }
    }

    private companion object {

        /** What [lockRow] reports for a row it just created, at the scale [SqlDialect.readMoney] returns. */
        val ZERO: BigDecimal = BigDecimal.ZERO.setScale(SqlDialect.MONEY_SCALE)

        /** Ids per `IN (…)`: well inside every driver's bind limit, and few enough round trips. */
        const val IN_CHUNK = 500

        /**
         * The one order every multi-row lock is taken in, on every server: account id as unsigned
         * big-endian halves (MariaDB's own `BINARY(16)` index order), then currency. The scope key is
         * a function of the currency, so it needs no place of its own. Any total order would prevent
         * the cycle; this one also walks the primary key forwards.
         */
        val ROW_ORDER: Comparator<Pair<AccountId, Currency>> =
            Comparator<Pair<AccountId, Currency>> { a, b ->
                java.lang.Long.compareUnsigned(a.first.value.mostSignificantBits, b.first.value.mostSignificantBits)
            }
                .thenComparator { a, b ->
                    java.lang.Long.compareUnsigned(a.first.value.leastSignificantBits, b.first.value.leastSignificantBits)
                }
                .thenBy { it.second.code.value }
    }
}
