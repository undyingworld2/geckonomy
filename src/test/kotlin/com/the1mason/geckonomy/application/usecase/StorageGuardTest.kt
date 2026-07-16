package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.CurrencyMismatch
import com.the1mason.geckonomy.domain.model.CurrencyCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException

/**
 * The exception boundary (ARCHITECTURE.md §6). These tests are why every other use case may assume no
 * exception escapes it.
 */
class StorageGuardTest {

    private val logged = mutableListOf<LogRecord>()
    private val logger = Logger.getAnonymousLogger().apply {
        useParentHandlers = false
        addHandler(object : Handler() {
            override fun publish(record: LogRecord) { logged += record }
            override fun flush() = Unit
            override fun close() = Unit
        })
    }
    private val guard = StorageGuard(logger)

    @Test
    fun `passes a success through untouched`() = runBlocking {
        val result = guard.guarding({ "reading" }) { Outcome.Success(42) }

        assertEquals(Outcome.Success(42), result)
        assertTrue(logged.isEmpty(), "a success must not log")
    }

    @Test
    fun `passes a typed failure through untouched`() = runBlocking {
        // A use case's own refusal is not the guard's business; only thrown things are.
        val refusal = Outcome.Failure(EconomyError.UnknownCurrency(CurrencyCode("nope")))

        assertEquals(refusal, guard.guarding({ "reading" }) { refusal })
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `maps a SQLException to StorageFailure and logs it`() = runBlocking {
        val result = guard.guarding<Int>({ "depositing 5 to alice" }) { throw SQLException("connection reset") }

        assertEquals(
            Outcome.Failure(EconomyError.StorageFailure("depositing 5 to alice", "connection reset")),
            result,
        )
        assertEquals(1, logged.size)
        assertEquals(Level.WARNING, logged.single().level)
        assertTrue(logged.single().message.contains("depositing 5 to alice"))
    }

    @Test
    fun `maps any storage exception, not just SQLException`() = runBlocking {
        // MoneyOutOfRange and LedgerFailure are plain RuntimeExceptions whose KDoc promises they are
        // caught here. Nothing about them is special to the guard, and that is the point.
        val result = guard.guarding<Int>({ "logging" }) { throw IllegalStateException("ledger is unhappy") }

        assertInstanceOf(Outcome.Failure::class.java, result)
        assertEquals(
            EconomyError.StorageFailure("logging", "ledger is unhappy"),
            (result as Outcome.Failure).error,
        )
    }

    /**
     * The arm the whole class is arranged around. `java.util.concurrent.CancellationException` is an
     * `IllegalStateException`, so a guard that caught `Exception` first would swallow it — and
     * `SqlUnitOfWork` rolls back a half-done transfer *because* cancellation reaches it.
     */
    @Test
    fun `rethrows cancellation instead of mapping it`(): Unit = runBlocking {
        val thrown = runCatching {
            guard.guarding<Int>({ "transferring" }) { throw CancellationException("plugin disabling") }
        }

        assertInstanceOf(CancellationException::class.java, thrown.exceptionOrNull())
        assertTrue(logged.isEmpty(), "cancellation is not a failure and must not be logged as one")
    }

    @Test
    fun `reports a domain exception as our bug, at SEVERE`() = runBlocking {
        val result = guard.guarding<Int>({ "adding gems to coins" }) {
            throw CurrencyMismatch(CurrencyCode("coins"), CurrencyCode("gems"))
        }

        // Still a StorageFailure — EconomyError has no Internal variant, and a player would read
        // either the same way. The log level is what separates "the database is down" from "we have
        // a bug", and it is the only thing that needs to.
        assertEquals(
            Outcome.Failure(EconomyError.StorageFailure("adding gems to coins", "internal error")),
            result,
        )
        assertEquals(Level.SEVERE, logged.single().level)
    }

    @Test
    fun `builds the context message only when something fails`() = runBlocking {
        var built = 0

        guard.guarding({ built++; "reading" }) { Outcome.Success(1) }

        assertEquals(0, built, "the happy path must not pay for a message nobody reads")
    }
}
