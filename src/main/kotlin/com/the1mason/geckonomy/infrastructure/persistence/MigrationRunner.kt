package com.the1mason.geckonomy.infrastructure.persistence

import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import javax.sql.DataSource

/**
 * Brings the database schema up to date before anything reads it (DATA_MODEL.md §2).
 *
 * Runs on enable, and the plugin refuses to start if it fails: an economy talking to a
 * half-migrated schema is worse than one that did not come up.
 *
 * **Blocking**, and deliberately not `suspend` — the composition root calls it inside
 * `runBlocking(io.dispatcher)`, which keeps the "JDBC only on the IO threads" rule
 * (CODING_STANDARDS.md §3) while still finishing before the first repository exists. There is nothing
 * to be gained by letting enable proceed concurrently with the migration it depends on.
 *
 * @param dataSource the pool; a connection is borrowed per migration file.
 * @param dialect decides which directory the SQL comes from.
 * @param clock stamps `applied_at`; injected so tests stay deterministic (CODING_STANDARDS.md §6).
 */
class MigrationRunner(
    private val dataSource: DataSource,
    private val dialect: SqlDialect,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * Applies every migration the database has not seen, in order.
     *
     * Idempotent: a second run finds the recorded versions and applies nothing.
     *
     * @return the versions applied by *this* call — empty when already up to date.
     * @throws MigrationFailure if a migration is missing, malformed, or rejected by the database.
     */
    fun migrate(): List<Int> {
        val available = available()
        return dataSource.connection.use { connection ->
            val applied = appliedVersions(connection)
            available.filter { it.version !in applied }.onEach { apply(connection, it) }.map { it.version }
        }
    }

    /**
     * Runs one migration and records it, as one transaction.
     *
     * The record is written in the same transaction as the schema change, so on SQLite the pair is
     * atomic. **On MariaDB it is not**, and cannot be: DDL implicitly commits there, so a failure
     * partway leaves the statements that already ran. The recovery is that the version row is only
     * written on success, so the next start re-runs the whole file — which is safe only because every
     * statement in it is `IF NOT EXISTS`. That invariant is the migration author's to keep.
     */
    private fun apply(connection: Connection, migration: Migration) {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                // One at a time rather than a batch, and named in the error: a batch reports
                // "statement 3 failed" against SQL the reader cannot see, and a broken migration is
                // exactly when a server owner needs the failing statement in front of them.
                migration.statements.forEach { sql ->
                    try {
                        statement.execute(sql)
                    } catch (e: SQLException) {
                        throw MigrationFailure("Migration ${migration.name} failed on [$sql]: ${e.message}", e)
                    }
                }
            }
            record(connection, migration.version)
            connection.commit()
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw if (e is MigrationFailure) e else MigrationFailure("Migration ${migration.name} failed: ${e.message}", e)
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    private fun record(connection: Connection, version: Int) {
        connection.prepareStatement("INSERT INTO gk_schema_version (version, applied_at) VALUES (?, ?)")
            .use { statement ->
                statement.setInt(1, version)
                statement.setLong(2, clock.millis())
                statement.executeUpdate()
            }
    }

    /**
     * Versions already applied, or empty if the tracking table does not exist yet.
     *
     * The table is created by the first migration, so the very first run has nowhere to read from —
     * asking the metadata rather than catching a failed `SELECT` keeps "no schema yet" a fact we
     * looked up instead of an error we recovered from.
     */
    private fun appliedVersions(connection: Connection): Set<Int> {
        if (!tableExists(connection, VERSION_TABLE)) return emptySet()
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version FROM $VERSION_TABLE").use { rows ->
                buildSet { while (rows.next()) add(rows.getInt("version")) }
            }
        }
    }

    /** Whether [table] exists, asked of the driver so each dialect answers for itself. */
    private fun tableExists(connection: Connection, table: String): Boolean =
        connection.metaData.getTables(connection.catalog, null, table, arrayOf("TABLE")).use { it.next() }

    /** Every migration shipped for this dialect, in apply order. */
    private fun available(): List<Migration> {
        val directory = "$MIGRATION_ROOT/${dialect.migrationDirectory}"
        return readResource("$directory/$INDEX_FILE")
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { name -> Migration(name, versionOf(name), statementsOf(readResource("$directory/$name"))) }
            .toList()
    }

    /** The number in `V001__init.sql`. */
    private fun versionOf(name: String): Int =
        NAME_PATTERN.matchEntire(name)?.groupValues?.get(1)?.toInt()
            ?: throw MigrationFailure("Migration '$name' is not named V<number>__<description>.sql")

    /**
     * The file's statements.
     *
     * A deliberately naive split on a trailing semicolon rather than a SQL parser: the input is our
     * own migration files, not user SQL, so the cost of the restriction (documented at the top of
     * each file) is a rule for us to follow rather than a limitation anyone else meets. Line comments
     * are stripped first so a `;` inside one cannot split a statement.
     */
    private fun statementsOf(sql: String): List<String> = sql
        .lineSequence()
        .map { it.substringBefore("--").trim() }
        .filter(String::isNotEmpty)
        .joinToString(" ")
        .split(";")
        .map(String::trim)
        .filter(String::isNotEmpty)

    private fun readResource(path: String): String =
        javaClass.getResourceAsStream("/$path")?.bufferedReader()?.use { it.readText() }
            ?: throw MigrationFailure("$path is missing from the plugin jar; the build is broken.")

    /** One migration file, parsed. */
    private data class Migration(val name: String, val version: Int, val statements: List<String>)

    private companion object {
        const val MIGRATION_ROOT = "db/migration"
        const val INDEX_FILE = "migrations.txt"
        const val VERSION_TABLE = "gk_schema_version"
        val NAME_PATTERN = Regex("V(\\d+)__.*\\.sql")
    }
}

/**
 * The schema could not be brought up to date.
 *
 * Fatal by design: the composition root disables the plugin rather than let it run against a schema
 * it does not understand.
 */
class MigrationFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
