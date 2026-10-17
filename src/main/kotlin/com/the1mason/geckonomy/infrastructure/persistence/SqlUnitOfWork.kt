package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.Transaction
import com.the1mason.geckonomy.domain.policy.OverdraftPolicy
import com.the1mason.geckonomy.domain.port.AccountRepository
import com.the1mason.geckonomy.domain.port.BalanceRepository
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import com.the1mason.geckonomy.domain.port.TransactionLog
import com.the1mason.geckonomy.domain.port.TxContext
import com.the1mason.geckonomy.domain.port.UnitOfWork
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlin.random.Random

/**
 * [UnitOfWork] over one JDBC transaction — what makes a transfer atomic (ARCHITECTURE.md §5).
 *
 * Takes the [DataSource] rather than a [ConnectionSource] because it is the thing that *decides* the
 * connection: it borrows one, turns off autocommit, and builds a [TxContext] whose repositories are
 * pinned to it. Repositories reached any other way would commit independently, which for a transfer
 * means debiting one account without crediting the other.
 *
 * Three things on top of the transaction itself, each answering one way a write used to be lost
 * (DATA_MODEL.md §4):
 * - **[gate]**: our own writers to one account queue in memory instead of on its row lock.
 * - **Retry**: a transaction that lost a lock conflict ([isWriteConflict]) was rolled back whole, so
 *   it runs again, up to [MAX_WRITE_ATTEMPTS] times in all.
 * - **Commit verification**: a commit that *throws* has an unknown outcome — the server may have
 *   committed and the acknowledgement been lost. Reporting failure then tells a delivery plugin to pay
 *   again; retrying blindly pays twice. So the transaction's first ledger row is looked up on a fresh
 *   connection: present means it committed and the result stands, absent means it rolled back and is
 *   safe to retry.
 */
class SqlUnitOfWork(
    private val dataSource: DataSource,
    private val dialect: SqlDialect,
    private val scopes: ScopeResolver,
    private val overdraft: OverdraftPolicy,
    private val currencies: CurrencyRegistry,
    private val dispatcher: CoroutineDispatcher,
) : UnitOfWork {

    private val gate = AccountWriteGate()

    /**
     * Runs [block] in one transaction: everything inside commits together or not at all.
     *
     * Any throwable rolls back and propagates — including [kotlinx.coroutines.CancellationException],
     * so a player quitting mid-transfer cannot leave half of one committed — unless it is a conflict
     * with attempts left, which rolls back and runs [block] again.
     *
     * Fresh repositories per attempt rather than cached: they are cheap, and one bound to a finished
     * transaction's connection is a bug waiting to be reused.
     */
    override suspend fun <T> transaction(writes: Set<AccountId>, block: suspend (TxContext) -> T): T =
        gate.withAccounts(writes) { retrying(block) }

    private suspend fun <T> retrying(block: suspend (TxContext) -> T): T {
        var attempt = 1
        while (true) {
            try {
                return attemptOnce(block)
            } catch (e: Throwable) {
                if (attempt >= MAX_WRITE_ATTEMPTS || !isWriteConflict(e)) throw e
                // Jittered, and growing: two servers that just deadlocked each other would otherwise
                // retry in lockstep and meet again.
                delay(Random.nextLong(BACKOFF_MS, BACKOFF_MS * 4) * attempt)
                attempt++
            }
        }
    }

    private suspend fun <T> attemptOnce(block: suspend (TxContext) -> T): T = withContext(dispatcher) {
        var unsettled: UnsettledCommit? = null
        val result = dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val log = MarkingLog(SqlTransactionLog(ConnectionSource.Pinned(connection), dialect, scopes, currencies, dispatcher))
                val result = block(context(ConnectionSource.Pinned(connection), log))
                try {
                    connection.commit()
                } catch (e: SQLException) {
                    // No marker — a transaction that wrote no ledger row — means nothing to look up,
                    // so the failure stands as it always did.
                    unsettled = UnsettledCommit(e, log.first ?: throw e)
                    runCatching { connection.rollback() }
                }
                result
            } catch (e: Throwable) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                // Restored before the connection returns to the pool: Hikari hands it to the next
                // caller as-is, and one that silently never commits would be a long night.
                runCatching { connection.autoCommit = previousAutoCommit }
            }
        }
        // Settled only once the connection is back: SQLite's pool holds exactly one, and looking up
        // the marker while still holding it would wait out the pool timeout on ourselves.
        unsettled?.let(::settle)
        result
    }

    /**
     * Decides whether a commit that threw happened, by looking for its first ledger row.
     *
     * Present: it committed, and returning normally lets the caller's result stand. Absent: it did not,
     * and [WriteConflict] has the retry loop run it again like any rolled-back conflict. If even the
     * lookup fails, the outcome stays unknown and the commit's own failure is reported.
     */
    private fun settle(commit: UnsettledCommit) {
        val committed = try {
            dataSource.connection.use { fresh -> ledgerHas(fresh, commit.marker) }
        } catch (lookup: SQLException) {
            commit.failure.addSuppressed(lookup)
            throw commit.failure
        }
        if (!committed) throw WriteConflict(commit.failure)
    }

    /** A commit that threw, and the ledger row that says whether it happened anyway. */
    private class UnsettledCommit(val failure: SQLException, val marker: UUID)

    private fun ledgerHas(connection: Connection, id: UUID): Boolean =
        connection.prepareStatement("SELECT 1 FROM gk_transaction WHERE id = ?").use { statement ->
            dialect.bindUuid(statement, 1, id)
            statement.executeQuery().use { it.next() }
        }

    private fun context(connections: ConnectionSource, ledger: TransactionLog): TxContext = object : TxContext {
        override val accounts: AccountRepository = SqlAccountRepository(connections, dialect, dispatcher)
        override val balance: BalanceRepository =
            SqlBalanceRepository(connections, dialect, scopes, overdraft, dispatcher)
        override val log: TransactionLog = ledger
    }

    /** Remembers the first row appended, as the transaction's marker for [commit]. */
    private class MarkingLog(private val delegate: TransactionLog) : TransactionLog by delegate {
        var first: UUID? = null
            private set

        override suspend fun append(tx: Transaction) {
            delegate.append(tx)
            if (first == null) first = tx.id
        }
    }

    private companion object {
        /** Base of the jittered retry delay; a lock conflict clears in about one transaction's time. */
        const val BACKOFF_MS = 5L
    }
}
