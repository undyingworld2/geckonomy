package com.the1mason.geckonomy.infrastructure.vault

import com.the1mason.geckonomy.application.EconomyFixture.Companion.ALICE
import com.the1mason.geckonomy.application.EconomyFixture.Companion.BOB
import com.the1mason.geckonomy.application.result.EconomyError
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.result.Transferred
import com.the1mason.geckonomy.domain.coins
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.infrastructure.i18n.CurrencyNames
import com.the1mason.geckonomy.infrastructure.i18n.FormatMoney
import com.the1mason.geckonomy.infrastructure.i18n.LanguageRepository
import com.the1mason.geckonomy.infrastructure.i18n.LogCapture
import com.the1mason.geckonomy.infrastructure.i18n.MessageService
import net.milkbowl.vault2.economy.EconomyResponse.ResponseType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.util.Locale

/** Response mapping against the **real** `en.yml`, so an error message that stopped existing fails here. */
class ResponseMapperTest {

    @TempDir
    lateinit var directory: Path

    private val log = LogCapture()

    private val mapper: ResponseMapper by lazy {
        ResponseMapper(
            MessageService(LanguageRepository(directory, log.logger), { "en" }).apply { reload() },
            FormatMoney({ Locale.US }, CurrencyNames { _, _ -> null }),
        )
    }

    @Test
    fun `success carries the requested amount and the resulting balance`() {
        val response = mapper.response(Outcome.Success("150.00".coins), BigDecimal("50.00"))

        assertEquals(ResponseType.SUCCESS, response.type)
        assertTrue(response.transactionSuccess())
        assertEquals(BigDecimal("50.00"), response.amount)
        assertEquals(BigDecimal("150.00"), response.balance)
        assertEquals("", response.errorMessage)
    }

    @Test
    fun `a failure reports zero moved`() {
        val response = mapper.response(Outcome.Failure(EconomyError.InvalidAmount(BigDecimal("-1"), "negative")), BigDecimal("-1"))

        assertEquals(ResponseType.FAILURE, response.type)
        assertFalse(response.transactionSuccess())
        assertEquals(BigDecimal.ZERO, response.amount)
        assertEquals(BigDecimal.ZERO, response.balance)
    }

    @Test
    fun `insufficient funds renders the required amount from the language file`() {
        val error = EconomyError.InsufficientFunds(ALICE, "50.00".coins, "Alice")

        assertEquals(
            "[Geckonomy] Alice doesn't have $50.00.",
            mapper.errorMessage(error),
        )
    }

    @Test
    fun `insufficient funds falls back to the uuid when the name is unknown`() {
        // Ugly but true, and the only honest thing left to say: this string reaches players through any
        // plugin that shows Vault's errorMessage, so a name is worth a read on the failure path.
        val error = EconomyError.InsufficientFunds(ALICE, "50.00".coins)

        assertEquals(
            "[Geckonomy] ${ALICE.value} doesn't have $50.00.",
            mapper.errorMessage(error),
        )
    }

    @Test
    fun `unknown currency names the code the caller passed`() {
        val message = mapper.errorMessage(EconomyError.UnknownCurrency(CurrencyCode("doubloons")))

        assertEquals("[Geckonomy] Unknown currency: doubloons.", message)
    }

    @Test
    fun `account not found names the uuid`() {
        val message = mapper.errorMessage(EconomyError.AccountNotFound(BOB))

        assertEquals("[Geckonomy] No account for ${BOB.value}.", message)
    }

    @Test
    fun `storage failure stays vague and leaks no cause`() {
        // error.storage is deliberately vague, and the cause belongs in the console. A Vault caller
        // may print errorMessage straight to a player; a SQL string must not arrive there.
        val message = mapper.errorMessage(EconomyError.StorageFailure("depositing 5", "constraint gk_balance_pk violated"))

        assertFalse(message.contains("gk_balance_pk"))
        assertTrue(message.contains("Something went wrong"))
    }

    @Test
    fun `every error variant renders a real message rather than a raw key`() {
        val errors = listOf(
            EconomyError.UnknownCurrency(CurrencyCode("nope")),
            EconomyError.AccountNotFound(ALICE),
            EconomyError.InsufficientFunds(ALICE, "1.00".coins),
            EconomyError.InvalidAmount(BigDecimal.ZERO, "zero"),
            EconomyError.StorageFailure("context", "cause"),
        )

        errors.forEach { error ->
            val message = mapper.errorMessage(error)
            assertFalse(message.startsWith("error."), "raw key leaked for $error: $message")
            assertTrue(message.isNotBlank(), "blank message for $error")
        }
        assertTrue(log.warnings().isEmpty(), "a missing key warned: ${log.warnings()}")
    }

    @Test
    fun `a transfer reports both resulting balances`() {
        val response = mapper.transfer(
            Outcome.Success(Transferred(payerBalance = "40.00".coins, payeeBalance = "60.00".coins)),
            from = ALICE,
            to = BOB,
            requested = BigDecimal("10.00"),
        )

        assertEquals(ResponseType.SUCCESS, response.type)
        assertEquals(BigDecimal("10.00"), response.amount)
        assertEquals(BigDecimal("40.00"), response.balance(ALICE.value).orElseThrow())
        assertEquals(BigDecimal("60.00"), response.balance(BOB.value).orElseThrow())
    }

    @Test
    fun `a failed transfer carries no balances`() {
        val response = mapper.transfer(
            Outcome.Failure(EconomyError.InsufficientFunds(ALICE, "10.00".coins)),
            from = ALICE,
            to = BOB,
            requested = BigDecimal("10.00"),
        )

        assertEquals(ResponseType.FAILURE, response.type)
        assertTrue(response.balance(ALICE.value).isEmpty)
        assertTrue(response.balance(BOB.value).isEmpty)
    }

    @Test
    fun `notImplemented keeps the reason for the developer reading it`() {
        val response = mapper.notImplemented("Shared accounts not supported")

        assertEquals(ResponseType.NOT_IMPLEMENTED, response.type)
        assertFalse(response.transactionSuccess())
        assertEquals("Shared accounts not supported", response.errorMessage)
    }
}
