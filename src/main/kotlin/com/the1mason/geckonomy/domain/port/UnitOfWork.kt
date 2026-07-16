package com.the1mason.geckonomy.domain.port

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
     * @return whatever [block] returns, once committed.
     */
    suspend fun <T> transaction(block: suspend (TxContext) -> T): T
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
