package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.infrastructure.config.PoolConfig
import com.the1mason.geckonomy.infrastructure.config.StorageConfig
import com.the1mason.geckonomy.infrastructure.config.StorageType
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * [MigrationRunnerContract] on SQLite.
 *
 * `@TempDir` is what makes each test's database empty: a fresh file per test, deleted after, so
 * nothing has to be torn down by hand.
 */
class SqliteMigrationRunnerTest : MigrationRunnerContract() {

    @TempDir
    lateinit var directory: Path

    override val dialect: SqlDialect = SqliteDialect

    override fun storage() = StorageConfig(
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
}
