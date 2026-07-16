package com.the1mason.geckonomy.application.service

import com.the1mason.geckonomy.application.Attribution
import com.the1mason.geckonomy.application.result.OperationResult
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.result.TransferResult
import com.the1mason.geckonomy.application.usecase.AccountExists
import com.the1mason.geckonomy.application.usecase.CanDeposit
import com.the1mason.geckonomy.application.usecase.CanWithdraw
import com.the1mason.geckonomy.application.usecase.CreateAccount
import com.the1mason.geckonomy.application.usecase.DeleteAccount
import com.the1mason.geckonomy.application.usecase.Deposit
import com.the1mason.geckonomy.application.usecase.FindAccountName
import com.the1mason.geckonomy.application.usecase.FormatMoney
import com.the1mason.geckonomy.application.usecase.GetBalance
import com.the1mason.geckonomy.application.usecase.Has
import com.the1mason.geckonomy.application.usecase.ListAccountNames
import com.the1mason.geckonomy.application.usecase.ListCurrencies
import com.the1mason.geckonomy.application.usecase.RenameAccount
import com.the1mason.geckonomy.application.usecase.SetBalance
import com.the1mason.geckonomy.application.usecase.Transfer
import com.the1mason.geckonomy.application.usecase.Withdraw
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.Money
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import java.math.BigDecimal

/**
 * The single entry point into the economy — every command, listener, and Vault provider goes through
 * here (ARCHITECTURE.md §2).
 *
 * Holds **no business logic**: each function is one line delegating to the use case that owns the
 * rule. What it adds is the two conveniences that would otherwise be repeated by every adapter —
 * defaulting the currency to the configured default (SPEC.md FR-B1) and attributing a change to
 * Geckonomy unless a caller says otherwise.
 *
 * That there is a facade at all is what keeps `CurrencyRegistry` and the repositories out of the
 * adapters: an adapter that had to resolve a currency before asking for a balance would end up with
 * its own copy of the unknown-currency rule.
 *
 * @param currencies used only to resolve the default currency for the parameter defaults below.
 */
class EconomyService(
    private val createAccount: CreateAccount,
    private val accountExists: AccountExists,
    private val findAccountName: FindAccountName,
    private val listAccountNames: ListAccountNames,
    private val getBalance: GetBalance,
    private val has: Has,
    private val canDeposit: CanDeposit,
    private val canWithdraw: CanWithdraw,
    private val deposit: Deposit,
    private val withdraw: Withdraw,
    private val setBalance: SetBalance,
    private val transfer: Transfer,
    private val renameAccount: RenameAccount,
    private val deleteAccount: DeleteAccount,
    private val listCurrencies: ListCurrencies,
    private val formatMoney: FormatMoney,
    private val currencies: CurrencyRegistry,
) {

    // ── Accounts ────────────────────────────────────────────────────────

    /** Creates [id], seeding every currency's starting balance. `false` if it already existed. */
    suspend fun createAccount(id: AccountId, name: String): Outcome<Boolean> = createAccount.invoke(id, name)

    /** Whether [id] names an account. */
    suspend fun exists(id: AccountId): Outcome<Boolean> = accountExists.invoke(id)

    /** [id]'s display name, or `null` if there is no such account. */
    suspend fun name(id: AccountId): Outcome<String?> = findAccountName.invoke(id)

    /** Every account's id mapped to its name. Unbounded — see [ListAccountNames]. */
    suspend fun nameMap(): Outcome<Map<AccountId, String>> = listAccountNames.invoke()

    /** Renames [id]. */
    suspend fun rename(id: AccountId, name: String): Outcome<Unit> = renameAccount.invoke(id, name)

    /** Deletes [id], its balances, and — per `keep-transaction-history` — its ledger. */
    suspend fun delete(id: AccountId): Outcome<Unit> = deleteAccount.invoke(id)

    // ── Reads ───────────────────────────────────────────────────────────

    /** What [id] holds in [currency]. */
    suspend fun balance(id: AccountId, currency: CurrencyCode = defaultCode()): OperationResult =
        getBalance.invoke(id, currency)

    /** Whether [id] holds at least [amount]. Advisory — see [Has]. */
    suspend fun has(id: AccountId, amount: BigDecimal, currency: CurrencyCode = defaultCode()): Outcome<Boolean> =
        has.invoke(id, amount, currency)

    /** Whether a deposit would be accepted. Advisory. */
    suspend fun canDeposit(id: AccountId, amount: BigDecimal, currency: CurrencyCode = defaultCode()): Outcome<Boolean> =
        canDeposit.invoke(id, amount, currency)

    /** Whether a withdrawal would be accepted. Advisory — the real check is atomic, inside `adjust`. */
    suspend fun canWithdraw(id: AccountId, amount: BigDecimal, currency: CurrencyCode = defaultCode()): Outcome<Boolean> =
        canWithdraw.invoke(id, amount, currency)

    // ── Writes ──────────────────────────────────────────────────────────

    /** Credits [amount] to [id]. */
    suspend fun deposit(
        id: AccountId,
        amount: BigDecimal,
        currency: CurrencyCode = defaultCode(),
        by: Attribution = Attribution.GECKONOMY,
    ): OperationResult = deposit.invoke(id, amount, currency, by)

    /** Debits [amount] from [id], refusing to overdraw unless `allow-overdraft` is on. */
    suspend fun withdraw(
        id: AccountId,
        amount: BigDecimal,
        currency: CurrencyCode = defaultCode(),
        by: Attribution = Attribution.GECKONOMY,
    ): OperationResult = withdraw.invoke(id, amount, currency, by)

    /** Replaces [id]'s balance with [amount]. */
    suspend fun set(
        id: AccountId,
        amount: BigDecimal,
        currency: CurrencyCode = defaultCode(),
        by: Attribution = Attribution.GECKONOMY,
    ): OperationResult = setBalance.invoke(id, amount, currency, by)

    /** Moves [amount] from [from] to [to], atomically. */
    suspend fun transfer(
        from: AccountId,
        to: AccountId,
        amount: BigDecimal,
        currency: CurrencyCode = defaultCode(),
        by: Attribution = Attribution.GECKONOMY,
    ): TransferResult = transfer.invoke(from, to, amount, currency, by)

    // ── Currencies ──────────────────────────────────────────────────────
    // Not suspend, and not an Outcome: no IO, nothing to fail. Marking them suspend would tell every
    // caller to treat a map lookup like a database round trip.

    /** Every configured currency, default first. */
    fun currencies(): List<Currency> = listCurrencies.invoke()

    /** The currency used when a caller names none. */
    fun defaultCurrency(): Currency = currencies.default()

    /** [money] as the string a player reads. */
    fun format(money: Money): String = formatMoney.invoke(money)

    private fun defaultCode(): CurrencyCode = currencies.default().code
}
