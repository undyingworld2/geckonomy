package com.the1mason.geckonomy.infrastructure.persistence

import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

/**
 * SQLite: account ids as canonical text, money as an INTEGER count of minor units
 * (DATA_MODEL.md §3).
 *
 * Text ids because SQLite has no binary literal a human can read in a `sqlite3` session, and the
 * local single-server database is exactly where someone pokes around by hand. The 20 bytes per row
 * that `BINARY(16)` would save do not matter at this scale; being able to paste a UUID from a log
 * into a `WHERE` clause does.
 */
object SqliteDialect : SqlDialect {

    override val migrationDirectory: String = "sqlite"

    override fun insertOrIgnore(table: String, columns: List<String>): String =
        "INSERT OR IGNORE INTO ${quote(table)} (${columns.joinToString(", ", transform = ::quote)}) " +
            "VALUES (${columns.joinToString(", ") { "?" }})"

    override fun upsert(
        table: String,
        columns: List<String>,
        keyColumns: List<String>,
        updateColumns: List<String>,
    ): String =
        "INSERT INTO ${quote(table)} (${columns.joinToString(", ", transform = ::quote)}) " +
            "VALUES (${columns.joinToString(", ") { "?" }}) " +
            "ON CONFLICT (${keyColumns.joinToString(", ", transform = ::quote)}) DO UPDATE SET " +
            updateColumns.joinToString(", ") { "${quote(it)} = excluded.${quote(it)}" }

    override fun bindUuid(statement: PreparedStatement, index: Int, uuid: UUID) =
        statement.setString(index, uuid.toString())

    override fun readUuid(row: ResultSet, column: String): UUID = UUID.fromString(row.getString(column))

    override fun bindNullableUuid(statement: PreparedStatement, index: Int, uuid: UUID?) =
        statement.setString(index, uuid?.toString())

    override fun readNullableUuid(row: ResultSet, column: String): UUID? =
        row.getString(column)?.let(UUID::fromString)

    override fun bindMoney(statement: PreparedStatement, index: Int, amount: BigDecimal) =
        statement.setLong(index, SqlDialect.toMinorUnits(amount))

    override fun readMoney(row: ResultSet, column: String): BigDecimal =
        SqlDialect.fromMinorUnits(row.getLong(column))

    /** SQLite quotes identifiers with double quotes; a literal `"` inside one is doubled. */
    private fun quote(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""
}
