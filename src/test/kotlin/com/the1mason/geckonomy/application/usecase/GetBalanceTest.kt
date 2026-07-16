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

class GetBalanceTest {

    private val fixture = EconomyFixture()
    private val getBalance = GetBalance(fixture.accounts, fixture.balances, fixture.amounts, fixture.guard)

    private val coins = TestCurrencies.COINS.code

    @Test
    fun `reads a stored balance`() = runBlocking {
        fixture.givenAccount(ALICE)
        fixture.balances.set(ALICE, TestCurrencies.COINS, BigDecimal("75.00"))

        val result = getBalance(ALICE, coins)

        assertEquals(BigDecimal("75.00"), (result as Outcome.Success).value.amount)
        assertEquals(TestCurrencies.COINS, result.value.currency)
    }

    @Test
    fun `normalizes the stored scale to the currency's digits`() {
        // Storage keeps scale 4. A player reading their balance must see 75.00, not 75.0000.
        runBlocking {
            fixture.givenAccount(ALICE)
            fixture.balances.set(ALICE, TestCurrencies.COINS, BigDecimal("75.0000"))

            val result = getBalance(ALICE, coins)

            assertEquals(BigDecimal("75.00"), (result as Outcome.Success).value.amount)
        }
    }

    @Test
    fun `reports zero for an account with no row in that currency`() = runBlocking {
        // GEMS was added to config after Alice existed, so she has no row. Zero — not GEMS's
        // starting-balance — because that is what her next deposit will build on.
        fixture.givenAccount(ALICE)

        val result = getBalance(ALICE, TestCurrencies.GEMS.code)

        assertEquals(BigDecimal("0"), (result as Outcome.Success).value.amount)
    }

    @Test
    fun `reports a missing account`() = runBlocking {
        val result = getBalance(BOB, coins)

        assertEquals(EconomyError.AccountNotFound(BOB), (result as Outcome.Failure).error)
    }

    @Test
    fun `reports an unknown currency`() = runBlocking {
        fixture.givenAccount(ALICE)
        val unknown = CurrencyCode("unobtainium")

        val result = getBalance(ALICE, unknown)

        assertEquals(EconomyError.UnknownCurrency(unknown), (result as Outcome.Failure).error)
    }

    /** See `AccountQueriesTest.maps a storage failure on every query` for the explicit `: Unit`. */
    @Test
    fun `does not ask whether the account exists when a balance is stored`(): Unit = runBlocking {
        // The hot path — every /balance and every join hydration — must cost one query, not two.
        fixture.givenAccount(ALICE)
        fixture.balances.set(ALICE, TestCurrencies.COINS, BigDecimal("75.00"))
        fixture.accounts.failWith = SQLException("exists() must not have been called")

        assertInstanceOf(Outcome.Success::class.java, getBalance(ALICE, coins))
    }

    @Test
    fun `maps a storage failure`(): Unit = runBlocking {
        fixture.givenAccount(ALICE)
        fixture.balances.failWith = SQLException("connection reset")

        val result = getBalance(ALICE, coins)

        assertInstanceOf(EconomyError.StorageFailure::class.java, (result as Outcome.Failure).error)
    }
}
