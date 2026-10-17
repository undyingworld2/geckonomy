package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.service.EconomyService
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.infrastructure.config.ConfigCurrencyRegistry
import com.the1mason.geckonomy.infrastructure.config.PoolConfig
import com.the1mason.geckonomy.infrastructure.config.StorageConfig
import com.the1mason.geckonomy.infrastructure.config.StorageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.SQLTransactionRollbackException
import java.util.UUID
import javax.sql.DataSource

/**
 * [SqlUnitOfWork]'s two answers to a write whose first attempt failed (DATA_MODEL.md §4): a conflict
 * is run again, and a commit that threw is settled by looking for its ledger row — never guessed.
 *
 * On SQLite with faults injected at the JDBC boundary, because neither can be produced on demand
 * against a real database: InnoDB picks its own deadlock victims, and a lost commit acknowledgement is
 * a network event. What matters is what the unit of work *does* about them, and that is the same code
 * on either backend.
 */
class SqlUnitOfWorkRetryTest {

    @TempDir
    lateinit var directory: Path

    private lateinit var harness: PersistenceHarness
    private lateinit var faults: Faults
    private lateinit var service: EconomyService

    private val alice = AccountId(UUID.fromString("00000000-0000-0000-0000-00000000a11c"))
    private val coins = TestCurrencies.COINS.code

    @BeforeEach
    fun setUp() {
        harness = PersistenceHarness(storage(), SqliteDialect)
        faults = Faults(harness.dataSource)
        service = economyOver(harness, SqlUnitOfWork(
            faults.dataSource,
            SqliteDialect,
            harness.scopes,
            harness.overdraft,
            ConfigCurrencyRegistry(listOf(TestCurrencies.COINS, TestCurrencies.GEMS)),
            Dispatchers.IO,
        ))
        runBlocking { service.createAccount(alice, "Alice") }
    }

    @AfterEach
    fun tearDown() = harness.close()

    @Test
    fun `a deadlock victim is run again and lands once`() = runBlocking {
        faults.failNextUpdate = SQLTransactionRollbackException("Deadlock found", "40001", 1213)

        val result = service.deposit(alice, BigDecimal("1.00"), coins)

        assertEquals(BigDecimal("101.00"), (result as Outcome.Success).value.balance.amount)
        assertEquals(BigDecimal("101.00"), balance())
        assertEquals(1, ledgerRows())
    }

    @Test
    fun `a commit that happened but reported failure is not applied again`() = runBlocking {
        faults.commitThenFail = true

        val result = service.deposit(alice, BigDecimal("1.00"), coins)

        assertEquals(BigDecimal("101.00"), (result as Outcome.Success).value.balance.amount)
        assertEquals(BigDecimal("101.00"), balance(), "applied exactly once")
        assertEquals(1, ledgerRows())
    }

    @Test
    fun `a commit that failed and did not happen is run again`() = runBlocking {
        faults.failCommitWithoutCommitting = true

        val result = service.deposit(alice, BigDecimal("1.00"), coins)

        assertEquals(BigDecimal("101.00"), (result as Outcome.Success).value.balance.amount)
        assertEquals(BigDecimal("101.00"), balance())
        assertEquals(1, ledgerRows())
    }

    @Test
    fun `an error that is not a conflict is reported, not retried`() = runBlocking {
        faults.failNextUpdate = SQLException("disk I/O error")

        val result = service.deposit(alice, BigDecimal("1.00"), coins)

        assertInstanceOf(Outcome.Failure::class.java, result)
        assertEquals(BigDecimal("100.00"), balance())
        assertEquals(0, ledgerRows())
    }

    @Test
    fun `conflicts past the attempt limit are reported`() = runBlocking {
        faults.failEveryUpdate = SQLTransactionRollbackException("Deadlock found", "40001", 1213)

        val result = service.deposit(alice, BigDecimal("1.00"), coins)

        assertInstanceOf(Outcome.Failure::class.java, result)
        assertEquals(MAX_WRITE_ATTEMPTS, faults.updates)
        assertEquals(BigDecimal("100.00"), balance())
    }

    // ── Wiring ──────────────────────────────────────────────────────────

    private suspend fun balance(): BigDecimal = (service.balance(alice, coins) as Outcome.Success).value.amount

    private fun ledgerRows(): Int = harness.query { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM gk_transaction").use { statement ->
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
        pool = PoolConfig(maximumPoolSize = 1, minimumIdle = 1, connectionTimeoutMs = 2_000),
    )

    /**
     * A [DataSource] over [real] that fails where it is told to. Only `UPDATE`s are counted and
     * failed — the balance write — so an account lookup or a ledger insert passes through untouched.
     */
    private class Faults(private val real: DataSource) {
        var failNextUpdate: SQLException? = null
        var failEveryUpdate: SQLException? = null
        var commitThenFail = false
        var failCommitWithoutCommitting = false
        var updates = 0

        val dataSource: DataSource = proxy(real) { method, args ->
            if (method.name == "getConnection") connection(real.connection) else method.invoke(real, *args.orEmpty())
        }

        private fun connection(connection: Connection): Connection = proxy(connection) { method, args ->
            when {
                method.name == "commit" && commitThenFail -> {
                    commitThenFail = false
                    connection.commit()
                    throw SQLException("connection reset while reading the commit acknowledgement")
                }
                method.name == "commit" && failCommitWithoutCommitting -> {
                    failCommitWithoutCommitting = false
                    connection.rollback()
                    throw SQLException("connection reset before the commit reached the server")
                }
                method.name == "prepareStatement" && (args?.get(0) as? String)?.startsWith("UPDATE") == true ->
                    statement(method.invoke(connection, *args) as PreparedStatement)
                else -> method.invoke(connection, *args.orEmpty())
            }
        }

        private fun statement(statement: PreparedStatement): PreparedStatement = proxy(statement) { method, args ->
            if (method.name == "executeUpdate") {
                updates++
                failEveryUpdate?.let { throw it }
                failNextUpdate?.let { failNextUpdate = null; throw it }
            }
            method.invoke(statement, *args.orEmpty())
        }

        private inline fun <reified T : Any> proxy(
            target: T,
            crossinline handler: (java.lang.reflect.Method, Array<Any?>?) -> Any?,
        ): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            try {
                handler(method, args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as T
    }
}
