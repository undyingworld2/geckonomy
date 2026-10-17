package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.Attribution
import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.application.result.Deposited
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.coins
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.BonusRule
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.TransactionType
import com.the1mason.geckonomy.infrastructure.config.ConfigCurrencyRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.SQLException

/** SPEC.md FR-B9/FR-B10: a deposit of a currency with a bonus credits the bonus currency alongside it. */
class BonusDepositTest {

    /** One fractional digit, so the ratio's rounding is visible. */
    private val bonuses = TestCurrencies.COINS.copy(
        code = CurrencyCode("bonuses"),
        isDefault = false,
        fractionalDigits = 1,
        startingBalance = BigDecimal.ZERO,
    )

    private val gems = TestCurrencies.GEMS.code

    private fun gems(ratio: String = "1", byDefault: Boolean = true): Currency =
        TestCurrencies.GEMS.copy(bonus = BonusRule(bonuses.code, BigDecimal(ratio), byDefault))

    private suspend fun fixture(gems: Currency = gems()): EconomyFixture {
        val fixture = EconomyFixture(currencies = ConfigCurrencyRegistry(listOf(TestCurrencies.COINS, gems, bonuses)))
        fixture.givenAccount(ALICE)
        return fixture
    }

    private suspend fun EconomyFixture.bonusOf(id: AccountId) = balances.get(id, bonuses)

    @Test
    fun `credits the bonus by default and reports it`() = runBlocking {
        val f = fixture()

        val deposited = (f.service.deposit(ALICE, BigDecimal("10"), gems) as Outcome.Success).value

        assertEquals(BigDecimal("10"), deposited.balance.amount)
        assertEquals(BigDecimal("10.0"), deposited.bonus!!.amount.amount)
        assertEquals(BigDecimal("10.0"), deposited.bonus!!.balance.amount)
        assertEquals(0, BigDecimal.TEN.compareTo(f.bonusOf(ALICE)))
    }

    @Test
    fun `writes a BONUS ledger row with the same attribution`() = runBlocking {
        val f = fixture()

        f.service.deposit(ALICE, BigDecimal("10"), gems, Attribution("Shop"))

        assertEquals(listOf(TransactionType.DEPOSIT, TransactionType.BONUS), f.log.entries.map { it.type })
        assertEquals(bonuses.code, f.log.entries.last().currency)
        assertEquals("Shop", f.log.entries.last().sourcePlugin)
    }

    @Test
    fun `an explicit false withholds a default bonus`() = runBlocking {
        val f = fixture()

        val result = f.service.deposit(ALICE, BigDecimal("10"), gems, bonus = false)

        assertNull((result as Outcome.Success).value.bonus)
        assertNull(f.bonusOf(ALICE))
        assertEquals(listOf(TransactionType.DEPOSIT), f.log.entries.map { it.type })
    }

    @Test
    fun `an explicit true grants a bonus that is off by default`() = runBlocking {
        val f = fixture(gems(byDefault = false))

        f.service.deposit(ALICE, BigDecimal("10"), gems)
        assertNull(f.bonusOf(ALICE))

        f.service.deposit(ALICE, BigDecimal("10"), gems, bonus = true)
        assertEquals(0, BigDecimal.TEN.compareTo(f.bonusOf(ALICE)))
    }

    @Test
    fun `applies the ratio, rounded to the bonus currency's digits`() = runBlocking {
        val f = fixture(gems(ratio = "0.25"))

        val result = f.service.deposit(ALICE, BigDecimal("3"), gems)

        // 3 × 0.25 = 0.75, HALF_UP at one digit.
        assertEquals(BigDecimal("0.8"), (result as Outcome.Success).value.bonus!!.amount.amount)
    }

    @Test
    fun `a bonus that rounds to zero writes nothing`() = runBlocking {
        val f = fixture(gems(ratio = "0.01"))

        val result = f.service.deposit(ALICE, BigDecimal("1"), gems)

        assertNull((result as Outcome.Success).value.bonus)
        assertEquals(listOf(TransactionType.DEPOSIT), f.log.entries.map { it.type })
    }

    @Test
    fun `asking for a bonus on a currency without one is refused before any write`() = runBlocking {
        val f = fixture()

        val result = f.service.deposit(ALICE, BigDecimal("1.00"), TestCurrencies.COINS.code, bonus = true)

        assertEquals(EconomyError.NoBonusCurrency(TestCurrencies.COINS.code), (result as Outcome.Failure).error)
        assertEquals(0, f.log.entries.size)
    }

    @Test
    fun `a failed bonus write rolls back the deposit too`() = runBlocking {
        val f = fixture()
        f.log.failWith(SQLException("ledger is down"), passes = 1)

        val result = f.service.deposit(ALICE, BigDecimal("10"), gems)

        assertInstanceOf(EconomyError.StorageFailure::class.java, (result as Outcome.Failure).error)
        assertNull(f.balances.get(ALICE, TestCurrencies.GEMS))
        assertNull(f.bonusOf(ALICE))
        assertEquals(0, f.log.entries.size)
    }

    @Test
    fun `a missing account gets neither`() = runBlocking {
        val f = fixture()

        val result = f.service.deposit(BOB, BigDecimal("10"), gems)

        assertEquals(EconomyError.AccountNotFound(BOB), (result as Outcome.Failure).error)
        assertEquals(0, f.log.entries.size)
    }

    @Test
    fun `withdraw, set and transfer never touch the bonus`() = runBlocking {
        val f = fixture()
        f.givenAccount(BOB)
        f.service.deposit(ALICE, BigDecimal("10"), gems)
        val before = f.bonusOf(ALICE)

        f.service.withdraw(ALICE, BigDecimal("2"), gems)
        f.service.set(ALICE, BigDecimal("50"), gems)
        f.service.transfer(ALICE, BOB, BigDecimal("5"), gems)

        assertEquals(before, f.bonusOf(ALICE))
        assertNull(f.bonusOf(BOB))
    }

    @Test
    fun `a currency without a bonus answers with the balance alone`() = runBlocking {
        val f = fixture()

        val result = f.service.deposit(ALICE, BigDecimal("1.00"), TestCurrencies.COINS.code)

        assertEquals(Deposited("1.00".coins), (result as Outcome.Success).value)
    }
}
