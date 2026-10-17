package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.application.service.EconomyService
import com.the1mason.geckonomy.application.usecase.AccountExists
import com.the1mason.geckonomy.application.usecase.Amounts
import com.the1mason.geckonomy.application.usecase.CanDeposit
import com.the1mason.geckonomy.application.usecase.CanWithdraw
import com.the1mason.geckonomy.application.usecase.CreateAccount
import com.the1mason.geckonomy.application.usecase.DeleteAccount
import com.the1mason.geckonomy.application.usecase.Deposit
import com.the1mason.geckonomy.application.usecase.FindAccountName
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
import com.the1mason.geckonomy.domain.policy.CurrencyValidation
import com.the1mason.geckonomy.domain.policy.RoundingPolicy
import com.the1mason.geckonomy.domain.port.UnitOfWork
import com.the1mason.geckonomy.infrastructure.config.ConfigCurrencyRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The composition root's assembly over [harness], minus Bukkit. Kept in step with `Geckonomy.Economy`.
 *
 * Shared by every suite that drives the real use cases through a real database, so the SQLite and
 * MariaDB suites cannot drift into testing two different wirings.
 */
fun economyOver(
    harness: PersistenceHarness,
    unitOfWork: UnitOfWork = harness.unitOfWork,
    keepHistory: () -> Boolean = { true },
): EconomyService {
    val currencies = ConfigCurrencyRegistry(listOf(TestCurrencies.COINS, TestCurrencies.GEMS))
    val rounding = { RoundingPolicy() }
    val guard = StorageGuard(Logger.getAnonymousLogger().apply { level = Level.OFF })
    val amounts = Amounts(CurrencyValidation(currencies), rounding)
    val transactions = TransactionFactory(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
    val getBalance = GetBalance(harness.accounts, harness.balances, amounts, guard)
    return EconomyService(
        createAccount = CreateAccount(unitOfWork, currencies, rounding, Clock.systemUTC(), guard),
        accountExists = AccountExists(harness.accounts, guard),
        findAccountName = FindAccountName(harness.accounts, guard),
        listAccountNames = ListAccountNames(harness.accounts, guard),
        getBalance = getBalance,
        has = Has(getBalance, amounts),
        canDeposit = CanDeposit(harness.accounts, amounts, guard),
        canWithdraw = CanWithdraw(getBalance, amounts, harness.overdraft),
        listTopBalances = ListTopBalances(harness.accounts, harness.balances, amounts, guard),
        deposit = Deposit(unitOfWork, amounts, transactions, guard),
        withdraw = Withdraw(unitOfWork, amounts, transactions, guard),
        setBalance = SetBalance(unitOfWork, amounts, harness.overdraft, transactions, guard),
        transfer = Transfer(unitOfWork, amounts, transactions, guard),
        renameAccount = RenameAccount(harness.accounts, guard),
        deleteAccount = DeleteAccount(unitOfWork, keepHistory, guard),
        listCurrencies = ListCurrencies(currencies),
        currencies = currencies,
    )
}
