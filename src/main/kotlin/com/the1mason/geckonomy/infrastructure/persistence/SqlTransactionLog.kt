package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.Transaction
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import com.the1mason.geckonomy.domain.port.TransactionLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * [TransactionLog] over SQL — the append-only audit ledger (DATA_MODEL.md §1).
 *
 * @param currencies needed only to resolve a [Transaction]'s [com.the1mason.geckonomy.domain.model.CurrencyCode]
 *   back to a whole [com.the1mason.geckonomy.domain.model.Currency], which [ScopeResolver] needs to
 *   key the row. The ledger row must carry the same `scope_key` as the balance it records, or a
 *   per-server currency's history would not line up with its balance.
 */
class SqlTransactionLog(
    private val connections: ConnectionSource,
    private val dialect: SqlDialect,
    private val scopes: ScopeResolver,
    private val currencies: CurrencyRegistry,
    private val dispatcher: CoroutineDispatcher,
) : TransactionLog {

    /**
     * A plain `INSERT`: the ledger has no upsert because it has no update. A duplicate id is a
     * programmer error and the primary key should say so rather than quietly overwrite an audit row.
     */
    override suspend fun append(tx: Transaction) {
        withContext(dispatcher) {
            val currency = currencies.byCode(tx.currency)
                ?: throw LedgerFailure("Cannot log a transaction in unknown currency '${tx.currency}'")
            connections.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO gk_transaction (id, account_id, currency_code, scope_key, delta, " +
                        "resulting_balance, type, source_plugin, counterparty_id, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    dialect.bindUuid(statement, 1, tx.id)
                    dialect.bindAccountId(statement, 2, tx.accountId)
                    statement.setString(3, tx.currency.value)
                    statement.setString(4, scopes.keyFor(currency))
                    dialect.bindMoney(statement, 5, tx.delta)
                    dialect.bindMoney(statement, 6, tx.resultingBalance)
                    statement.setString(7, tx.type.name)
                    statement.setString(8, tx.sourcePlugin)
                    dialect.bindNullableAccountId(statement, 9, tx.counterparty)
                    statement.setLong(10, tx.createdAt.toEpochMilli())
                    statement.executeUpdate()
                }
            }
        }
    }

    /**
     * Deletes every row for [id], across **all** scope keys.
     *
     * Not filtered by scope, unlike everything else here: the caller is deleting the account, and an
     * account is not per-server. Leaving another server's rows behind would keep exactly the history
     * the operator asked to erase, keyed under an id that no longer resolves to anything.
     */
    override suspend fun purge(id: AccountId): Int = withContext(dispatcher) {
        connections.use { connection ->
            connection.prepareStatement("DELETE FROM gk_transaction WHERE account_id = ?").use { statement ->
                dialect.bindAccountId(statement, 1, id)
                statement.executeUpdate()
            }
        }
    }
}

/** The ledger could not record a change. Caught at the application boundary; never thrown at Bukkit. */
class LedgerFailure(message: String) : RuntimeException(message)
