package com.the1mason.geckonomy.application.usecase

import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.domain.model.Account
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.AccountType
import com.the1mason.geckonomy.domain.policy.RoundingPolicy
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import com.the1mason.geckonomy.domain.port.UnitOfWork
import java.time.Clock

/**
 * Creates an account and seeds its opening balances (SPEC.md FR-A1, FR-A6).
 *
 * @param rounding supplied per call, not captured — see [Amounts].
 */
class CreateAccount internal constructor(
    private val unitOfWork: UnitOfWork,
    private val currencies: CurrencyRegistry,
    private val rounding: () -> RoundingPolicy,
    private val clock: Clock,
    private val guard: StorageGuard,
) {

    /**
     * Creates [id], seeding one balance row per configured currency at its `starting-balance`.
     *
     * **Idempotent** (FR-A1): account creation races — a join listener and a Vault plugin may both
     * reach for the same player — so a second call is a no-op that answers `false`, not an error. The
     * seeding is gated on having actually created the row, which is what stops the loser of that race
     * from resetting a balance back to `starting-balance`.
     *
     * All of it runs in one transaction. Not for atomicity against a concurrent *reader*, but because
     * the idempotency above is only safe if create-and-seed are indivisible: a crash in between would
     * leave an account that can never be seeded, since every later `create` answers `false` and skips
     * it. The account would silently hold nothing forever.
     *
     * Seeds **every** currency, including those starting at zero (DATA_MODEL.md §6) — the row is what
     * makes a later `/baltop` list a player who has not earned anything yet. A currency added to
     * config *after* this runs gets no row, which is correct and harmless: `adjust` seeds one on
     * first use and `GetBalance` already reads a missing row as zero.
     *
     * Writes **no ledger rows**. A starting balance is the account's initial state, not a change to
     * it; a `DEPOSIT` row attributed to nobody would misreport the economy's inflow to anyone
     * auditing it.
     *
     * @return `true` if this call created the account, `false` if it already existed. A `false` is
     *   also M7's cue that a returning player's display name may have changed — `create` will not
     *   update it, so the join listener calls [RenameAccount].
     */
    suspend operator fun invoke(
        id: AccountId,
        name: String,
        type: AccountType = AccountType.PLAYER,
    ): Outcome<Boolean> = guard.guarding({ "creating account ${id.value} ($name)" }) {
        unitOfWork.transaction { ctx ->
            val created = ctx.accounts.create(Account(id, name, type, clock.instant()))
            if (created) {
                val round = rounding()
                currencies.all().forEach { currency ->
                    ctx.balance.set(id, currency, round.round(currency.startingBalance, currency))
                }
            }
            Outcome.Success(created)
        }
    }
}
