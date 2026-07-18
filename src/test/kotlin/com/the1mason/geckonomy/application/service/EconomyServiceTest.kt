package com.the1mason.geckonomy.application.service

import com.the1mason.geckonomy.application.Attribution
import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.TestCurrencies
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The facade holds no rules, so this does not re-test them — each use case's own suite does. What is
 * left, and what can only break here, is the wiring: the defaults it applies and that each function
 * reaches the use case it claims to.
 */
class EconomyServiceTest {

    private val fixture = EconomyFixture()
    private val service = fixture.service

    @Test
    fun `defaults to the configured default currency`() = runBlocking {
        service.createAccount(ALICE, "Alice")

        // COINS is the default and starts at 100.00; naming no currency must find it.
        val result = service.balance(ALICE)

        assertEquals(BigDecimal("100.00"), (result as Outcome.Success).value.amount)
        assertEquals(TestCurrencies.COINS, result.value.currency)
    }

    @Test
    fun `uses a named currency over the default`() = runBlocking {
        service.createAccount(ALICE, "Alice")

        val result = service.balance(ALICE, TestCurrencies.GEMS.code)

        assertEquals(TestCurrencies.GEMS, (result as Outcome.Success).value.currency)
    }

    @Test
    fun `attributes a change to geckonomy by default`() = runBlocking {
        service.createAccount(ALICE, "Alice")

        service.deposit(ALICE, BigDecimal("25.00"))

        assertEquals("geckonomy", fixture.log.entries.single().sourcePlugin)
    }

    @Test
    fun `passes a caller's attribution through`() = runBlocking {
        service.createAccount(ALICE, "Alice")

        service.deposit(ALICE, BigDecimal("25.00"), by = Attribution("ShopPlugin"))

        assertEquals("ShopPlugin", fixture.log.entries.single().sourcePlugin)
    }

    @Test
    fun `reports the default currency`() {
        assertEquals(TestCurrencies.COINS, service.defaultCurrency())
    }

    @Test
    fun `lists every currency`() {
        assertEquals(listOf(TestCurrencies.COINS, TestCurrencies.GEMS), service.currencies())
    }

    @Test
    fun `delegates every account operation`() = runBlocking {
        assertEquals(Outcome.Success(true), service.createAccount(ALICE, "Alice"))
        assertEquals(Outcome.Success(false), service.createAccount(ALICE, "Alice"))
        assertEquals(Outcome.Success(true), service.exists(ALICE))
        assertEquals(Outcome.Success("Alice"), service.name(ALICE))
        assertEquals(Outcome.Success(mapOf(ALICE to "Alice")), service.nameMap())
        assertEquals(Outcome.Success(Unit), service.rename(ALICE, "AliceV2"))
        assertEquals(Outcome.Success("AliceV2"), service.name(ALICE))
        assertEquals(Outcome.Success(Unit), service.delete(ALICE))
        assertEquals(Outcome.Success(false), service.exists(ALICE))
    }

    @Test
    fun `delegates every balance operation`() = runBlocking {
        service.createAccount(ALICE, "Alice")
        service.createAccount(BOB, "Bob")

        assertEquals(Outcome.Success(true), service.has(ALICE, BigDecimal("100.00")))
        assertEquals(Outcome.Success(true), service.canDeposit(ALICE, BigDecimal("1.00")))
        assertEquals(Outcome.Success(true), service.canWithdraw(ALICE, BigDecimal("100.00")))
        assertEquals(Outcome.Success(false), service.canWithdraw(ALICE, BigDecimal("100.01")))

        assertEquals(BigDecimal("125.00"), (service.deposit(ALICE, BigDecimal("25.00")) as Outcome.Success).value.amount)
        assertEquals(BigDecimal("100.00"), (service.withdraw(ALICE, BigDecimal("25.00")) as Outcome.Success).value.amount)
        assertEquals(BigDecimal("50.00"), (service.set(ALICE, BigDecimal("50.00")) as Outcome.Success).value.amount)

        val moved = service.transfer(ALICE, BOB, BigDecimal("20.00")) as Outcome.Success
        assertEquals(BigDecimal("30.00"), moved.value.payerBalance.amount)
        assertEquals(BigDecimal("120.00"), moved.value.payeeBalance.amount)
    }

    @Test
    fun `honours the retention setting through the facade`() = runBlocking {
        service.createAccount(ALICE, "Alice")
        service.deposit(ALICE, BigDecimal("25.00"))
        fixture.keepHistory = false

        service.delete(ALICE)

        assertTrue(fixture.log.entries.isEmpty())
    }
}
