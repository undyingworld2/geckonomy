package com.the1mason.geckonomy.domain.port

import com.the1mason.geckonomy.domain.model.AccountId

/**
 * A transactional boundary for operations that must not half-happen.
 *
 * A transfer debits one account and credits another: without a boundary, a failure between the two
 * destroys money (ARCHITECTURE.md §5). The domain states that requirement here; infrastructure
 * satisfies it with a database transaction.
 */
interface UnitOfWork {

    /**
     * Runs [block] as one atomic unit — every change inside commits together, or none of them do.
     *
     * The [TxContext] handed to [block] is valid only for that call; work done through ports
     * captured from outside is *not* part of the transaction.
     *
     * **[block] may run more than once.** A transaction that loses a lock conflict to another server
     * (a deadlock, a lock-wait timeout, two first deposits racing to create one balance row) is rolled
     * back in full and retried, because the alternative is reporting a storage failure for a deposit
     * that would have succeeded a millisecond later (DATA_MODEL.md §4). So [block] must touch nothing
     * but [TxContext]: anything it does outside would happen once per attempt.
     *
     * @param writes the accounts whose balances [block] may change. An implementation runs two
     *   transactions that share one strictly one after the other, so writers to one balance queue
     *   rather than contend for its row lock. Empty for a transaction that changes no balance.
     * @return whatever [block] returns, once committed.
     */
    suspend fun <T> transaction(writes: Set<AccountId> = emptySet(), block: suspend (TxContext) -> T): T
}

/**
 * Repository access bound to a single in-flight transaction.
 *
 * Same ports as the ambient ones, but every call through these participates in the enclosing
 * [UnitOfWork.transaction] — the distinction is the whole point of the type. Obtained from
 * [UnitOfWork.transaction]; never injected directly.
 */
interface TxContext {
    val accounts: AccountRepository
    val balance: BalanceRepository
    val log: TransactionLog
}
