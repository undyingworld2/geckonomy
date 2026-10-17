package com.the1mason.geckonomy.infrastructure.bukkit.command

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.BonusRule
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.infrastructure.config.ConfigCurrencyRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.math.BigDecimal

/** `/eco give … [bonus|nobonus]`, and that `take`/`set` leave the bonus currency alone (SPEC.md FR-B9). */
class EcoGiveBonusTest {

    private val bonuses = TestCurrencies.GEMS.copy(
        code = CurrencyCode("bonuses"),
        singular = "Bonus",
        plural = "Bonuses",
    )
    private val gems = TestCurrencies.GEMS.copy(bonus = BonusRule(bonuses.code, BigDecimal.ONE, byDefault = true))

    private val fixture = EconomyFixture(
        currencies = ConfigCurrencyRegistry(listOf(TestCurrencies.COINS, gems, bonuses)),
    )
    private val harness = CommandHarness(fixture)

    @AfterEach
    fun tearDown() = harness.unmock()

    private fun admin(): PlayerMock = harness.server.addPlayer("Admin").apply {
        addAttachment(harness.plugin, "geckonomy.admin", true)
    }

    private fun bob(): AccountId {
        val id = AccountId(harness.player("Bob").uniqueId)
        runBlocking { fixture.givenAccount(id, "Bob") }
        return id
    }

    private fun balance(id: AccountId, currency: Currency): BigDecimal? =
        runBlocking { fixture.balances.get(id, currency) }?.stripTrailingZeros()

    @Test
    fun `give grants the bonus by default and says so`() {
        val eco = admin()
        val bob = bob()

        harness.dispatch(eco, "eco give Bob 10 gems")

        assertEquals("[Geckonomy] Gave 10 Gems (+10 Bonuses) to Bob.", harness.nextMessage(eco))
        assertEquals(BigDecimal.TEN.stripTrailingZeros(), balance(bob, bonuses))
    }

    @Test
    fun `nobonus withholds it`() {
        val eco = admin()
        val bob = bob()

        harness.dispatch(eco, "eco give Bob 10 gems nobonus")

        assertEquals("[Geckonomy] Gave 10 Gems to Bob.", harness.nextMessage(eco))
        assertNull(balance(bob, bonuses))
    }

    @Test
    fun `the keyword in the currency slot means the default currency`() {
        val eco = admin()
        bob()

        harness.dispatch(eco, "eco give Bob 10 bonus")

        // Coins has no bonus, so asking for one is refused rather than silently ignored.
        assertEquals("[Geckonomy] coins has no bonus currency.", harness.nextMessage(eco))
    }

    @Test
    fun `nobonus on the default currency just gives`() {
        val eco = admin()
        val bob = bob()

        harness.dispatch(eco, "eco give Bob 10 nobonus")

        assertEquals("[Geckonomy] Gave \$10.00 to Bob.", harness.nextMessage(eco))
        assertEquals(BigDecimal.TEN.stripTrailingZeros(), balance(bob, TestCurrencies.COINS))
    }

    @Test
    fun `an online player's mirror sees the give and its bonus`() {
        val eco = admin()
        val bob = bob()
        harness.mirror.hydrate(bob, mapOf(gems.code to BigDecimal.ZERO, bonuses.code to BigDecimal.ZERO))

        harness.dispatch(eco, "eco give Bob 10 gems")

        assertEquals(BigDecimal("10"), harness.mirror.get(bob, gems.code))
        assertEquals(BigDecimal("10"), harness.mirror.get(bob, bonuses.code))
    }

    @Test
    fun `take and set never touch the bonus`() {
        val eco = admin()
        val bob = bob()
        harness.dispatch(eco, "eco give Bob 10 gems")
        harness.messages(eco)

        harness.dispatch(eco, "eco take Bob 5 gems")
        harness.dispatch(eco, "eco set Bob 100 gems")
        harness.dispatch(eco, "eco reset Bob gems")

        assertEquals(BigDecimal.TEN.stripTrailingZeros(), balance(bob, bonuses))
    }
}
