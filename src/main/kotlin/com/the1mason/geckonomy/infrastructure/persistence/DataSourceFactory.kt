package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.infrastructure.config.StorageConfig
import com.the1mason.geckonomy.infrastructure.config.StorageType
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlin.io.path.absolutePathString
import kotlin.io.path.createParentDirectories

/**
 * Builds the connection pool the whole plugin talks to (DATA_MODEL.md §5).
 *
 * The one place that knows how a [StorageConfig] becomes a JDBC connection, so every difference
 * between the two backends — URL shape, pragmas, sane pool sizing — is visible side by side here
 * rather than scattered across the repositories.
 *
 * The returned pool is the caller's to close (`onDisable` does it).
 */
class DataSourceFactory {

    /**
     * Opens a pool for [config].
     *
     * Does not connect eagerly beyond Hikari's own startup probe: a database that is down should
     * fail at enable with a clear message, which is exactly what Hikari's probe gives us.
     *
     * @throws com.zaxxer.hikari.pool.HikariPool.PoolInitializationException if the database cannot be
     *   reached. The composition root catches it and disables the plugin rather than run without
     *   storage.
     */
    fun create(config: StorageConfig): HikariDataSource = HikariDataSource(
        when (config.type) {
            StorageType.SQLITE -> sqlite(config)
            StorageType.MARIADB -> mariadb(config)
        },
    )

    /**
     * SQLite: a local file, one writer.
     *
     * The pool is pinned to a single connection no matter what `pool.maximum-pool-size` says. SQLite
     * serializes writers anyway, so extra connections buy no throughput and cost correctness: with
     * one connection, a transaction cannot deadlock against another connection of our own pool, and
     * `SQLITE_BUSY` stops being reachable from inside this process. The configured size is not an
     * error — it is simply meaningless here — so this overrides it silently rather than rejecting a
     * config that is fine for MariaDB.
     */
    private fun sqlite(config: StorageConfig): HikariConfig {
        val file = requireNotNull(config.file) { "storage.file is required for sqlite; config validation should have caught this" }
        // The server owner names a path; the directory under it may not exist on first start.
        file.toAbsolutePath().createParentDirectories()
        return base(config).apply {
            driverClassName = SQLITE_DRIVER
            jdbcUrl = "jdbc:sqlite:${file.absolutePathString()}"
            maximumPoolSize = 1
            minimumIdle = 1
            // Pragmas belong on the connection, not in a migration: they are per-connection settings
            // that a fresh connection would otherwise silently drop back to the defaults.
            //  - foreign_keys: OFF by default in SQLite, so gk_balance's FK to gk_account would be
            //    decoration rather than a constraint.
            //  - journal_mode=WAL: readers stop blocking the writer, which matters because /baltop
            //    scans while transfers write. Persists in the database file, but set per connection
            //    so a file created elsewhere still gets it.
            //  - busy_timeout: a safety net for another *process* on the same file (a second server,
            //    a DB browser). Our own single connection cannot contend with itself.
            addDataSourceProperty("foreign_keys", "true")
            addDataSourceProperty("journal_mode", "WAL")
            addDataSourceProperty("busy_timeout", BUSY_TIMEOUT_MS.toString())
        }
    }

    /** MariaDB: a remote server, pooled as configured. */
    private fun mariadb(config: StorageConfig): HikariConfig = base(config).apply {
        driverClassName = MARIADB_DRIVER
        jdbcUrl = "jdbc:mariadb://${config.host}:${config.port}/${config.database}"
        username = config.username
        password = config.password
        maximumPoolSize = config.pool.maximumPoolSize
        minimumIdle = config.pool.minimumIdle
    }

    /**
     * The settings both backends share.
     *
     * **`driverClassName` is not optional, and this is the trap.** Left unset, Hikari resolves the
     * driver through `DriverManager`, whose registry is populated by a `ServiceLoader` scan of the
     * *system* classloader — and a Paper plugin's libraries are not there. `GeckonomyLoader` puts them
     * on our own isolated classloader, which that scan never sees, so `DriverManager` answers "no
     * suitable driver" and Hikari reports `Failed to get driver instance`. Naming the class makes
     * Hikari instantiate it from the classloader that actually holds it.
     *
     * MariaDB was the backend that exposed this, because nothing else on the server registers that
     * driver. **SQLite only ever appeared to work by accident**: Paper ships `sqlite-jdbc` for its own
     * use, so `DriverManager` had a SQLite driver registered — Paper's, at Paper's version, not the one
     * `geckonomy-libraries.txt` pins. Both backends now load the driver we ship.
     *
     * Not reachable from a test: Testcontainers and the SQLite suites put the driver on the *system*
     * classpath, where `DriverManager` finds it and this line changes nothing. Only a real server
     * separates the classloaders.
     */
    private fun base(config: StorageConfig): HikariConfig = HikariConfig().apply {
        poolName = POOL_NAME
        connectionTimeout = config.pool.connectionTimeoutMs.toLong()
        // Owner-supplied JDBC properties are applied first so the settings above win a conflict:
        // a config that turns off foreign keys or renames our pool is a footgun, not a feature.
        config.properties.forEach(::addDataSourceProperty)
    }

    private companion object {

        /** Identifies our threads and connections in logs and in a `SHOW PROCESSLIST`. */
        const val POOL_NAME = "geckonomy-pool"

        /** How long SQLite waits on a lock held by another process before giving up. */
        const val BUSY_TIMEOUT_MS = 5_000

        // Named, not referenced by class literal: these are the coordinates in
        // geckonomy-libraries.txt, and a literal would tie compilation to the driver being present.
        const val SQLITE_DRIVER = "org.sqlite.JDBC"
        const val MARIADB_DRIVER = "org.mariadb.jdbc.Driver"
    }
}
