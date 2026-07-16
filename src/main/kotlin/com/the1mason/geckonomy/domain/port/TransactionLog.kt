package com.the1mason.geckonomy.domain.port

import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.Transaction

/**
 * The audit ledger.
 *
 * Append-only for every *economy* operation: no update, and no delete of a single row, because the
 * port itself should make an un-auditable change impossible to express (DOMAIN_MODEL.md §4). A
 * balance change, once recorded, stays recorded.
 *
 * [purge] is the one exception, and it is deliberately shaped so it cannot be mistaken for one: it
 * erases an account's history wholesale, only as part of deleting the account itself. There is no way
 * to express "forget this one withdrawal".
 */
interface TransactionLog {

    /** Records [tx]. */
    suspend fun append(tx: Transaction)

    /**
     * Erases every ledger row belonging to [id].
     *
     * Exists because `settings.keep-transaction-history: false` means a deleted account leaves no
     * trace (CONFIGURATION.md §2, DATA_MODEL.md §6) — an operator's call, not the model's. The
     * retention rule lives in the application layer; this port only offers the capability, and
     * `gk_transaction` carries no foreign key to `gk_account` precisely so the two decisions stay
     * independent (DATA_MODEL.md §1).
     *
     * Only `DeleteAccount` calls this. A history the operator asked to keep is never touched.
     *
     * @return how many rows were removed.
     */
    suspend fun purge(id: AccountId): Int
}
