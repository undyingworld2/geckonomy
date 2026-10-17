package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.service.EconomyService
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.infrastructure.config.PoolConfig
import com.the1mason.geckonomy.infrastructure.config.StorageConfig
import com.the1mason.geckonomy.infrastructure.config.StorageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.sql.DriverManager
import java.util.UUID

/**
 * M13's acceptance: concurrent writes to one balance row, from two servers sharing one MariaDB, lose
 * nothing.
 *
 * Written against the production deadlock first (two deposits to one `coins` row, each holding the S
 * lock `INSERT IGNORE` takes on an existing row while waiting for the X lock its `UPDATE` needs), and
 * red on the M12 code: InnoDB killed one side, `StorageGuard` reported it, and the deposit was gone.
 *
 * Two [PersistenceHarness]es over one database are two servers: separate pools, separate IO threads,
 * no shared memory — so nothing in-process can serialize them and only the SQL can be correct.
 */
@Testcontainers(disabledWithoutDocker = true)
class MariaDbConcurrencyTest {

    private lateinit var serverA: PersistenceHarness
    private lateinit var serverB: PersistenceHarness
    private lateinit var economyA: EconomyService
    private lateinit var economyB: EconomyService

    private val alice = AccountId(UUID.fromString("00000000-0000-0000-0000-00000000a11c"))
    private val bob = AccountId(UUID.fromString("00000000-0000-0000-0000-0000000000b0"))
    private val coins = TestCurrencies.COINS.code

    @BeforeEach
    fun setUp() {
        wipe()
        serverA = PersistenceHarness(storage(), MariaDbDialect, serverId = "server-a")
        serverB = PersistenceHarness(storage(), MariaDbDialect, serverId = "server-b")
        economyA = economyOver(serverA)
        economyB = economyOver(serverB)
    }

    @AfterEach
    fun tearDown() {
        serverA.close()
        serverB.close()
    }

    /**
     * The production incident. Asserts on InnoDB's own deadlock counter as well as the outcome: a
     * retry would make the outcome pass on its own, and the point is that the deadlock no longer
     * happens at all.
     */
    @Test
    fun `concurrent deposits to one account from two servers all land`() = runBlocking {
        economyA.createAccount(alice, "Alice")
        val deadlocksBefore = deadlocks()

        val results = burst(PER_SERVER) { economy -> economy.deposit(alice, BigDecimal("1.00"), coins) }

        assertEquals(emptyList<Outcome<*>>(), results.filterIsInstance<Outcome.Failure>())
        assertEquals(BigDecimal("100.00").add(BigDecimal(2 * PER_SERVER)), balanceOf(alice))
        assertEquals(2 * PER_SERVER, ledgerRows(alice))
        assertEquals(deadlocksBefore, deadlocks(), "no lock-upgrade deadlock, rather than one retried")
    }

    /**
     * No balance row yet — a currency added to config after the account existed — so every first
     * deposit races to create it. One insert wins; the rest wait, fail on the duplicate key, and are
     * retried onto the row the winner made.
     */
    @Test
    fun `first deposits racing to create a balance row all land`() = runBlocking {
        economyA.createAccount(alice, "Alice")
        serverA.query { connection ->
            connection.prepareStatement("DELETE FROM gk_balance").use { it.executeUpdate() }
        }
        val deadlocksBefore = deadlocks()

        val results = burst(PER_SERVER) { economy -> economy.deposit(alice, BigDecimal("1.00"), coins) }

        assertEquals(emptyList<Outcome<*>>(), results.filterIsInstance<Outcome.Failure>())
        assertEquals(BigDecimal(2 * PER_SERVER).setScale(2), balanceOf(alice))
        assertEquals(deadlocksBefore, deadlocks(), "READ COMMITTED: no gap locks to deadlock on")
    }

    @Test
    fun `opposite transfers between two accounts never deadlock and conserve money`() = runBlocking {
        economyA.createAccount(alice, "Alice")
        economyA.createAccount(bob, "Bob")
        val deadlocksBefore = deadlocks()

        val results = runBursts(
            { economyA.transfer(alice, bob, BigDecimal("1.00"), coins) },
            { economyB.transfer(bob, alice, BigDecimal("1.00"), coins) },
        )

        assertEquals(emptyList<Outcome<*>>(), results.filterIsInstance<Outcome.Failure>())
        assertEquals(BigDecimal("100.00"), balanceOf(alice))
        assertEquals(BigDecimal("100.00"), balanceOf(bob))
        assertEquals(deadlocksBefore, deadlocks(), "both rows locked in one global order")
    }

    @Test
    fun `concurrent withdrawals at the overdraft edge refuse exactly the excess`() = runBlocking {
        economyA.createAccount(alice, "Alice")

        // 100.00 to spend, 2 × 75 withdrawals of 1.00: exactly 100 succeed, 50 are refused.
        val results = burst(75) { economy -> economy.withdraw(alice, BigDecimal("1.00"), coins) }

        val failures = results.filterIsInstance<Outcome.Failure>().map { it.error }
        assertEquals(50, failures.size)
        assertEquals(failures, failures.filterIsInstance<EconomyError.InsufficientFunds>())
        assertEquals(BigDecimal("0.00"), balanceOf(alice))
        assertEquals(100, ledgerRows(alice))
    }

    // ── Wiring ──────────────────────────────────────────────────────────

    /** [perServer] calls of [op] on each server at once. */
    private suspend fun burst(perServer: Int, op: suspend (EconomyService) -> Outcome<*>): List<Outcome<*>> =
        runBursts({ op(economyA) }, { op(economyB) }, perServer)

    private suspend fun runBursts(
        onA: suspend () -> Outcome<*>,
        onB: suspend () -> Outcome<*>,
        perServer: Int = PER_SERVER,
    ): List<Outcome<*>> = kotlinx.coroutines.coroutineScope {
        (1..perServer).flatMap { listOf(async(Dispatchers.Default) { onA() }, async(Dispatchers.Default) { onB() }) }
            .awaitAll()
    }

    private suspend fun balanceOf(id: AccountId): BigDecimal =
        (economyA.balance(id, coins) as Outcome.Success).value.amount

    /** InnoDB's count of deadlocks detected since the server started. */
    private fun deadlocks(): Long = serverA.query { connection ->
        connection.prepareStatement("SHOW GLOBAL STATUS LIKE 'Innodb_deadlocks'").use { statement ->
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getLong(2)
            }
        }
    }

    private fun ledgerRows(id: AccountId): Int = serverA.query { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM gk_transaction WHERE account_id = ?").use { statement ->
            MariaDbDialect.bindAccountId(statement, 1, id)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun storage() = StorageConfig(
        type = StorageType.MARIADB,
        file = null,
        host = container.host,
        port = container.firstMappedPort,
        database = container.databaseName,
        username = container.username,
        password = container.password,
        properties = emptyMap(),
        pool = PoolConfig(maximumPoolSize = 4, minimumIdle = 1, connectionTimeoutMs = 10_000),
    )

    /** Before the harnesses open, so the first test's migration runs against an empty schema too. */
    private fun wipe() {
        val url = "jdbc:mariadb://${container.host}:${container.firstMappedPort}/${container.databaseName}"
        DriverManager.getConnection(url, container.username, container.password).use { connection ->
            connection.createStatement().use { statement ->
                val tables = statement.executeQuery("SHOW TABLES LIKE 'gk\\_%'").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
                MariaDbRepositoryTest.TABLES_IN_FK_ORDER.filter { it in tables }
                    .forEach { table -> statement.executeUpdate("DELETE FROM $table") }
            }
        }
    }

    companion object {
        private const val PER_SERVER = 50

        @Container
        @JvmStatic
        val container: MariaDBContainer<*> = MariaDBContainer("mariadb:11.4")
    }
}
