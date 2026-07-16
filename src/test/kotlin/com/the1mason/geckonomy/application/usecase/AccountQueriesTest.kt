package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.TestCurrencies
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * The three read-only account queries and the currency listing. One file: each is a one-liner over a
 * port, and a file apiece would be four imports and a fixture to say the same thing.
 */
class AccountQueriesTest {

    private val fixture = EconomyFixture()

    private val exists = AccountExists(fixture.accounts, fixture.guard)
    private val findName = FindAccountName(fixture.accounts, fixture.guard)
    private val listNames = ListAccountNames(fixture.accounts, fixture.guard)
    private val listCurrencies = ListCurrencies(fixture.currencies)

    @Test
    fun `finds an existing account`() = runBlocking {
        fixture.givenAccount(ALICE, "Alice")

        assertEquals(Outcome.Success(true), exists(ALICE))
    }

    @Test
    fun `a missing account is an answer, not a failure`() = runBlocking {
        // Success(false), not Failure(AccountNotFound): "no" is what the question asked for.
        assertEquals(Outcome.Success(false), exists(BOB))
    }

    @Test
    fun `reads an account's name`() = runBlocking {
        fixture.givenAccount(ALICE, "Alice")

        assertEquals(Outcome.Success("Alice"), findName(ALICE))
    }

    @Test
    fun `answers null for a missing account's name`() = runBlocking {
        assertEquals(Outcome.Success(null), findName(BOB))
    }

    @Test
    fun `maps every account's name`() = runBlocking {
        fixture.givenAccount(ALICE, "Alice")
        fixture.givenAccount(BOB, "Bob")

        assertEquals(Outcome.Success(mapOf(ALICE to "Alice", BOB to "Bob")), listNames())
    }

    @Test
    fun `maps nothing when there are no accounts`() = runBlocking {
        assertEquals(Outcome.Success(emptyMap<Nothing, Nothing>()), listNames())
    }

    /**
     * Explicit `: Unit`, here and wherever a test ends in `assertInstanceOf` — it *returns* the value
     * it matched, Kotlin infers that as the method's return type, and JUnit silently skips a `@Test`
     * that returns something. Same trap `RepositoryContract` documents.
     */
    @Test
    fun `maps a storage failure on every query`(): Unit = runBlocking {
        fixture.accounts.failWith = SQLException("connection reset")
        assertInstanceOf(EconomyError.StorageFailure::class.java, (exists(ALICE) as Outcome.Failure).error)

        fixture.accounts.failWith = SQLException("connection reset")
        assertInstanceOf(EconomyError.StorageFailure::class.java, (findName(ALICE) as Outcome.Failure).error)

        fixture.accounts.failWith = SQLException("connection reset")
        assertInstanceOf(EconomyError.StorageFailure::class.java, (listNames() as Outcome.Failure).error)
    }

    @Test
    fun `lists every currency, default first`() {
        assertEquals(listOf(TestCurrencies.COINS, TestCurrencies.GEMS), listCurrencies())
    }
}
