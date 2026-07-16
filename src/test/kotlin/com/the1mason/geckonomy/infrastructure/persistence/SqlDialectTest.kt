package com.the1mason.geckonomy.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

/**
 * Money encoding and generated SQL, tested without a database.
 *
 * These are the parts of the dialects that can be checked by pure computation, which matters more
 * than usual here: [MariaDbDialect] has no repository suite of its own until Docker is available, so
 * its upsert syntax and money handling would otherwise ship completely unexecuted.
 */
class SqlDialectTest {

    // ── Money encoding (DATA_MODEL.md §3) ───────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = ["0", "1", "100.00", "0.0001", "-0.0001", "-9.5", "1234.5678", "922337203685477.5807"])
    fun `round-trips an amount through minor units`(amount: String) {
        val encoded = SqlDialect.toMinorUnits(BigDecimal(amount))

        assertEquals(0, BigDecimal(amount).compareTo(SqlDialect.fromMinorUnits(encoded)))
    }

    @Test
    fun `encodes to a count of minor units at the fixed scale`() {
        // 100.00 coins is 1,000,000 ten-thousandths. The scale is fixed at 4 rather than read from
        // the currency precisely so that this number never changes meaning (SqlDialect.MONEY_SCALE).
        assertEquals(1_000_000L, SqlDialect.toMinorUnits(BigDecimal("100.00")))
    }

    @Test
    fun `ignores trailing zeros beyond the scale`() {
        // Scale 6, but the extra digits carry no information — padding is not precision loss.
        assertEquals(1_000_000L, SqlDialect.toMinorUnits(BigDecimal("100.000000")))
    }

    @Test
    fun `refuses an amount finer than the scale`() {
        // A caller that skipped RoundingPolicy. Truncating silently would make the stored balance
        // disagree with the number the player was told, which is how audits stop adding up.
        assertThrows<MoneyOutOfRange> { SqlDialect.toMinorUnits(BigDecimal("1.00001")) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["922337203685477.5808", "-922337203685477.5809", "1e20"])
    fun `refuses an amount beyond the storable range`(amount: String) {
        assertThrows<MoneyOutOfRange> { SqlDialect.toMinorUnits(BigDecimal(amount)) }
    }

    @Test
    fun `stores the documented ceiling exactly`() {
        // The number CONFIGURATION.md and DATA_MODEL.md promise: ~922 trillion. If this changes, the
        // scale changed, and every stored balance silently changed meaning with it.
        assertEquals(BigDecimal("922337203685477.5807"), SqlDialect.MAX_MONEY)
        assertEquals(BigDecimal("-922337203685477.5808"), SqlDialect.MIN_MONEY)
    }

    // ── Generated SQL ───────────────────────────────────────────────────

    @Test
    fun `sqlite names the conflict target in an upsert`() {
        val sql = SqliteDialect.upsert("gk_balance", listOf("account_id", "amount"), listOf("account_id"), listOf("amount"))

        assertEquals(
            """INSERT INTO "gk_balance" ("account_id", "amount") VALUES (?, ?) """ +
                """ON CONFLICT ("account_id") DO UPDATE SET "amount" = excluded."amount"""",
            sql,
        )
    }

    @Test
    fun `mariadb infers the conflict target in an upsert`() {
        val sql = MariaDbDialect.upsert("gk_balance", listOf("account_id", "amount"), listOf("account_id"), listOf("amount"))

        assertEquals(
            "INSERT INTO `gk_balance` (`account_id`, `amount`) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE `amount` = VALUES(`amount`)",
            sql,
        )
    }

    @Test
    fun `each dialect spells insert-or-ignore its own way`() {
        assertEquals(
            """INSERT OR IGNORE INTO "gk_account" ("id") VALUES (?)""",
            SqliteDialect.insertOrIgnore("gk_account", listOf("id")),
        )
        assertEquals(
            "INSERT IGNORE INTO `gk_account` (`id`) VALUES (?)",
            MariaDbDialect.insertOrIgnore("gk_account", listOf("id")),
        )
    }

    @Test
    fun `the dialects read their own migration directories`() {
        // A dialect pointed at the other's SQL would create the wrong column types and fail late.
        assertEquals("sqlite", SqliteDialect.migrationDirectory)
        assertEquals("mariadb", MariaDbDialect.migrationDirectory)
    }

    @Test
    fun `every shipped migration exists and parses`() {
        // Cheap insurance for the dialect with no repository suite: MariaDB's V001 is never executed
        // by any test, so at minimum its file must be present and readable through the same index the
        // runner uses.
        listOf(SqliteDialect, MariaDbDialect).forEach { dialect ->
            val index = javaClass.getResourceAsStream("/db/migration/${dialect.migrationDirectory}/migrations.txt")
            assertTrue(index != null, "${dialect.migrationDirectory} has no migrations.txt")
            index?.close()

            val migration = javaClass.getResourceAsStream("/db/migration/${dialect.migrationDirectory}/V001__init.sql")
            assertTrue(migration != null, "${dialect.migrationDirectory} has no V001__init.sql")
            migration?.close()
        }
    }
}
