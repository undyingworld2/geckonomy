package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.result.then
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.Money
import com.the1mason.geckonomy.domain.port.AccountRepository
import com.the1mason.geckonomy.domain.port.BalanceRepository

/** One `/baltop` row: where an account placed, what it is called, and what it holds. */
data class TopBalance(val rank: Int, val id: AccountId, val name: String, val balance: Money)

/**
 * The richest accounts in a currency (SPEC.md FR-CMD3).
 *
 * Two queries, both bounded by [limit]: the balances, then the names of exactly those accounts. The
 * unbounded [ListAccountNames] would also answer the second half, and reads every account on the
 * server to do it.
 */
class ListTopBalances internal constructor(
    private val accounts: AccountRepository,
    private val balances: BalanceRepository,
    private val amounts: Amounts,
    private val guard: StorageGuard,
) {

    /**
     * [limit] rows, richest first.
     *
     * An account whose name has gone missing is **skipped, not shown nameless**. It cannot happen —
     * a balance row implies an account row through the foreign key (DATA_MODEL.md §1) — so the
     * alternative is inventing a placeholder for a state the schema forbids, and putting `null` or a
     * bare UUID in front of players if it ever stops forbidding it.
     *
     * Ranks number the rows returned, so they stay contiguous even then.
     */
    suspend operator fun invoke(currency: CurrencyCode, limit: Int): Outcome<List<TopBalance>> =
        amounts.currency(currency).then { resolved ->
            guard.guarding({ "reading the top $limit ${currency.value} balances" }) {
                val rows = balances.top(resolved, limit)
                val names = accounts.namesOf(rows.map { (id, _) -> id })
                Outcome.Success(
                    rows.mapNotNull { (id, amount) ->
                        val name = names[id] ?: return@mapNotNull null
                        Triple(id, name, amounts.balance(amount, resolved))
                    }.mapIndexed { index, (id, name, balance) ->
                        TopBalance(index + 1, id, name, balance)
                    },
                )
            }
        }
}
