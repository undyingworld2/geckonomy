package com.the1mason.geckonomy.infrastructure.persistence

import java.sql.SQLException

/**
 * A transaction lost a race for a row it needed, and was (or must be) rolled back whole.
 *
 * Raised by [SqlBalanceRepository] when creating a missing balance row hits a constraint — another
 * transaction created it first, or the account was deleted underneath. Either way the next attempt sees
 * the committed truth: the row exists and is locked normally, or `exists` reports the account gone.
 *
 * An [SQLException] on purpose, carrying the original's state and code: if the retries run out it
 * reaches `StorageGuard` as the same storage failure the bare exception always was.
 */
internal class WriteConflict(cause: SQLException) : SQLException(cause.message, cause.sqlState, cause.errorCode, cause)

/**
 * Whether [e] means "rolled back by a conflict, safe to run again from the start" — and nothing else.
 *
 * - [WriteConflict]: ours, above.
 * - SQLState `40001`: serialization failure. InnoDB's deadlock victim (1213) is reported this way and
 *   is rolled back in full by the server before we hear of it.
 * - MariaDB 1205, lock wait timeout. InnoDB rolls back only the *statement*, so the caller must roll
 *   back the rest before retrying, which [SqlUnitOfWork] always does.
 *
 * Deliberately not "any SQLException": a broken connection mid-commit leaves the outcome unknown, and
 * running the block again could apply it twice. [SqlUnitOfWork] resolves that case separately.
 */
internal fun isWriteConflict(e: Throwable): Boolean =
    generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).any { cause ->
        cause is WriteConflict ||
            (cause is SQLException && (cause.sqlState == SERIALIZATION_FAILURE || cause.errorCode == LOCK_WAIT_TIMEOUT))
    }

/** How many times a conflicted transaction is attempted in all, before its failure is reported. */
internal const val MAX_WRITE_ATTEMPTS = 3

private const val SERIALIZATION_FAILURE = "40001"
private const val LOCK_WAIT_TIMEOUT = 1205

/** Bounds the cause walk: a cyclic cause chain is legal, if perverse. */
private const val MAX_CAUSE_DEPTH = 8
