package com.the1mason.geckonomy.application.result

import com.the1mason.geckonomy.domain.model.Money

/**
 * Both sides of a completed transfer.
 *
 * Both balances, because both parties get told: `pay.sent` to the payer and `pay.received` to the
 * payee (LOCALIZATION.md §2), and a caller that had to re-read the other side would be reading it
 * outside the transaction that moved it.
 *
 * @property payerBalance what the sender holds now.
 * @property payeeBalance what the recipient holds now.
 */
data class Transferred(val payerBalance: Money, val payeeBalance: Money)

/** What a transfer answers with: both resulting balances, or why the money did not move. */
typealias TransferResult = Outcome<Transferred>
