package com.the1mason.geckonomy.infrastructure.bukkit

import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.infrastructure.bukkit.CurrencyAccess.Action
import com.the1mason.geckonomy.infrastructure.config.ConfigCurrencyRegistry
import com.the1mason.geckonomy.infrastructure.i18n.MessageKey
import io.mockk.every
import io.mockk.mockk
import org.bukkit.permissions.Permissible
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The two gates, and the promise that tab completion and the handler read them the same way.
 *
 * A unit test rather than a MockBukkit one because the flags need currencies the shipped pair does not
 * cover — nothing in `TestCurrencies` is `show-in-baltop: false`, since nothing in the example config
 * is.
 */
class CurrencyAccessTest {

    private val hidden = TestCurrencies.GEMS.copy(showInBaltop = false)
    private val currencies = ConfigCurrencyRegistry(listOf(TestCurrencies.COINS, hidden))
    private val access = CurrencyAccess(currencies)

    /** Holds every node — so only the flags can refuse anything. */
    private fun permitted(): Permissible = mockk { every { hasPermission(any<String>()) } returns true }

    private fun denying(vararg nodes: String): Permissible = mockk {
        every { hasPermission(any<String>()) } answers { firstArg<String>() !in nodes }
    }

    @Test
    fun `allows a currency whose flags and permissions both pass`() {
        assertNull(access.refusal(permitted(), Action.PAY, TestCurrencies.COINS))
    }

    @Test
    fun `a flag refusal beats a permission the player holds`() {
        // gems is transferable:false. No node grants past that.
        assertEquals(
            MessageKey.ERROR_NOT_TRANSFERABLE,
            access.refusal(permitted(), Action.PAY, hidden),
        )
    }

    @Test
    fun `balance-check-others is refused with its own message`() {
        assertEquals(
            MessageKey.ERROR_OTHERS_HIDDEN,
            access.refusal(permitted(), Action.BALANCE_OTHERS, hidden),
        )
    }

    @Test
    fun `a missing per-currency node is refused`() {
        assertEquals(
            MessageKey.ERROR_NO_CURRENCY_PERMISSION,
            access.refusal(denying("geckonomy.pay.coins"), Action.PAY, TestCurrencies.COINS),
        )
    }

    /** SPEC.md §7: `show-in-baltop: false` excludes a currency from `/baltop`. */
    @Test
    fun `a currency hidden from baltop is not offered and is refused`() {
        assertEquals(listOf(TestCurrencies.COINS), access.permitted(permitted(), Action.BALTOP))
        assertEquals(
            MessageKey.ERROR_NO_CURRENCY_PERMISSION,
            access.refusal(permitted(), Action.BALTOP, hidden),
        )
    }

    @Test
    fun `only transferable currencies are offered for pay`() {
        assertEquals(listOf(TestCurrencies.COINS), access.permitted(permitted(), Action.PAY))
    }

    @Test
    fun `own balance is never gated by a flag`() {
        assertNull(access.refusal(permitted(), Action.BALANCE, hidden))
        assertEquals(listOf(TestCurrencies.COINS, hidden), access.permitted(permitted(), Action.BALANCE))
    }

    /**
     * The promise the class exists for: what completion offers is exactly what the handler accepts.
     * A currency refused for any reason must not be suggested.
     */
    @Test
    fun `completion offers exactly what the handler would accept`() {
        val who = denying("geckonomy.balance.gems")

        val offered = access.permitted(who, Action.BALANCE)

        assertEquals(listOf(TestCurrencies.COINS), offered)
        offered.forEach { assertNull(access.refusal(who, Action.BALANCE, it)) }
    }

    @Test
    fun `the default currency is offered first`() {
        assertEquals(TestCurrencies.COINS, access.permitted(permitted(), Action.BALANCE).first())
    }

    @Test
    fun `nodes are named per action and currency`() {
        assertEquals("geckonomy.pay.coins", Action.PAY.node(TestCurrencies.COINS))
        assertEquals("geckonomy.balance.others.coins", Action.BALANCE_OTHERS.node(TestCurrencies.COINS))
        assertEquals("geckonomy.baltop.*", Action.BALTOP.wildcard)
        assertEquals("geckonomy.balance", Action.BALANCE.base)
    }
}
