package com.the1mason.geckonomy.domain.policy

import com.the1mason.geckonomy.domain.model.Money
import java.math.BigDecimal

/**
 * Decides whether a balance is allowed to end up below zero.
 *
 * Split out from the withdraw path so the rule is stated once and tested directly, rather than
 * hiding as an `if` inside every operation that can reduce a balance (DOMAIN_MODEL.md §3).
 *
 * @param allowOverdraft from `settings.allow-overdraft`; defaults off, matching config
 *   (CONFIGURATION.md §2). When off, a withdrawal that would go negative is refused.
 */
class OverdraftPolicy(private val allowOverdraft: Boolean = false) {

    /**
     * Whether a balance of [resultingBalance] is permitted.
     *
     * Takes the *result* of the operation rather than the amount being withdrawn, because that is
     * the thing the rule is actually about — and it lets one function cover withdraw, transfer, and
     * an admin `set` to a negative number alike.
     */
    fun permits(resultingBalance: BigDecimal): Boolean =
        allowOverdraft || resultingBalance.signum() >= 0

    /** Whether a resulting balance of [resulting] is permitted. */
    fun permits(resulting: Money): Boolean = permits(resulting.amount)

    /**
     * Whether any negative balance at all is permitted.
     *
     * [permits] answers for one amount, which suits a caller holding the result of an operation. This
     * answers for the rule itself, which is what a caller must ask when it cannot enumerate the
     * amounts — notably the SQL balance repository, which pushes the guard into a `WHERE` clause and
     * so needs to know whether to emit the clause at all, not whether one particular number passes.
     */
    fun allowsNegativeBalances(): Boolean = allowOverdraft
}
