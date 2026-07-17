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
import com.the1mason.geckonomy.infrastructure.bukkit.listener.PlayerConnectionListener
import com.the1mason.geckonomy.infrastructure.i18n.LanguageRepository
import com.the1mason.geckonomy.infrastructure.i18n.MessageService
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
import com.the1mason.geckonomy.infrastructure.vault.OnlineBalanceMirror
import com.the1mason.geckonomy.infrastructure.vault.VaultRegistration
import com.the1mason.geckonomy.infrastructure.vault.VaultSyncPath
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.bukkit.plugin.java.JavaPlugin
import java.time.Clock
import java.util.Locale
import java.util.logging.Logger
import kotlin.io.path.exists

/** Composition root — the only class that may know every layer (ARCHITECTURE.md §1). */
class Geckonomy : JavaPlugin() {

    /**
     * Cancelled in [onDisable] so nothing outlives the plugin classloader (CODING_STANDARDS.md §3).
     * The [SupervisorJob] keeps one failed operation from tearing down unrelated ones.
     *
     * **Not** the `IoDispatcher`: that is sized for the connection pool — a single thread on SQLite —
     * and command logic does more than query, so running it there would serialize the whole plugin
     * behind one thread to no purpose. Database work reaches the IO threads because the repositories
     * put it there themselves, not because their caller was dispatched correctly.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("geckonomy"))

    private var config: ConfigService? = null

    private var storage: Storage? = null

    private var economy: EconomyService? = null

    /**
     * Unlike [economy], this needs no storage and cannot fail: a language file that is missing or
     * broken degrades to the copy bundled in the jar (LOCALIZATION.md §1), because refusing to start
     * over a text file would be a worse trade than saying it in English.
     */
    private var messages: MessageService? = null

    /** Online players' balances, so Vault's synchronous API never queries the database (ARCHITECTURE.md §4). */
    private val mirror = OnlineBalanceMirror()

    /**
     * The two registered Vault services, or null when VaultUnlocked is not installed.
     *
     * Deliberately [AutoCloseable] rather than `VaultRegistration`: naming that type here would load
     * it, and it names Vault classes that may not exist. See [registerVault].
     */
    private var vault: AutoCloseable? = null

    /** Wiring order is ARCHITECTURE.md §7; each step needs the one above it. */
    override fun onEnable() {
        val config = loadConfig() ?: return
        this.config = config
        val storage = openStorage(config) ?: return
        this.storage = storage
        val economy = Economy(config, storage, Clock.systemUTC(), logger)
        this.economy = economy.service
        val messages = loadMessages(config)
        this.messages = messages

        val sync = VaultSyncPath(economy.service, mirror, scope, config.current.storage.type, logger)
        server.pluginManager.registerEvents(
            PlayerConnectionListener(economy.service, sync, mirror, logger),
            this,
        )
        // The check stays out here, on purpose. VaultUnlocked is a soft dependency, so its classes may
        // not exist at runtime, and the JVM resolves a class the moment a method that names one runs.
        // registerVault names several; nothing calls it unless Vault is actually present.
        vault = if (vaultUnlockedInstalled()) {
            registerVault(economy, messages, sync, config.currencies)
        } else {
            null // Not fatal: accounts, balances and commands all work. Only third-party integration is lost.
        }
        logger.info("Geckonomy enabled.")
    }

    /**
     * Whether VaultUnlocked — not the original Vault — is on the server, warning with the reason if not.
     *
     * The name cannot answer this alone. VaultUnlocked ships as `name: Vault` because it is a drop-in
     * replacement, and reusing the name is what keeps every plugin that softdepends on `Vault` working;
     * the original is called `Vault` too. Only the original lacks the `vault2` package, so the class is
     * the question actually worth asking. Asking it by string rather than by class literal matters: a
     * literal would resolve the class, which is the very thing in doubt.
     */
    private fun vaultUnlockedInstalled(): Boolean {
        if (server.pluginManager.getPlugin(VAULT) == null) {
            logger.warning(
                "VaultUnlocked is not installed - Geckonomy's economy will not be visible to other " +
                    "plugins. Install VaultUnlocked to let shops and similar plugins use it.",
            )
            return false
        }
        return try {
            Class.forName(V2_ECONOMY, false, javaClass.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            logger.warning(
                "The installed Vault is the original, which has no v2 economy API - Geckonomy's " +
                    "economy will not be visible to other plugins. Replace it with VaultUnlocked.",
            )
            false
        }
    }

    private fun registerVault(
        economy: Economy,
        messages: MessageService,
        sync: VaultSyncPath,
        currencies: CurrencyRegistry,
    ): AutoCloseable = VaultRegistration(
        plugin = this,
        economy = economy.service,
        currencies = currencies,
        sync = sync,
        messages = messages,
        format = economy.format,
        rounding = economy.rounding,
        scope = scope,
        logger = logger,
    ).apply { register() }
        .also { logger.info("Registered with VaultUnlocked (v2 and legacy v1 economy services).") }

    /**
     * `lang/en.yml` is written once and never overwritten, so an owner's edits survive an upgrade —
     * which is why `LanguageRepository` keeps the jar's copy as a last fallback: their file is frozen
     * at the version that created it, and a later Geckonomy will have messages it has never heard of.
     *
     * The existence check is not redundant with `saveResource`'s own `replace = false`: that overload
     * logs a warning when the file is already there, so calling it unconditionally would tell the
     * owner off for the normal case on every start but the first.
     */
    private fun loadMessages(config: ConfigService): MessageService {
        val directory = dataFolder.toPath().resolve("lang")
        if (!directory.resolve("en.yml").exists()) saveResource("lang/en.yml", false)
        val languages = LanguageRepository(directory, logger)
        // Named, not a trailing lambda: the last parameter is the renderer, and a trailing lambda
        // would bind there instead.
        return MessageService(languages, language = { config.current.settings.language })
            .apply { reload() }
    }

    override fun onDisable() {
        // Unwinds in reverse: unregister services, flush, close the pool and dispatcher. The economy
        // goes before the storage it reads through, so nothing can be handed a closed pool.
        //
        // Vault first of all: while it is registered, another plugin can still be handed our provider
        // and call it, and everything below this line is what that call would land on.
        vault?.close()
        vault = null
        scope.cancel("Geckonomy is disabling")
        economy = null
        messages = null
        storage?.close()
        storage = null
        config = null
        logger.info("Geckonomy disabled.")
    }

    /**
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
     * The same refusal as [loadConfig], for the same reason: a plugin that enabled without storage
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

    private companion object {
        /** VaultUnlocked's *plugin* name, which is not "VaultUnlocked". See [vaultUnlockedInstalled]. */
        const val VAULT = "Vault"

        /** The v2 API the original Vault does not have; named by string, never as a class literal. */
        const val V2_ECONOMY = "net.milkbowl.vault2.economy.Economy"
    }

    /**
     * The policies here are captured at wiring rather than read per call — which is what makes
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
         * Shared with `SetBalance` rather than kept private, so one policy answers for
         * `allow-overdraft` everywhere. A second instance built from the same config would work until
         * someone changed how it is read, at which point the SQL guard and the admin path would
         * disagree about whether a balance may go negative. One object cannot.
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
     * A class rather than a method so the collaborators every use case shares — the guard, the amount
     * rules, the ledger-row factory — are built once and named, instead of being threaded through a
     * sixteen-argument constructor call by hand.
     *
     * Nothing here needs closing: the use cases hold no resources of their own, only [Storage]'s
     * ports, which [Storage] closes.
     */
    private class Economy(service: ConfigService, storage: Storage, clock: Clock, logger: Logger) {

        /**
         * Read per call, not captured. `settings.rounding-mode` and `settings.keep-transaction-history`
         * are reloadable — unlike `allow-overdraft` and `server-id`, `ConfigService.restartWarnings`
         * deliberately says nothing about them, which is a promise that `/geckonomy reload` changes
         * them. Capturing either here would quietly break that promise: the reload would report
         * success and change nothing.
         */
        val rounding = { RoundingPolicy(service.current.settings.roundingMode) }
        private val keepHistory = { service.current.settings.keepTransactionHistory }

        /**
         * `settings.language` names a file rather than a locale, but deriving one from it is what
         * keeps a German server's text and its numbers agreeing — `1.000,00 Coins`, not `1,000.00`.
         * Read per call for the same reason as [rounding].
         */
        private val locale = { Locale.forLanguageTag(service.current.settings.language) }

        private val currencies = service.currencies
        private val guard = StorageGuard(logger)
        private val amounts = Amounts(CurrencyValidation(currencies), rounding)
        private val transactions = TransactionFactory(clock)

        /** Shared by [Has] and [CanWithdraw], which are both "read the balance, then judge it". */
        private val getBalance = GetBalance(storage.accounts, storage.balances, amounts, guard)

        /** Exposed as well as injected: Vault's `ResponseMapper` renders `<formatted>` with the same one. */
        val format = FormatMoney(locale)

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
            formatMoney = format,
            currencies = currencies,
        )
    }
}
