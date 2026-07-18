package com.the1mason.geckonomy.infrastructure.vault

import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.coins
import com.the1mason.geckonomy.domain.policy.RoundingPolicy
import com.the1mason.geckonomy.infrastructure.i18n.CurrencyNames
import com.the1mason.geckonomy.infrastructure.i18n.FormatMoney
import com.the1mason.geckonomy.infrastructure.i18n.LanguageRepository
import com.the1mason.geckonomy.infrastructure.i18n.LogCapture
import com.the1mason.geckonomy.infrastructure.i18n.MessageService
import net.milkbowl.vault.economy.EconomyResponse.ResponseType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import java.util.Locale

class LegacyResponseMapperTest {

    @TempDir
    lateinit var directory: Path

    private val log = LogCapture()
    private val halfUp = RoundingPolicy(RoundingMode.HALF_UP)

    private val mapper: LegacyResponseMapper by lazy {
        LegacyResponseMapper(
            ResponseMapper(
                MessageService(LanguageRepository(directory, log.logger), { "en" }).apply { reload() },
                FormatMoney({ Locale.US }, CurrencyNames { _, _ -> null }),
            ),
        )
    }

    @Test
    fun `success converts both amounts to double`() {
        val response = mapper.response(Outcome.Success("150.00".coins), BigDecimal("50.00"))

        assertEquals(ResponseType.SUCCESS, response.type)
        assertTrue(response.transactionSuccess())
        assertEquals(50.0, response.amount)
        assertEquals(150.0, response.balance)
    }

    @Test
    fun `a failure shares v2's localized message`() {
        val response = mapper.failure(EconomyError.InsufficientFunds(ALICE, "50.00".coins))

        assertEquals(ResponseType.FAILURE, response.type)
        assertEquals(0.0, response.amount)
        assertEquals(0.0, response.balance)
        assertTrue(response.errorMessage.contains("doesn't have $50.00"))
    }

    @Test
    fun `bank methods keep a reason a plugin developer can act on`() {
        val response = mapper.notImplemented("Banks not supported")

        assertEquals(ResponseType.NOT_IMPLEMENTED, response.type)
        assertFalse(response.transactionSuccess())
        assertEquals("Banks not supported", response.errorMessage)
    }

    @Test
    fun `toMoney uses valueOf, so a legacy caller's round number stays round`() {
        // BigDecimal(0.1) is 0.1000000000000000055511151231257827: the binary double read literally.
        // valueOf goes via toString and gives 0.1. The ledger stores the difference forever.
        assertEquals(BigDecimal("0.10"), 0.1.toMoney(TestCurrencies.COINS, halfUp))
    }

    @Test
    fun `toMoney rounds to the currency's scale`() {
        assertEquals(BigDecimal("1.24"), 1.235.toMoney(TestCurrencies.COINS, halfUp))
        assertEquals(BigDecimal("2"), 1.5.toMoney(TestCurrencies.GEMS, halfUp))
    }

    @Test
    fun `toMoney honours the configured rounding mode`() {
        assertEquals(BigDecimal("1.23"), 1.235.toMoney(TestCurrencies.COINS, RoundingPolicy(RoundingMode.DOWN)))
    }

    @Test
    fun `a whole-unit currency round-trips through double`() {
        val amount = 7.0.toMoney(TestCurrencies.GEMS, halfUp)

        assertEquals(BigDecimal("7"), amount)
        assertEquals(7.0, amount.toDouble())
    }
}
