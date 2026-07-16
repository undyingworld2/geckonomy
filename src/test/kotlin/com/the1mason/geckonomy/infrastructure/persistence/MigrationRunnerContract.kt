package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.infrastructure.config.StorageConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The [MigrationRunner] suite, written once and run against every dialect.
 *
 * Split out of what used to be a SQLite-only test once Docker arrived. The differences it exists to
 * cover are real: SQLite makes DDL transactional, MariaDB implicitly commits it, and the
 * `IF NOT EXISTS` on every statement is what keeps the *same* recovery story true on both
 * (DATA_MODEL.md §2). Only a live MariaDB can prove that half of it.
 */
abstract class MigrationRunnerContract {

    /** A database with no schema at all — a server starting for the first time. */
    protected abstract fun storage(): StorageConfig

    protected abstract val dialect: SqlDialect

    protected lateinit var dataSource: HikariDataSource

    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun openPool() {
        dataSource = DataSourceFactory().create(storage())
    }

    @AfterEach
    fun closePool() = dataSource.close()

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
    fun `re-applies a migration whose version row was never recorded`() {
        runner().migrate()
        // Exactly the state MariaDB is left in when V001 fails partway: its DDL implicitly committed,
        // so the tables are there, but the version row is only written on success and never was.
        // Deleting the row reproduces that without having to break a real migration.
        execute("DELETE FROM gk_schema_version")

        val applied = runner().migrate()

        // Re-running over its own tables must succeed — that is what every statement being
        // IF NOT EXISTS buys, and it is the only recovery path MariaDB has.
        assertEquals(listOf(1), applied, "an unrecorded migration must re-run")
        TABLES.forEach { table -> assertTrue(tableExists(table), "$table should still exist") }
    }

    @Test
    fun `reports a migration that does not exist`() {
        val broken = MigrationRunner(dataSource, BrokenDialect(dialect), clock)

        val thrown = runCatching { broken.migrate() }.exceptionOrNull()

        // A jar missing its own migrations is a broken build, and the server owner needs to be told
        // that rather than watch the plugin fail later on a missing table.
        assertTrue(thrown is MigrationFailure, "expected a MigrationFailure, got $thrown")
    }

    private fun runner() = MigrationRunner(dataSource, dialect, clock)

    private fun execute(sql: String) = dataSource.connection.use { connection ->
        connection.createStatement().use { it.executeUpdate(sql) }
    }

    private fun tableExists(table: String): Boolean = dataSource.connection.use { connection ->
        connection.metaData.getTables(connection.catalog, null, table, arrayOf("TABLE")).use(ResultSet::next)
    }

    /** A dialect pointing at migrations that were never shipped. */
    private class BrokenDialect(dialect: SqlDialect) : SqlDialect by dialect {
        override val migrationDirectory: String = "nonexistent"
    }

    protected companion object {
        val TABLES = listOf("gk_account", "gk_balance", "gk_transaction", "gk_account_member", "gk_schema_version")
    }
}
