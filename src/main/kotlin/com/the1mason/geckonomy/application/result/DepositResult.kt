package com.the1mason.geckonomy.application.result

import com.the1mason.geckonomy.domain.model.Money

/** What a deposit answers with: the deposit itself, and the bonus that rode along with it, if any. */
typealias DepositResult = Outcome<Deposited>

/**
 * @property balance the deposited currency's balance after the change, as [OperationResult] reports it.
 * @property bonus `null` when no bonus was credited — none configured, declined, or rounded to zero.
 */
data class Deposited(val balance: Money, val bonus: BonusCredit? = null)

/** @property amount what was credited. @property balance the bonus currency's balance after it. */
data class BonusCredit(val amount: Money, val balance: Money)
