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
     * Three statements in one transaction, not the "single UPDATE" DATA_MODEL.md §4 first imagined:
     *
     * 1. **Seed.** `INSERT OR IGNORE` a zero row. A missing row means zero, and a currency added to
     *    config after an account exists has no row until something touches it — without this, the
     *    first deposit into a new currency would update nothing and silently report success.
     * 2. **Guard and update.** `SET amount = amount + delta WHERE ... AND amount + delta >= 0`. The
     *    read and the write are one statement, so two concurrent withdrawals cannot both observe a
     *    sufficient balance and jointly overdraw it. Zero rows updated means the guard refused.
     * 3. **Read back.** The new balance. Separate because MariaDB has no `UPDATE ... RETURNING`, and
     *    a dialect split here would buy one round trip at the cost of the guarantee below.
     *
     * The transaction is what makes step 3 trustworthy: without it, another server on the same
     * MariaDB could change the balance between the update and the read, and we would return a number
     * that was never the result of *this* call. Inside a transaction the three steps are one
     * indivisible operation.
     *
     * Returns `null` when the guard refused, which is a routine outcome and not an error
     * (CODING_STANDARDS.md §4).
     */
    override suspend fun adjust(id: AccountId, currency: Currency, delta: BigDecimal): BigDecimal? =
        withContext(dispatcher) {
            connections.use { connection ->
                inTransaction(connection) {
                    seed(connection, id, currency)
                    if (!applyDelta(connection, id, currency, delta)) return@inTransaction null
                    // Non-null: the update above touched the row, so it exists.
                    read(connection, id, currency)
                }
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

    /** Creates the row at zero if it is absent; does nothing if it exists. */
    private fun seed(connection: Connection, id: AccountId, currency: Currency) {
        connection.prepareStatement(
            dialect.insertOrIgnore("gk_balance", listOf("account_id", "currency_code", "scope_key", "amount")),
        ).use { statement ->
            bindKey(statement, id, currency)
            dialect.bindMoney(statement, 4, BigDecimal.ZERO)
            statement.executeUpdate()
        }
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

    private fun read(connection: Connection, id: AccountId, currency: Currency): BigDecimal? =
        connection.prepareStatement(
            "SELECT amount FROM gk_balance WHERE account_id = ? AND currency_code = ? AND scope_key = ?",
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
     * be equally correct called directly or as one step of a transfer.
     */
    private fun <T> inTransaction(connection: Connection, block: () -> T): T {
        if (!connection.autoCommit) return block()
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (e: Throwable) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            connection.autoCommit = true
        }
    }
}
