package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.infrastructure.config.PoolConfig
import com.the1mason.geckonomy.infrastructure.config.StorageConfig
import com.the1mason.geckonomy.infrastructure.config.StorageType
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * [RepositoryContract] on SQLite.
 *
 * A real file in a temp directory rather than `:memory:`, which the M3 task suggested. An in-memory
 * database lives and dies with its connection, so the scope tests — which put two servers, and
 * therefore two pools, on one database — could not share it without the `cache=shared` URL hack. A
 * temp file is what production actually uses, and JUnit deletes it after each test.
 *
 * The MariaDB sibling is deliberately absent: it needs Docker for Testcontainers, which this machine
 * does not have. Adding it is a subclass of this shape and nothing else — every expectation already
 * lives in [RepositoryContract].
 */
class SqliteRepositoryTest : RepositoryContract() {

    @TempDir
    lateinit var directory: Path

    override val dialect: SqlDialect = SqliteDialect

    /**
     * The same file for every server id, so two harnesses in one test are two servers sharing one
     * database — the arrangement the scope tests are about.
     */
    override fun storageFor(serverId: String): StorageConfig = StorageConfig(
        type = StorageType.SQLITE,
        file = directory.resolve("economy.db"),
        host = null,
        port = null,
        database = null,
        username = null,
        password = null,
        properties = emptyMap(),
        pool = PoolConfig(maximumPoolSize = 10, minimumIdle = 1, connectionTimeoutMs = 10_000),
    )
}
