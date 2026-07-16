package com.the1mason.geckonomy.domain.model

import java.math.BigDecimal

/**
 * What one account holds in one currency.
 *
 * Typically materialized from the repository rather than constructed directly (DOMAIN_MODEL.md §2) —
 * it is a read projection, not a thing the domain mutates. Balance *changes* go through
 * [com.the1mason.geckonomy.domain.port.BalanceRepository] so they stay atomic.
 *
 * Holds a [CurrencyCode] rather than a whole [Currency]: a balance row is identified by the currency
 * it belongs to, and carrying the full definition would let a stale copy of config ride along with
 * data read from the database.
 */
data class Balance(
    val accountId: AccountId,
    val currency: CurrencyCode,
    val amount: BigDecimal,
)
