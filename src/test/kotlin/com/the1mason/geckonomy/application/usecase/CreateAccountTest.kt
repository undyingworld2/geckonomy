package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.AccountType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.SQLException

class CreateAccountTest {

    private val fixture = EconomyFixture()
    private val createAccount = CreateAccount(
        fixture.unitOfWork,
        fixture.currencies,
        fixture.rounding,
        fixture.clock,
        fixture.guard,
    )

    @Test
    fun `creates an account`() = runBlocking {
        val result = createAccount(ALICE, "Alice")

        assertEquals(Outcome.Success(true), result)
        assertTrue(fixture.accounts.exists(ALICE))
        assertEquals("Alice", fixture.accounts.findName(ALICE))
    }

    @Test
    fun `stamps the account with the injected clock`() = runBlocking {
        createAccount(ALICE, "Alice")

        assertEquals(EconomyFixture.NOW, fixture.accounts.find(ALICE)?.createdAt)
    }

    @Test
    fun `seeds every currency at its starting balance`() = runBlocking {
        createAccount(ALICE, "Alice")

        // COINS starts at 100.00, GEMS at 0 — and GEMS gets a row anyway, so a player who has earned
        // nothing still appears in /baltop.
        assertEquals(0, BigDecimal("100.00").compareTo(fixture.balances.get(ALICE, TestCurrencies.COINS)))
        assertEquals(0, BigDecimal("0").compareTo(fixture.balances.get(ALICE, TestCurrencies.GEMS)))
    }

    @Test
    fun `writes no ledger rows for the opening balance`() = runBlocking {
        createAccount(ALICE, "Alice")

        // A starting balance is initial state, not a transaction. A DEPOSIT row from nobody would
        // misreport the economy's inflow.
        assertTrue(fixture.log.entries.isEmpty())
    }

    @Test
    fun `is idempotent and does not reseed`() = runBlocking {
        createAccount(ALICE, "Alice")
        fixture.balances.set(ALICE, TestCurrencies.COINS, BigDecimal("5.00"))

        val second = createAccount(ALICE, "Alice")

        // The race this exists for: a join listener and a Vault plugin both reaching for the same
        // player. The loser must not reset a spent balance back to starting-balance.
        assertEquals(Outcome.Success(false), second)
        assertEquals(0, BigDecimal("5.00").compareTo(fixture.balances.get(ALICE, TestCurrencies.COINS)))
    }

    @Test
    fun `does not rename an existing account`() = runBlocking {
        createAccount(ALICE, "Alice")

        createAccount(ALICE, "AliceRenamed")

        // create is INSERT OR IGNORE; refreshing a changed name is RenameAccount's job, and M7's
        // join listener uses the `false` above as its cue.
        assertEquals("Alice", fixture.accounts.findName(ALICE))
    }

    @Test
    fun `creates a player account by default`() = runBlocking {
        createAccount(ALICE, "Alice")

        assertEquals(AccountType.PLAYER, fixture.accounts.find(ALICE)?.type)
    }

    /** See `AccountQueriesTest.maps a storage failure on every query` for the explicit `: Unit`. */
    @Test
    fun `maps a storage failure`(): Unit = runBlocking {
        fixture.accounts.failWith = SQLException("connection reset")

        val result = createAccount(ALICE, "Alice")

        assertInstanceOf(EconomyError.StorageFailure::class.java, (result as Outcome.Failure).error)
    }

    @Test
    fun `seeds nothing when the account write fails`() = runBlocking {
        // The reason create-and-seed share a transaction: a half-done create would leave an account
        // that can never be seeded, since every later create answers false and skips it.
        fixture.balances.failWith = SQLException("connection reset")

        createAccount(ALICE, "Alice")

        assertNull(fixture.balances.get(ALICE, TestCurrencies.COINS))
        assertTrue(!fixture.accounts.exists(ALICE), "the account must roll back with its seeds")
    }
}
