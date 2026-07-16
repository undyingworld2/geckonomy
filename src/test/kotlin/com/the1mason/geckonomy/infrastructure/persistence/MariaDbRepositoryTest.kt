package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.infrastructure.config.PoolConfig
import com.the1mason.geckonomy.infrastructure.config.StorageConfig
import com.the1mason.geckonomy.infrastructure.config.StorageType
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager

/**
 * [RepositoryContract] on MariaDB — the half of M3's acceptance criterion that shipped unproven.
 *
 * Every expectation lives in the contract; this class supplies only a database. That is the point of
 * the shape: MariaDB is where the dialects actually diverge — `BINARY(16)` UUIDs against SQLite's
 * blobs, `DECIMAL(38,4)` against an INTEGER count of minor units, `INSERT ... ON DUPLICATE KEY UPDATE`
 * against `ON CONFLICT` — and the same expectations have to hold across all of it.
 *
 * **One container for the whole class, wiped between tests.** Starting a MariaDB per test would cost
 * seconds apiece across ~35 tests for no isolation the wipe does not already give. The wipe runs in
 * [BeforeEach] rather than after, so a test that dies mid-way cannot poison the next one.
 */
@Testcontainers
class MariaDbRepositoryTest : RepositoryContract() {

    override val dialect: SqlDialect = MariaDbDialect

    /**
     * The same database for every server id, so two harnesses in one test are two servers sharing one
     * database — the arrangement the scope tests are about.
     */
    override fun storageFor(serverId: String): StorageConfig = StorageConfig(
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

    /**
     * Empties the tables, leaving the schema — and `gk_schema_version` with it, so the next harness's
     * [MigrationRunner] correctly finds nothing to apply instead of re-running V001 per test.
     *
     * Runs after the contract's own `@BeforeEach` (JUnit orders the superclass first), which is
     * harmless: that one only opens pools and migrates, and rows deleted underneath an idle pool are
     * simply rows that were never there.
     */
    @BeforeEach
    fun wipe() {
        DriverManager.getConnection(jdbcUrl(), container.username, container.password).use { connection ->
            connection.createStatement().use { statement ->
                // gk_balance before gk_account: the FK between them is real here (InnoDB), unlike on
                // SQLite where it needs a pragma, and MariaDB will not let the parent go first.
                TABLES_IN_FK_ORDER.forEach { table -> statement.executeUpdate("DELETE FROM $table") }
            }
        }
    }

    private fun jdbcUrl(): String = "jdbc:mariadb://${container.host}:${container.firstMappedPort}/${container.databaseName}"

    companion object {

        /**
         * `@JvmStatic` is what makes this one container per class rather than one per test — a
         * non-static `@Container` is started and stopped around every test method.
         */
        @Container
        @JvmStatic
        val container: MariaDBContainer<*> = MariaDBContainer("mariadb:11.4")

        /** Children first: `gk_balance` carries an `ON DELETE CASCADE` FK to `gk_account`. */
        val TABLES_IN_FK_ORDER = listOf("gk_transaction", "gk_balance", "gk_account_member", "gk_account")
    }
}
