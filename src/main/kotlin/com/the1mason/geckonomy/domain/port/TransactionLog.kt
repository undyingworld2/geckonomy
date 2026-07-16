package com.the1mason.geckonomy.domain.port

import com.the1mason.geckonomy.domain.model.Transaction

/**
 * The audit ledger.
 *
 * Append-only by design: there is no update or delete here, because the port itself should make an
 * un-auditable change impossible to express (DOMAIN_MODEL.md §4).
 */
interface TransactionLog {

    /** Records [tx]. */
    suspend fun append(tx: Transaction)
}
