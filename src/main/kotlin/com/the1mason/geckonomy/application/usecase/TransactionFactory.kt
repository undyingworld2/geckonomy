package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.Attribution
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.Transaction
import com.the1mason.geckonomy.domain.model.TransactionType
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

/**
 * Builds ledger rows.
 *
 * Exists to hold the two things a [Transaction] needs that are not arguments — the time and the id —
 * behind injected sources, which is what [Transaction]'s own KDoc asks for ("Supplied by the caller
 * from an injected `Clock`, so tests stay deterministic", CODING_STANDARDS.md §6). With both fixed, a
 * test asserts a whole expected row with one `assertEquals` instead of picking over it field by field
 * and skipping the two it cannot predict.
 *
 * @param ids where row ids come from. A parameter only so tests can make them predictable; production
 *   takes the default.
 */
internal class TransactionFactory(
    private val clock: Clock,
    private val ids: () -> UUID = UUID::randomUUID,
) {

    /** One ledger row. [counterparty] is set only for the two sides of a transfer. */
    fun entry(
        accountId: AccountId,
        currency: CurrencyCode,
        delta: BigDecimal,
        resultingBalance: BigDecimal,
        type: TransactionType,
        by: Attribution,
        counterparty: AccountId? = null,
    ): Transaction = Transaction(
        id = ids(),
        accountId = accountId,
        currency = currency,
        delta = delta,
        resultingBalance = resultingBalance,
        type = type,
        sourcePlugin = by.plugin,
        counterparty = counterparty,
        createdAt = clock.instant(),
    )
}
