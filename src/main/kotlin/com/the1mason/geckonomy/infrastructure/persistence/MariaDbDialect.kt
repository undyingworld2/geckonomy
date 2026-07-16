package com.the1mason.geckonomy.infrastructure.persistence

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

/**
 * MariaDB: account ids as `BINARY(16)`, money as native `DECIMAL(38,4)` (DATA_MODEL.md §3).
 *
 * Binary ids because a shared database is the one that gets large — every index on `account_id` pays
 * for the id's width, and 16 bytes beats 36 characters. The readability that SQLite's text ids buy is
 * worth less here, where nobody debugs by hand against the production economy.
 *
 * Money as `DECIMAL` rather than SQLite's minor-unit integer because MariaDB has an exact decimal
 * type and `SUM`/`ORDER BY` over it read naturally to anyone querying the database directly. The
 * *scale* is still [SqlDialect.MONEY_SCALE] and the range is still checked against SQLite's 64-bit
 * ceiling, so a balance either store accepts is a balance the other accepts too — the two backends
 * hold exactly the same set of values, which is what makes migrating between them safe.
 */
object MariaDbDialect : SqlDialect {

    override val migrationDirectory: String = "mariadb"

    override fun insertOrIgnore(table: String, columns: List<String>): String =
        "INSERT IGNORE INTO ${quote(table)} (${columns.joinToString(", ", transform = ::quote)}) " +
            "VALUES (${columns.joinToString(", ") { "?" }})"

    override fun upsert(
        table: String,
        columns: List<String>,
        keyColumns: List<String>,
        updateColumns: List<String>,
    ): String =
        // keyColumns is unused: MariaDB infers the conflicting key from whichever unique index the
        // row violates. It stays in the signature because SQLite must name it, and a dialect
        // interface shaped around only one backend's needs is how the abstraction leaks.
        "INSERT INTO ${quote(table)} (${columns.joinToString(", ", transform = ::quote)}) " +
            "VALUES (${columns.joinToString(", ") { "?" }}) " +
            "ON DUPLICATE KEY UPDATE " +
            updateColumns.joinToString(", ") { "${quote(it)} = VALUES(${quote(it)})" }

    override fun bindUuid(statement: PreparedStatement, index: Int, uuid: UUID) =
        statement.setBytes(index, toBytes(uuid))

    override fun readUuid(row: ResultSet, column: String): UUID = toUuid(row.getBytes(column))

    override fun bindNullableUuid(statement: PreparedStatement, index: Int, uuid: UUID?) =
        statement.setBytes(index, uuid?.let(::toBytes))

    override fun readNullableUuid(row: ResultSet, column: String): UUID? = row.getBytes(column)?.let(::toUuid)

    override fun bindMoney(statement: PreparedStatement, index: Int, amount: BigDecimal) {
        // Called for the range check, which the DECIMAL column would not enforce: DECIMAL(38,4)
        // happily stores a number SQLite could never hold, and the day someone migrates backends is
        // the day that becomes their problem. Its return value is deliberately unused.
        SqlDialect.toMinorUnits(amount)
        statement.setBigDecimal(index, amount.setScale(SqlDialect.MONEY_SCALE))
    }

    override fun readMoney(row: ResultSet, column: String): BigDecimal =
        // The column's scale is already MONEY_SCALE, so this only normalizes what the driver hands
        // back; it never discards a digit the schema could hold.
        row.getBigDecimal(column).setScale(SqlDialect.MONEY_SCALE)

    /** Big-endian, so a `BINARY(16)` sorts and groups the same way the UUID's text form does. */
    private fun toBytes(uuid: UUID): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES * 2)
        .putLong(uuid.mostSignificantBits)
        .putLong(uuid.leastSignificantBits)
        .array()

    private fun toUuid(bytes: ByteArray): UUID = ByteBuffer.wrap(bytes).run { UUID(long, long) }

    /** MariaDB quotes identifiers with backticks; a literal backtick inside one is doubled. */
    private fun quote(identifier: String): String = "`${identifier.replace("`", "``")}`"
}
