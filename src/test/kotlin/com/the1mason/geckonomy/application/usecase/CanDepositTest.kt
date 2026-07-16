package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.CurrencyCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.SQLException

class CanDepositTest {

    private val fixture = EconomyFixture()
    private val canDeposit = CanDeposit(fixture.accounts, fixture.amounts, fixture.guard)

    private val coins = TestCurrencies.COINS.code

    @Test
    fun `says yes for any existing account`() = runBlocking {
        // There is no cap and no deposit permission, so an existing account can always be paid.
        fixture.givenAccount(ALICE)

        assertEquals(Outcome.Success(true), canDeposit(ALICE, BigDecimal("50.00"), coins))
    }

    @Test
    fun `does not require an existing balance row`() = runBlocking {
        fixture.givenAccount(ALICE)

        assertEquals(Outcome.Success(true), canDeposit(ALICE, BigDecimal("3"), TestCurrencies.GEMS.code))
    }

    @Test
    fun `reports a missing account`() = runBlocking {
        val result = canDeposit(BOB, BigDecimal("1.00"), coins)

        assertEquals(EconomyError.AccountNotFound(BOB), (result as Outcome.Failure).error)
    }

    /** See `AccountQueriesTest.maps a storage failure on every query` for the explicit `: Unit`. */
    @Test
    fun `rejects a zero or negative amount`(): Unit = runBlocking {
        fixture.givenAccount(ALICE)

        assertInstanceOf(Outcome.Failure::class.java, canDeposit(ALICE, BigDecimal.ZERO, coins))
        assertInstanceOf(Outcome.Failure::class.java, canDeposit(ALICE, BigDecimal("-1.00"), coins))
    }

    @Test
    fun `reports an unknown currency`() = runBlocking {
        fixture.givenAccount(ALICE)
        val unknown = CurrencyCode("unobtainium")

        val result = canDeposit(ALICE, BigDecimal("1.00"), unknown)

        assertEquals(EconomyError.UnknownCurrency(unknown), (result as Outcome.Failure).error)
    }

    @Test
    fun `maps a storage failure`(): Unit = runBlocking {
        fixture.accounts.failWith = SQLException("connection reset")

        val result = canDeposit(ALICE, BigDecimal("1.00"), coins)

        assertInstanceOf(EconomyError.StorageFailure::class.java, (result as Outcome.Failure).error)
    }
}
