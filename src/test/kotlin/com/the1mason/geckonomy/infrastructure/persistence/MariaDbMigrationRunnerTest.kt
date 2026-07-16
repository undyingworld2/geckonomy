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
 * [MigrationRunnerContract] on MariaDB — the dialect whose migration path the contract's comments are
 * mostly about, and which shipped unexecuted until Docker arrived.
 *
 * Its own container rather than [MariaDbRepositoryTest]'s: these tests need a database with *no*
 * schema, and sharing one would mean each suite dropping tables the other had just built.
 */
@Testcontainers
class MariaDbMigrationRunnerTest : MigrationRunnerContract() {

    override val dialect: SqlDialect = MariaDbDialect

    override fun storage() = StorageConfig(
        type = StorageType.MARIADB,
        file = null,
        host = container.host,
        port = container.firstMappedPort,
        database = container.databaseName,
        username = container.username,
        password = container.password,
        properties = emptyMap(),
        pool = PoolConfig(maximumPoolSize = 2, minimumIdle = 1, connectionTimeoutMs = 10_000),
    )

    /**
     * Drops the schema, so every test starts from the empty database the contract assumes.
     *
     * `DELETE` would not do: these tests are about creating tables, not about rows. Runs after the
     * contract's `@BeforeEach` (JUnit orders the superclass first), which only opens a pool — dropping
     * tables underneath it is fine, since the pool is what the test then migrates *through*.
     */
    @BeforeEach
    fun dropSchema() {
        DriverManager.getConnection(jdbcUrl(), container.username, container.password).use { connection ->
            connection.createStatement().use { statement ->
                // Children first: gk_balance's FK to gk_account is enforced here, and InnoDB will not
                // let the parent go while it stands.
                statement.executeUpdate("DROP TABLE IF EXISTS gk_transaction, gk_balance, gk_account_member, gk_account, gk_schema_version")
            }
        }
    }

    private fun jdbcUrl(): String = "jdbc:mariadb://${container.host}:${container.firstMappedPort}/${container.databaseName}"

    companion object {

        /** `@JvmStatic` keeps this to one container per class rather than one per test method. */
        @Container
        @JvmStatic
        val container: MariaDBContainer<*> = MariaDBContainer("mariadb:11.4")
    }
}
