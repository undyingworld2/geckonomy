package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.application.EconomyFixture.Companion.CAROL
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.CurrencyCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.SQLException

class ListTopBalancesTest {

    private val fixture = EconomyFixture()
    private val listTop = ListTopBalances(fixture.accounts, fixture.balances, fixture.amounts, fixture.guard)

    private val coins = TestCurrencies.COINS.code

    private suspend fun given(vararg holdings: Pair<AccountId, String>) {
        holdings.forEachIndexed { index, (id, amount) ->
            fixture.givenAccount(id, "Player$index")
            fixture.balances.set(id, TestCurrencies.COINS, BigDecimal(amount))
        }
    }

    @Test
    fun `ranks the richest first`() = runBlocking {
        fixture.givenAccount(ALICE, "Alice")
        fixture.givenAccount(BOB, "Bob")
        fixture.balances.set(ALICE, TestCurrencies.COINS, BigDecimal("10.00"))
        fixture.balances.set(BOB, TestCurrencies.COINS, BigDecimal("90.00"))

        val rows = (listTop(coins, 10) as Outcome.Success).value

        assertEquals(listOf(1 to "Bob", 2 to "Alice"), rows.map { it.rank to it.name })
        assertEquals(BigDecimal("90.00"), rows.first().balance.amount)
    }

    @Test
    fun `honours the limit`() = runBlocking {
        given(ALICE to "10.00", BOB to "90.00", CAROL to "50.00")

        val rows = (listTop(coins, 2) as Outcome.Success).value

        assertEquals(2, rows.size)
        assertEquals(listOf(1, 2), rows.map { it.rank })
    }

    @Test
    fun `is empty when nobody holds the currency`() = runBlocking {
        val rows = (listTop(coins, 10) as Outcome.Success).value

        assertEquals(emptyList<TopBalance>(), rows)
    }

    @Test
    fun `normalizes the stored scale to the currency's digits`() = runBlocking {
        fixture.givenAccount(ALICE, "Alice")
        fixture.balances.set(ALICE, TestCurrencies.COINS, BigDecimal("75.0000"))

        val rows = (listTop(coins, 10) as Outcome.Success).value

        assertEquals(BigDecimal("75.00"), rows.single().balance.amount)
    }

    @Test
    fun `reports an unknown currency`() = runBlocking {
        val unknown = CurrencyCode("unobtainium")

        val result = listTop(unknown, 10)

        assertEquals(EconomyError.UnknownCurrency(unknown), (result as Outcome.Failure).error)
    }

    @Test
    fun `maps a storage failure`(): Unit = runBlocking {
        fixture.givenAccount(ALICE, "Alice")
        fixture.balances.set(ALICE, TestCurrencies.COINS, BigDecimal("10.00"))
        fixture.accounts.failWith = SQLException("connection reset")

        val result = listTop(coins, 10)

        assertInstanceOf(EconomyError.StorageFailure::class.java, (result as Outcome.Failure).error)
    }
}
