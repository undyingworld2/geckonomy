package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.domain.model.Account
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.port.AccountRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * [AccountRepository] over SQL.
 *
 * Accounts carry no scope key: an account exists network-wide even when its balances do not
 * (DATA_MODEL.md §7). Two servers sharing a database share the account row and disagree only about
 * what it holds.
 */
class SqlAccountRepository(
    private val connections: ConnectionSource,
    private val dialect: SqlDialect,
    private val dispatcher: CoroutineDispatcher,
) : AccountRepository {

    /**
     * Idempotent through `INSERT OR IGNORE` rather than exists-then-insert: two callers racing to
     * create the same player — a join listener and a Vault plugin — must not turn into a primary key
     * violation, and only the database can decide that race (SPEC.md FR-A1).
     */
    override suspend fun create(account: Account): Boolean = withContext(dispatcher) {
        connections.use { connection ->
            connection.prepareStatement(
                dialect.insertOrIgnore("gk_account", listOf("id", "name", "type", "created_at")),
            ).use { statement ->
                dialect.bindAccountId(statement, 1, account.id)
                statement.setString(2, account.name)
                statement.setString(3, account.type.name)
                statement.setLong(4, account.createdAt.toEpochMilli())
                statement.executeUpdate() > 0
            }
        }
    }

    override suspend fun exists(id: AccountId): Boolean = withContext(dispatcher) {
        connections.use { connection ->
            connection.prepareStatement("SELECT 1 FROM gk_account WHERE id = ?").use { statement ->
                dialect.bindAccountId(statement, 1, id)
                statement.executeQuery().use { it.next() }
            }
        }
    }

    override suspend fun findName(id: AccountId): String? = withContext(dispatcher) {
        connections.use { connection ->
            connection.prepareStatement("SELECT name FROM gk_account WHERE id = ?").use { statement ->
                dialect.bindAccountId(statement, 1, id)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString("name") else null }
            }
        }
    }

    /**
     * Every account's name.
     *
     * Unbounded because Vault's `getUUIDNameMap` is unbounded (DATA_MODEL.md §8) — the port cannot
     * page what its caller will not. Fine at the scale of one server's player list, and the only
     * caller reads it once per join.
     */
    override suspend fun nameMap(): Map<AccountId, String> = withContext(dispatcher) {
        connections.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id, name FROM gk_account").use { rows ->
                    buildMap { while (rows.next()) put(dialect.readAccountId(rows, "id"), rows.getString("name")) }
                }
            }
        }
    }

    override suspend fun rename(id: AccountId, name: String): Boolean = withContext(dispatcher) {
        connections.use { connection ->
            connection.prepareStatement("UPDATE gk_account SET name = ? WHERE id = ?").use { statement ->
                statement.setString(1, name)
                dialect.bindAccountId(statement, 2, id)
                statement.executeUpdate() > 0
            }
        }
    }

    /**
     * Deletes the account and, by cascade, its balances (DATA_MODEL.md §6).
     *
     * One statement rather than two, because the balances go through `gk_balance`'s
     * `ON DELETE CASCADE`. That keeps the deletion atomic without a transaction — two statements on
     * an autocommit connection could leave an account whose money had already been erased.
     *
     * The cascade relies on SQLite's `foreign_keys` pragma, which [DataSourceFactory] sets per
     * connection and a future edit could silently drop. `deleting an account removes its balances`
     * in the repository suite exists to catch exactly that, which is a better guard than a defensive
     * second `DELETE` here: the test fails loudly, a redundant statement would hide the breakage.
     *
     * The ledger is untouched; `settings.keep-transaction-history` governs it (DATA_MODEL.md §6) and
     * the application layer applies that rule.
     */
    override suspend fun delete(id: AccountId): Boolean = withContext(dispatcher) {
        connections.use { connection ->
            connection.prepareStatement("DELETE FROM gk_account WHERE id = ?").use { statement ->
                dialect.bindAccountId(statement, 1, id)
                statement.executeUpdate() > 0
            }
        }
    }
}
