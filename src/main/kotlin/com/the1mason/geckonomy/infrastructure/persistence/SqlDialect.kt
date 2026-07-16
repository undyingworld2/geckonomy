package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.domain.model.AccountId
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

/**
 * Everything SQLite and MariaDB disagree about (DATA_MODEL.md §3, ARCHITECTURE.md §3).
 *
 * The repositories are written once against this interface, so a dialect difference is a bug in one
 * small implementation rather than a branch buried in query code. Anything the two backends spell the
 * same way is not here — this is the divergence, not a general SQL abstraction.
 *
 * Implementations are stateless and safe to share.
 */
interface SqlDialect {

    /** Directory under `db/migration/` holding this dialect's SQL (DATA_MODEL.md §2). */
    val migrationDirectory: String

    /**
     * `INSERT` that does nothing when the row already exists.
     *
     * Spelled `INSERT OR IGNORE` by SQLite and `INSERT IGNORE` by MariaDB. Used to seed a row before
     * adjusting it, and to make account creation idempotent.
     *
     * @param table unquoted table name.
     * @param columns unquoted column names, in the order the placeholders bind.
     */
    fun insertOrIgnore(table: String, columns: List<String>): String

    /**
     * `INSERT` that overwrites [updateColumns] when the row already exists — the upsert of
     * DATA_MODEL.md §4.
     *
     * @param table unquoted table name.
     * @param columns every column being inserted, in bind order.
     * @param keyColumns the conflicting key; SQLite names it explicitly, MariaDB infers it.
     * @param updateColumns what to overwrite on conflict.
     */
    fun upsert(table: String, columns: List<String>, keyColumns: List<String>, updateColumns: List<String>): String

    /**
     * Binds [uuid] at [index] in the form this dialect stores UUIDs.
     *
     * The primitive the id functions below are built from. Account ids and transaction ids are both
     * UUIDs in the same column shape, so a dialect that only knew about *account* ids would leave the
     * ledger's own id to be encoded by hand — which is exactly the kind of thing that works on SQLite
     * and corrupts on MariaDB.
     */
    fun bindUuid(statement: PreparedStatement, index: Int, uuid: UUID)

    /** Reads the UUID in [column]. */
    fun readUuid(row: ResultSet, column: String): UUID

    /** Binds a UUID that may be absent — a transfer counterparty on a one-sided operation. */
    fun bindNullableUuid(statement: PreparedStatement, index: Int, uuid: UUID?)

    /** Reads a UUID that may be absent; `null` when the column is SQL `NULL`. */
    fun readNullableUuid(row: ResultSet, column: String): UUID?

    /** Binds [id] at [index]. */
    fun bindAccountId(statement: PreparedStatement, index: Int, id: AccountId) =
        bindUuid(statement, index, id.value)

    /** Reads the account id in [column]. */
    fun readAccountId(row: ResultSet, column: String): AccountId = AccountId(readUuid(row, column))

    /** Binds a nullable account id. */
    fun bindNullableAccountId(statement: PreparedStatement, index: Int, id: AccountId?) =
        bindNullableUuid(statement, index, id?.value)

    /** Reads a nullable account id; `null` when the column is SQL `NULL`. */
    fun readNullableAccountId(row: ResultSet, column: String): AccountId? =
        readNullableUuid(row, column)?.let(::AccountId)

    /**
     * Binds [amount] at [index] in the form this dialect stores money.
     *
     * @throws MoneyOutOfRange if [amount] cannot be stored (see [MONEY_SCALE]).
     */
    fun bindMoney(statement: PreparedStatement, index: Int, amount: BigDecimal)

    /** Reads the money in [column] back to an exact [BigDecimal] at [MONEY_SCALE]. */
    fun readMoney(row: ResultSet, column: String): BigDecimal

    companion object {

        /**
         * Fractional digits every stored amount is held at, on both backends.
         *
         * **Why a fixed scale, and why 4** (the decision DATA_MODEL.md §3 deferred to M3):
         *
         * SQLite has no decimal type, so money is stored as an INTEGER count of minor units — exact,
         * natively sortable for `/baltop`, and addable in a single guarded `UPDATE`, which is what
         * lets [BalanceRepository.adjust][com.the1mason.geckonomy.domain.port.BalanceRepository.adjust]
         * be atomic without a read-modify-write. The cost is a ceiling: a 64-bit integer at scale *n*
         * caps a balance at `2^63-1 / 10^n`. At scale 4 that is ~922 trillion, which no economy
         * reaches; at scale 10 it would be ~922 million, which one plausibly does.
         *
         * Fixed rather than per-currency because the scale is how a stored integer is *interpreted*.
         * Were it read from a currency's `fractional-digits`, editing that value in `config.yml`
         * would silently multiply or divide every existing balance by a power of ten. Fixed, config
         * decides only rounding, and `fractional-digits` is capped at this value by `ConfigLoader`
         * so a currency can never ask for precision the store would truncate.
         */
        const val MONEY_SCALE = 4

        /** Largest storable amount; see [MONEY_SCALE]. */
        val MAX_MONEY: BigDecimal = BigDecimal.valueOf(Long.MAX_VALUE, MONEY_SCALE)

        /** Smallest storable amount — negative balances exist wherever overdraft is on. */
        val MIN_MONEY: BigDecimal = BigDecimal.valueOf(Long.MIN_VALUE, MONEY_SCALE)

        /**
         * [amount] as the integer count of minor units both dialects agree on.
         *
         * Shared rather than per-dialect because the scale is a property of the *schema*, not of a
         * backend: MariaDB's `DECIMAL(38,4)` could hold far more than SQLite's 64-bit ceiling, but a
         * balance it accepted and SQLite could not would make the two stores non-interchangeable, and
         * migrating between them is a thing owners do. So MariaDB is held to SQLite's range too.
         *
         * @throws MoneyOutOfRange if [amount] is finer than [MONEY_SCALE] (a caller that skipped
         *   `RoundingPolicy` — a bug) or too large to store (a plausible `/eco give` typo).
         */
        fun toMinorUnits(amount: BigDecimal): Long {
            val scaled = try {
                amount.setScale(MONEY_SCALE)
            } catch (e: ArithmeticException) {
                throw MoneyOutOfRange(
                    "$amount has more than $MONEY_SCALE fractional digits; round it with RoundingPolicy before storing",
                    e,
                )
            }
            return try {
                scaled.unscaledValue().longValueExact()
            } catch (e: ArithmeticException) {
                throw MoneyOutOfRange("$amount is outside the storable range $MIN_MONEY..$MAX_MONEY", e)
            }
        }

        /** The inverse of [toMinorUnits]. */
        fun fromMinorUnits(minorUnits: Long): BigDecimal = BigDecimal.valueOf(minorUnits, MONEY_SCALE)
    }
}

/**
 * An amount cannot be stored: finer than the fixed scale, or beyond the 64-bit ceiling
 * ([SqlDialect.MONEY_SCALE]).
 *
 * An exception rather than a typed result, unlike insufficient funds, because both causes are faults
 * rather than outcomes: rounding is the caller's job, and no legitimate balance approaches 922
 * trillion. It is caught at the application boundary and reported as a storage failure, never thrown
 * at a Bukkit caller (CODING_STANDARDS.md §4).
 */
class MoneyOutOfRange(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
