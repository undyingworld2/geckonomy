package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.domain.policy.OverdraftPolicy
import com.the1mason.geckonomy.domain.port.AccountRepository
import com.the1mason.geckonomy.domain.port.BalanceRepository
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import com.the1mason.geckonomy.domain.port.TransactionLog
import com.the1mason.geckonomy.domain.port.TxContext
import com.the1mason.geckonomy.domain.port.UnitOfWork
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.sql.DataSource

/**
 * [UnitOfWork] over one JDBC transaction — what makes a transfer atomic (ARCHITECTURE.md §5).
 *
 * Takes the [DataSource] rather than a [ConnectionSource] because it is the thing that *decides* the
 * connection: it borrows one, turns off autocommit, and builds a [TxContext] whose repositories are
 * pinned to it. Repositories reached any other way would commit independently, which for a transfer
 * means debiting one account without crediting the other.
 */
class SqlUnitOfWork(
    private val dataSource: DataSource,
    private val dialect: SqlDialect,
    private val scopes: ScopeResolver,
    private val overdraft: OverdraftPolicy,
    private val currencies: CurrencyRegistry,
    private val dispatcher: CoroutineDispatcher,
) : UnitOfWork {

    /**
     * Runs [block] in one transaction: everything inside commits together or not at all.
     *
     * Any throwable rolls back and propagates — including [kotlinx.coroutines.CancellationException],
     * so a player quitting mid-transfer cannot leave half of one committed.
     *
     * Fresh repositories per call rather than cached: they are cheap, and one bound to a finished
     * transaction's connection is a bug waiting to be reused.
     */
    override suspend fun <T> transaction(block: suspend (TxContext) -> T): T = withContext(dispatcher) {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val result = block(context(ConnectionSource.Pinned(connection)))
                connection.commit()
                result
            } catch (e: Throwable) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                // Restored before the connection returns to the pool: Hikari hands it to the next
                // caller as-is, and one that silently never commits would be a long night.
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    private fun context(connections: ConnectionSource): TxContext = object : TxContext {
        override val accounts: AccountRepository = SqlAccountRepository(connections, dialect, dispatcher)
        override val balance: BalanceRepository =
            SqlBalanceRepository(connections, dialect, scopes, overdraft, dispatcher)
        override val log: TransactionLog = SqlTransactionLog(connections, dialect, scopes, currencies, dispatcher)
    }
}
