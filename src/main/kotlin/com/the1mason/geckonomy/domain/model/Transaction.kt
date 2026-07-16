package com.the1mason.geckonomy.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * An immutable audit record of a balance change — one row of the ledger.
 *
 * Append-only: never updated, never deleted by normal operations (DOMAIN_MODEL.md §4). That is what
 * makes the ledger trustworthy as an audit trail, and why [resultingBalance] is stored rather than
 * recomputed — it records what the balance actually *was* at the time, even if config or code
 * changes later.
 *
 * @property id identity of this ledger row (not the account's).
 * @property accountId the account whose balance moved.
 * @property currency which currency moved.
 * @property delta the signed change: negative for a withdrawal, positive for a deposit.
 * @property resultingBalance the balance immediately after this change.
 * @property type what kind of operation caused it.
 * @property sourcePlugin who asked: a Vault caller's plugin name, or `"geckonomy"` for our own
 *   commands. `null` when unattributable.
 * @property counterparty the other account in a transfer; `null` for single-sided operations.
 * @property createdAt when the change happened. Supplied by the caller from an injected `Clock`, so
 *   tests stay deterministic (CODING_STANDARDS.md §6).
 */
data class Transaction(
    val id: UUID,
    val accountId: AccountId,
    val currency: CurrencyCode,
    val delta: BigDecimal,
    val resultingBalance: BigDecimal,
    val type: TransactionType,
    val sourcePlugin: String?,
    val counterparty: AccountId?,
    val createdAt: Instant,
)

/**
 * What kind of operation produced a [Transaction].
 *
 * A transfer writes *two* rows — [TRANSFER_OUT] on the payer and [TRANSFER_IN] on the payee — rather
 * than one row with two accounts, so that each account's ledger reads as a complete history of its
 * own balance (ARCHITECTURE.md §5).
 */
enum class TransactionType {
    /** Balance increased. */
    DEPOSIT,

    /** Balance decreased. */
    WITHDRAW,

    /** Balance replaced outright, typically by an admin. */
    SET,

    /** The receiving side of a transfer. */
    TRANSFER_IN,

    /** The sending side of a transfer. */
    TRANSFER_OUT,
}
