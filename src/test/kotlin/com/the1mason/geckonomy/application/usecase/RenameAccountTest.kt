package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.sql.SQLException

class RenameAccountTest {

    private val fixture = EconomyFixture()
    private val renameAccount = RenameAccount(fixture.accounts, fixture.guard)

    @Test
    fun `renames an account`() = runBlocking {
        fixture.givenAccount(ALICE, "Alice")

        assertEquals(Outcome.Success(Unit), renameAccount(ALICE, "AliceV2"))
        assertEquals("AliceV2", fixture.accounts.findName(ALICE))
    }

    @Test
    fun `reports a missing account`() = runBlocking {
        val result = renameAccount(BOB, "Bob")

        assertEquals(EconomyError.AccountNotFound(BOB), (result as Outcome.Failure).error)
    }

    /** See `AccountQueriesTest.maps a storage failure on every query` for the explicit `: Unit`. */
    @Test
    fun `maps a storage failure`(): Unit = runBlocking {
        fixture.accounts.failWith = SQLException("connection reset")

        val result = renameAccount(ALICE, "Alice")

        assertInstanceOf(EconomyError.StorageFailure::class.java, (result as Outcome.Failure).error)
    }
}
