package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.infrastructure.config.PoolConfig
import com.the1mason.geckonomy.infrastructure.config.StorageConfig
import com.the1mason.geckonomy.infrastructure.config.StorageType
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * [MigrationRunner] on SQLite.
 *
 * MariaDB's migration path is untested until Docker is available, and its differences are real —
 * DDL there commits implicitly, so the transaction these tests rely on does not protect it. The
 * `IF NOT EXISTS` on every statement is what covers that case, and only a live MariaDB can prove it.
 */
class MigrationRunnerTest {

    @TempDir
    lateinit var directory: Path

    private lateinit var dataSource: HikariDataSource

    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        dataSource = DataSourceFactory().create(storage())
    }

    @AfterEach
    fun tearDown() = dataSource.close()

    @Test
    fun `applies the initial migration to an empty database`() {
        val applied = runner().migrate()

        assertEquals(listOf(1), applied)
        TABLES.forEach { table -> assertTrue(tableExists(table), "$table should have been created") }
    }

    @Test
    fun `is idempotent`() {
        runner().migrate()

        // The second start of a server that is already migrated. Re-applying V001 would either fail
        // on the existing tables or, worse, quietly recreate them.
        assertEquals(emptyList<Int>(), runner().migrate(), "an up-to-date database needs no migrations")
    }

    @Test
    fun `records what it applied`() {
        runner().migrate()

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version, applied_at FROM gk_schema_version").use { rows ->
                    assertTrue(rows.next(), "the applied migration should have been recorded")
                    assertEquals(1, rows.getInt("version"))
                    assertEquals(clock.millis(), rows.getLong("applied_at"))
                    assertTrue(!rows.next(), "exactly one migration has shipped so far")
                }
            }
        }
    }

    @Test
    fun `reports a migration that does not exist`() {
        val broken = MigrationRunner(dataSource, BrokenDialect, clock)

        val thrown = runCatching { broken.migrate() }.exceptionOrNull()

        // A jar missing its own migrations is a broken build, and the server owner needs to be told
        // that rather than watch the plugin fail later on a missing table.
        assertTrue(thrown is MigrationFailure, "expected a MigrationFailure, got $thrown")
    }

    private fun runner() = MigrationRunner(dataSource, SqliteDialect, clock)

    private fun tableExists(table: String): Boolean = dataSource.connection.use { connection ->
        connection.metaData.getTables(null, null, table, arrayOf("TABLE")).use(ResultSet::next)
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
        pool = PoolConfig(maximumPoolSize = 1, minimumIdle = 1, connectionTimeoutMs = 10_000),
    )

    /** A dialect pointing at migrations that were never shipped. */
    private object BrokenDialect : SqlDialect by SqliteDialect {
        override val migrationDirectory: String = "nonexistent"
    }

    private companion object {
        val TABLES = listOf("gk_account", "gk_balance", "gk_transaction", "gk_account_member", "gk_schema_version")
    }
}
