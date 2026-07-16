package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.Attribution
import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.SQLException

class DeleteAccountTest {

    private val fixture = EconomyFixture()
    private var keepHistory = true
    private val deleteAccount = DeleteAccount(fixture.unitOfWork, { keepHistory }, fixture.guard)

    private suspend fun givenAliceWithHistory(): AccountId {
        fixture.givenAccount(ALICE, "Alice")
        fixture.log.append(
            fixture.transactions.entry(
                ALICE,
                TestCurrencies.COINS.code,
                BigDecimal("25.00"),
                BigDecimal("75.00"),
                TransactionType.DEPOSIT,
                Attribution.GECKONOMY,
            ),
        )
        return ALICE
    }

    @Test
    fun `deletes an account`() = runBlocking {
        fixture.givenAccount(ALICE, "Alice")

        assertEquals(Outcome.Success(Unit), deleteAccount(ALICE))
        assertFalse(fixture.accounts.exists(ALICE))
    }

    @Test
    fun `keeps the ledger by default`() = runBlocking {
        givenAliceWithHistory()
        keepHistory = true

        deleteAccount(ALICE)

        assertEquals(1, fixture.log.entries.size, "the audit trail outlives the account it describes")
    }

    @Test
    fun `purges the ledger when the operator asked for that`() = runBlocking {
        givenAliceWithHistory()
        keepHistory = false

        deleteAccount(ALICE)

        assertTrue(fixture.log.entries.isEmpty())
    }

    @Test
    fun `reads the retention setting per call so reload works`() = runBlocking {
        // keep-transaction-history is reloadable — ConfigService does not warn that it needs a
        // restart — so it is supplied, not captured. This fails if someone passes a Boolean instead.
        givenAliceWithHistory()
        keepHistory = true
        deleteAccount(ALICE)
        assertEquals(1, fixture.log.entries.size)

        givenAliceWithHistory()
        keepHistory = false
        deleteAccount(ALICE)

        assertTrue(fixture.log.entries.isEmpty())
    }

    @Test
    fun `reports a missing account`() = runBlocking {
        val result = deleteAccount(BOB)

        assertEquals(EconomyError.AccountNotFound(BOB), (result as Outcome.Failure).error)
    }

    @Test
    fun `does not purge the history of an account that does not exist`() = runBlocking {
        // A typed Failure is a normal return, not a throw, so the transaction commits. Purging
        // before checking would destroy an audit trail as a side effect of a *failed* delete.
        givenAliceWithHistory()
        fixture.accounts.delete(ALICE)
        keepHistory = false

        val result = deleteAccount(ALICE)

        assertInstanceOf(Outcome.Failure::class.java, result)
        assertEquals(1, fixture.log.entries.size, "a failed delete must not erase anything")
    }

    @Test
    fun `keeps the account when the purge fails`() = runBlocking {
        // Both halves commit or neither does: an operator retrying "erase this player" must not find
        // the account gone and the records they wanted rid of still there.
        givenAliceWithHistory()
        keepHistory = false
        fixture.log.failWith = SQLException("connection reset")

        val result = deleteAccount(ALICE)

        assertInstanceOf(Outcome.Failure::class.java, result)
        assertTrue(fixture.accounts.exists(ALICE), "the delete must roll back with the purge")
        assertEquals(1, fixture.log.entries.size)
    }
}
