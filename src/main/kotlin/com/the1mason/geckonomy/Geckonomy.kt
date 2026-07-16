package com.the1mason.geckonomy

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
import com.the1mason.geckonomy.application.usecase.RenameAccount
import com.the1mason.geckonomy.application.usecase.SetBalance
import com.the1mason.geckonomy.application.usecase.StorageGuard
import com.the1mason.geckonomy.application.usecase.TransactionFactory
import com.the1mason.geckonomy.application.usecase.Transfer
import com.the1mason.geckonomy.application.usecase.Withdraw
import com.the1mason.geckonomy.domain.policy.CurrencyValidation
import com.the1mason.geckonomy.domain.policy.OverdraftPolicy
import com.the1mason.geckonomy.domain.policy.RoundingPolicy
import com.the1mason.geckonomy.domain.port.AccountRepository
import com.the1mason.geckonomy.domain.port.BalanceRepository
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import com.the1mason.geckonomy.domain.port.TransactionLog
import com.the1mason.geckonomy.domain.port.UnitOfWork
import com.the1mason.geckonomy.infrastructure.config.ConfigService
import com.the1mason.geckonomy.infrastructure.config.GeckonomyConfig
import com.the1mason.geckonomy.infrastructure.config.StartOutcome
import com.the1mason.geckonomy.infrastructure.config.StorageType
import com.the1mason.geckonomy.infrastructure.persistence.ConnectionSource
import com.the1mason.geckonomy.infrastructure.persistence.DataSourceFactory
import com.the1mason.geckonomy.infrastructure.persistence.IoDispatcher
import com.the1mason.geckonomy.infrastructure.persistence.MariaDbDialect
import com.the1mason.geckonomy.infrastructure.persistence.MigrationRunner
import com.the1mason.geckonomy.infrastructure.persistence.ScopeResolver
import com.the1mason.geckonomy.infrastructure.persistence.SqlAccountRepository
import com.the1mason.geckonomy.infrastructure.persistence.SqlBalanceRepository
import com.the1mason.geckonomy.infrastructure.persistence.SqlDialect
import com.the1mason.geckonomy.infrastructure.persistence.SqlTransactionLog
import com.the1mason.geckonomy.infrastructure.persistence.SqlUnitOfWork
import com.the1mason.geckonomy.infrastructure.persistence.SqliteDialect
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.bukkit.plugin.java.JavaPlugin
import java.time.Clock
import java.util.logging.Logger

/**
 * Composition root — the only class that may know every layer (ARCHITECTURE.md §1).
 *
 * At M3 it wires config and persistence; the later milestones fill in the rest in the order
 * [onEnable] documents.
 */
class Geckonomy : JavaPlugin() {

    /**
     * The one scope every coroutine in the plugin runs under, cancelled in [onDisable] so nothing
     * outlives the plugin classloader (CODING_STANDARDS.md §3).
     *
     * A [SupervisorJob] keeps one failed operation from tearing down unrelated ones.
     *
     * **Not** the `IoDispatcher`, despite what this comment predicted at M0. Commands do more than
     * query: they format messages and decide what to say, while the IO dispatcher is sized for the
     * connection pool — a single thread on SQLite. Running command logic there would serialize the
     * whole plugin behind one thread to no purpose. Database work reaches the IO threads because the
     * repositories put it there themselves, not because their caller was dispatched correctly.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("geckonomy"))

    /**
     * Config and the currency registry, held for the milestones that build on them and for M7's
     * `/geckonomy reload`. Null until a valid config is read — and if none ever is, the plugin
     * disables rather than reaching this state.
     */
    private var config: ConfigService? = null

    /** The database side, held for the use cases built on it. Null until [openStorage] succeeds. */
    private var storage: Storage? = null

    /**
     * The economy itself — the one entry point every later milestone calls.
     *
     * Held so M6's Vault providers and M7's commands can take it. Null until storage opens, and if it
     * never does, the plugin disables rather than reaching this state.
     */
    private var economy: EconomyService? = null

    override fun onEnable() {
        // Wiring order — ARCHITECTURE.md §7. Each step arrives with its milestone:
        // 1. M2 — load config; build CurrencyRegistry and StorageConfig.                    [done]
        // 2. M3 — DataSourceFactory -> SqlDialect -> repositories + UnitOfWork; migrations. [done]
        // 3. M4 — EconomyService from the use cases + ports.                                [done]
        // 4. M5 — MessageService from the language files.
        // 5. M6 — VaultUnlockedEconomyProvider (v2) and LegacyVaultEconomyProvider (v1) + the online
        //         balance mirror; register both with the ServicesManager.
        // 6. M7 — register commands and listeners.
        val config = loadConfig() ?: return
        this.config = config
        val storage = openStorage(config) ?: return
        this.storage = storage
        economy = Economy(config, storage, Clock.systemUTC(), logger).service
        logger.info("Geckonomy enabled (M4: economy ready; no commands or Vault providers wired yet).")
    }

    override fun onDisable() {
        // Unwinds in reverse: unregister services, flush, close the pool and dispatcher. The economy
        // goes before the storage it reads through, so nothing can be handed a closed pool.
        scope.cancel("Geckonomy is disabling")
        economy = null
        storage?.close()
        storage = null
        config = null
        logger.info("Geckonomy disabled.")
    }

    /**
     * Reads `config.yml`, or disables the plugin and returns null.
     *
     * Refusing to start beats starting misconfigured (CONFIGURATION.md §3): an economy that comes up
     * with the wrong currencies or points at the wrong database does damage that a clear error at
     * boot does not.
     */
    private fun loadConfig(): ConfigService? {
        saveDefaultConfig()
        return when (val outcome = ConfigService.start(dataFolder.toPath().resolve("config.yml"))) {
            is StartOutcome.Failed -> {
                // ASCII only: the server console writes the log in the platform charset, and a
                // bullet or an em-dash comes out as a replacement character on a Windows console.
                logger.severe("Geckonomy cannot start - config.yml is invalid:")
                outcome.errors.forEach { logger.severe("  - $it") }
                logger.severe("Fix the problems above and restart the server.")
                disable()
                null
            }

            is StartOutcome.Started -> {
                outcome.warnings.forEach { logger.warning(it) }
                val service = outcome.service
                val config = service.current
                logger.info(
                    "Loaded ${config.currencies.size} currencies " +
                        "(default: ${service.currencies.default().code}); storage: ${config.storage.type}.",
                )
                service
            }
        }
    }

    /**
     * Opens the pool, migrates the schema, and builds the repositories — or disables the plugin.
     *
     * The same refusal as a bad config, for the same reason: a plugin that enabled without storage
     * would answer every balance query with an error, and a player would read that as having lost
     * their money. A server that did not start is easier to explain, and to fix.
     */
    private fun openStorage(service: ConfigService): Storage? {
        val config = service.current
        val io = IoDispatcher.forStorage(config.storage)
        return try {
            val dialect = dialectFor(config.storage.type)
            val dataSource = DataSourceFactory().create(config.storage)
            try {
                migrate(dataSource, dialect, io)
                Storage(dataSource, io, dialect, config, service.currencies)
            } catch (e: Exception) {
                // The pool opened but the schema did not: close it here, because the Storage that
                // would have owned it was never built.
                dataSource.close()
                throw e
            }
        } catch (e: Exception) {
            io.close()
            logger.severe("Geckonomy cannot start - storage is unavailable:")
            logger.severe("  - ${e.message ?: e.toString()}")
            logger.severe("Check the storage section of config.yml and that the database is reachable.")
            disable()
            null
        }
    }

    /**
     * Runs pending migrations on the IO threads, blocking until they finish.
     *
     * Blocking is the point: nothing may touch the database until the schema is right, so there is
     * nothing useful for enable to do concurrently. Going through `runBlocking` on the IO dispatcher
     * rather than simply calling it keeps the "JDBC only on the IO threads" rule intact
     * (CODING_STANDARDS.md §3) even for this one startup-time exception.
     */
    private fun migrate(dataSource: HikariDataSource, dialect: SqlDialect, io: IoDispatcher) {
        val applied = runBlocking(io.dispatcher) { MigrationRunner(dataSource, dialect).migrate() }
        if (applied.isEmpty()) logger.info("Database schema is up to date.")
        else logger.info("Applied database migrations: ${applied.joinToString()}.")
    }

    private fun dialectFor(type: StorageType): SqlDialect = when (type) {
        StorageType.SQLITE -> SqliteDialect
        StorageType.MARIADB -> MariaDbDialect
    }

    private fun disable() = server.pluginManager.disablePlugin(this)

    /**
     * Everything M3 builds, kept together so [onDisable] can close it in one move and M4 can take the
     * ports off it.
     *
     * The policies are captured here, at wiring, rather than read per call — which is what makes
     * `settings.allow-overdraft` and `settings.server-id` restart-only, and why `ConfigService` warns
     * when a reload changes either (CONFIGURATION.md §4).
     */
    private class Storage(
        private val dataSource: HikariDataSource,
        private val io: IoDispatcher,
        dialect: SqlDialect,
        config: GeckonomyConfig,
        currencies: CurrencyRegistry,
    ) : AutoCloseable {

        private val scopes = ScopeResolver(config.settings.serverId)
        private val connections = ConnectionSource.Pooled(dataSource)

        /**
         * Shared with M4's `SetBalance` rather than kept private, so one policy answers for
         * `allow-overdraft` everywhere.
         *
         * `BalanceRepository.set` is unguarded and `SetBalance` applies the rule itself; a second
         * instance built from the same config would work until someone changed how it is read, at
         * which point the SQL guard and the admin path would disagree about whether a balance may go
         * negative. One object cannot.
         */
        val overdraft = OverdraftPolicy(config.settings.allowOverdraft)

        val accounts: AccountRepository = SqlAccountRepository(connections, dialect, io.dispatcher)
        val balances: BalanceRepository = SqlBalanceRepository(connections, dialect, scopes, overdraft, io.dispatcher)
        val log: TransactionLog = SqlTransactionLog(connections, dialect, scopes, currencies, io.dispatcher)
        val unitOfWork: UnitOfWork = SqlUnitOfWork(dataSource, dialect, scopes, overdraft, currencies, io.dispatcher)

        /** Pool first, then threads: a thread cannot be left mid-query on a pool that is already gone. */
        override fun close() {
            dataSource.close()
            io.close()
        }
    }

    /**
     * M4's assembly: the use cases over M3's ports, and the facade over them.
     *
     * A class rather than a method so the collaborators every use case shares — the guard, the amount
     * rules, the ledger-row factory — are built once and named, instead of being threaded through a
     * sixteen-argument constructor call by hand.
     *
     * Nothing here needs closing: the use cases hold no resources of their own, only [Storage]'s
     * ports, which [Storage] closes.
     */
    private class Economy(service: ConfigService, storage: Storage, clock: Clock, logger: Logger) {

        /**
         * Read per call, not captured.
         *
         * `settings.rounding-mode` and `settings.keep-transaction-history` are reloadable — unlike
         * `allow-overdraft` and `server-id`, `ConfigService.restartWarnings` deliberately says nothing
         * about them, which is a promise that `/geckonomy reload` changes them. Capturing either here
         * would quietly break that promise: the reload would report success and change nothing.
         */
        private val rounding = { RoundingPolicy(service.current.settings.roundingMode) }
        private val keepHistory = { service.current.settings.keepTransactionHistory }

        private val currencies = service.currencies
        private val guard = StorageGuard(logger)
        private val amounts = Amounts(CurrencyValidation(currencies), rounding)
        private val transactions = TransactionFactory(clock)

        /** Shared by [Has] and [CanWithdraw], which are both "read the balance, then judge it". */
        private val getBalance = GetBalance(storage.accounts, storage.balances, amounts, guard)

        val service: EconomyService = EconomyService(
            createAccount = CreateAccount(storage.unitOfWork, currencies, rounding, clock, guard),
            accountExists = AccountExists(storage.accounts, guard),
            findAccountName = FindAccountName(storage.accounts, guard),
            listAccountNames = ListAccountNames(storage.accounts, guard),
            getBalance = getBalance,
            has = Has(getBalance, amounts),
            canDeposit = CanDeposit(storage.accounts, amounts, guard),
            canWithdraw = CanWithdraw(getBalance, amounts, storage.overdraft),
            deposit = Deposit(storage.unitOfWork, amounts, transactions, guard),
            withdraw = Withdraw(storage.unitOfWork, amounts, transactions, guard),
            setBalance = SetBalance(storage.unitOfWork, amounts, storage.overdraft, transactions, guard),
            transfer = Transfer(storage.unitOfWork, amounts, transactions, guard),
            renameAccount = RenameAccount(storage.accounts, guard),
            deleteAccount = DeleteAccount(storage.unitOfWork, keepHistory, guard),
            listCurrencies = ListCurrencies(currencies),
            formatMoney = FormatMoney(),
            currencies = currencies,
        )
    }
}
