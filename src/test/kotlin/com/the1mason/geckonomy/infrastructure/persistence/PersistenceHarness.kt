package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.policy.OverdraftPolicy
import com.the1mason.geckonomy.infrastructure.config.ConfigCurrencyRegistry
import com.the1mason.geckonomy.infrastructure.config.StorageConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection

/**
 * A live persistence stack over a real database — pool, migrations, repositories and all.
 *
 * Assembled the way `Geckonomy.onEnable` assembles it, deliberately: a harness that wired the
 * repositories by hand could pass while the composition root's version of the same wiring was broken,
 * and [DataSourceFactory] and [MigrationRunner] would go untested. The only thing faked here is the
 * currency registry, which is config's business rather than persistence's.
 *
 * @param storage which backend, and where. The one thing each dialect's suite supplies differently.
 * @param serverId scope key for per-server currencies; varied by the scope tests to simulate two
 *   servers sharing one database.
 * @param allowOverdraft compiled into the balance guard, exactly as it is at startup.
 */
class PersistenceHarness(
    storage: StorageConfig,
    val dialect: SqlDialect,
    serverId: String = "test-server",
    allowOverdraft: Boolean = false,
) : AutoCloseable {

    private val io = IoDispatcher.forStorage(storage)
    private val currencies = ConfigCurrencyRegistry(listOf(TestCurrencies.COINS, TestCurrencies.GEMS))

    val dataSource: HikariDataSource = DataSourceFactory().create(storage)
    val scopes = ScopeResolver(serverId)

    /**
     * One instance, shared — as in production, where the same policy backs the repository's compiled
     * SQL guard and M4's `SetBalance` check, so the two cannot disagree about `allow-overdraft`.
     */
    val overdraft = OverdraftPolicy(allowOverdraft)

    val accounts = SqlAccountRepository(ConnectionSource.Pooled(dataSource), dialect, io.dispatcher)
    val balances = SqlBalanceRepository(ConnectionSource.Pooled(dataSource), dialect, scopes, overdraft, io.dispatcher)
    val log = SqlTransactionLog(ConnectionSource.Pooled(dataSource), dialect, scopes, currencies, io.dispatcher)
    val unitOfWork = SqlUnitOfWork(dataSource, dialect, scopes, overdraft, currencies, io.dispatcher)

    init {
        MigrationRunner(dataSource, dialect).migrate()
    }

    /** A raw connection, for asserting on rows no port exposes — the ledger, and scope keys. */
    fun <T> query(block: (Connection) -> T): T = dataSource.connection.use(block)

    override fun close() {
        dataSource.close()
        io.close()
    }
}
