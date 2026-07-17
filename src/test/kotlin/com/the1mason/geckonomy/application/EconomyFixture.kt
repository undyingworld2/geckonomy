package com.the1mason.geckonomy.application

import com.the1mason.geckonomy.application.service.EconomyService
import com.the1mason.geckonomy.application.usecase.AccountExists
import com.the1mason.geckonomy.application.usecase.Amounts
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
import com.the1mason.geckonomy.application.usecase.ListTopBalances
import com.the1mason.geckonomy.application.usecase.RenameAccount
import com.the1mason.geckonomy.application.usecase.SetBalance
import com.the1mason.geckonomy.application.usecase.StorageGuard
import com.the1mason.geckonomy.application.usecase.TransactionFactory
import com.the1mason.geckonomy.application.usecase.Transfer
import com.the1mason.geckonomy.application.usecase.Withdraw
import com.the1mason.geckonomy.domain.TestCurrencies
import com.the1mason.geckonomy.domain.model.Account
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.AccountType
import com.the1mason.geckonomy.domain.policy.CurrencyValidation
import com.the1mason.geckonomy.domain.policy.OverdraftPolicy
import com.the1mason.geckonomy.domain.policy.RoundingPolicy
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import com.the1mason.geckonomy.infrastructure.config.ConfigCurrencyRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Everything a use-case test needs, wired the way the composition root wires it.
 *
 * Time and ids are fixed, so a test asserts a whole expected ledger row rather than picking around
 * the two fields it could not otherwise predict (CODING_STANDARDS.md §6).
 *
 * @param allowOverdraft compiled into one [OverdraftPolicy] shared by the balance fake and the use
 *   cases — as in production, where the same instance backs the repository's SQL guard and
 *   `SetBalance`'s check, so the two cannot disagree.
 * @param currencies overridable for the one test that needs the registry itself to fail. Unlike the
 *   repositories, it is not behind [StorageGuard] — it is in-memory and cannot fail in production —
 *   so a caller that touches it is holding the exception, and that is worth being able to prove.
 */
internal class EconomyFixture(
    allowOverdraft: Boolean = false,
    val currencies: CurrencyRegistry = ConfigCurrencyRegistry(listOf(TestCurrencies.COINS, TestCurrencies.GEMS)),
) {

    val clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

    /** Sequential, so an expected row's id is `txId(1)` rather than a wildcard. */
    private var nextId = 0
    private val ids = { txId(++nextId) }

    val overdraft = OverdraftPolicy(allowOverdraft)
    val rounding = { RoundingPolicy() }

    val accounts = InMemoryAccountRepository()
    val balances = InMemoryBalanceRepository(accounts, overdraft)
    val log = RecordingTransactionLog()
    val unitOfWork = InMemoryUnitOfWork(accounts, balances, log)

    val amounts = Amounts(CurrencyValidation(currencies), rounding)
    val transactions = TransactionFactory(clock, ids)

    /** Silent: these tests exercise failure paths on purpose, and a passing build should stay quiet. */
    val guard = StorageGuard(Logger.getAnonymousLogger().apply { level = Level.OFF })

    /** `keep-transaction-history`, flippable mid-test to prove the setting is read per call. */
    var keepHistory = true

    /** Exposed as well as injected, the way the composition root exposes it: renderers need the same one. */
    val format = FormatMoney { Locale.US }

    /**
     * A real [EconomyService] over the fakes, assembled the way `Geckonomy.onEnable` assembles it.
     *
     * Built lazily so a test that only wants one use case does not pay to construct sixteen.
     */
    val service: EconomyService by lazy {
        val getBalance = GetBalance(accounts, balances, amounts, guard)
        EconomyService(
            createAccount = CreateAccount(unitOfWork, currencies, rounding, clock, guard),
            accountExists = AccountExists(accounts, guard),
            findAccountName = FindAccountName(accounts, guard),
            listAccountNames = ListAccountNames(accounts, guard),
            getBalance = getBalance,
            has = Has(getBalance, amounts),
            canDeposit = CanDeposit(accounts, amounts, guard),
            canWithdraw = CanWithdraw(getBalance, amounts, overdraft),
            listTopBalances = ListTopBalances(accounts, balances, amounts, guard),
            deposit = Deposit(unitOfWork, amounts, transactions, guard),
            withdraw = Withdraw(unitOfWork, amounts, transactions, guard),
            setBalance = SetBalance(unitOfWork, amounts, overdraft, transactions, guard),
            transfer = Transfer(unitOfWork, amounts, transactions, guard),
            renameAccount = RenameAccount(accounts, guard),
            deleteAccount = DeleteAccount(unitOfWork, { keepHistory }, guard),
            listCurrencies = ListCurrencies(currencies),
            formatMoney = format,
            currencies = currencies,
        )
    }

    /** An existing account, since most operations need one before they mean anything. */
    suspend fun givenAccount(id: AccountId, name: String = "Player"): AccountId {
        accounts.create(Account(id, name, AccountType.PLAYER, NOW))
        return id
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")

        /** The same cast as `RepositoryContract`, so a reader moving between the two suites is not relearning names. */
        val ALICE = AccountId(UUID.fromString("00000000-0000-0000-0000-00000000a11c"))
        val BOB = AccountId(UUID.fromString("00000000-0000-0000-0000-0000000000b0"))
        val CAROL = AccountId(UUID.fromString("00000000-0000-0000-0000-0000000ca201"))

        /** The nth id this fixture hands to [TransactionFactory]. */
        fun txId(n: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(n))
    }
}
