package com.the1mason.geckonomy.infrastructure.config

import java.nio.file.Path

/** Which storage backend holds the economy (CONFIGURATION.md §2). */
enum class StorageType {
    /** A local file; the default, and all a single server needs. */
    SQLITE,

    /** A remote server, so several Minecraft servers can share one economy. */
    MARIADB,
}

/**
 * Where and how balances are stored — the `storage` section of `config.yml`.
 *
 * Flat rather than one class per backend: [file] belongs to SQLite and [host]…[password] to MariaDB,
 * so exactly one group is meaningful at a time and the other is null. Config validation guarantees
 * that whichever group [type] names is present and non-blank before this object is handed out, so
 * M3's `DataSourceFactory` reads the fields for its own [type] as a formality rather than a real
 * possibility of absence.
 *
 * @property type the backend the other fields are read against.
 * @property file SQLite database file, relative to the server directory. Null unless [type] is
 *   [StorageType.SQLITE] and no path was configured.
 * @property host MariaDB host. Null unless [type] is [StorageType.MARIADB].
 * @property port MariaDB port; defaults to 3306.
 * @property database MariaDB schema name.
 * @property username MariaDB user.
 * @property password MariaDB password; may be empty, which is a legitimate local setup.
 * @property properties extra JDBC connection properties, passed through untouched.
 * @property pool HikariCP sizing.
 */
data class StorageConfig(
    val type: StorageType,
    val file: Path?,
    val host: String?,
    val port: Int?,
    val database: String?,
    val username: String?,
    val password: String?,
    val properties: Map<String, String>,
    val pool: PoolConfig,
)

/**
 * HikariCP pool sizing (`storage.pool`).
 *
 * @property maximumPoolSize connections the pool may open. SQLite is effectively single-writer, so a
 *   large value buys nothing there.
 * @property minimumIdle connections kept ready; never more than [maximumPoolSize] (validated).
 * @property connectionTimeoutMs how long a caller waits for a connection before failing. Hikari
 *   refuses anything under 250 ms, so validation does too.
 */
data class PoolConfig(
    val maximumPoolSize: Int,
    val minimumIdle: Int,
    val connectionTimeoutMs: Int,
)
