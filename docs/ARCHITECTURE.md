# Geckonomy — Architecture

Status: v1 design. Companion to `SPEC.md`, `DOMAIN_MODEL.md`, `DATA_MODEL.md`.

## 1. Style

Clean / hexagonal architecture. Three concentric layers with the **dependency rule pointing inward**:

```
infrastructure  ──▶  application  ──▶  domain
   (adapters)          (use cases)      (model + ports)
```

- **domain** — pure Kotlin. No Bukkit, JDBC, Vault, coroutines-on-IO, or config formats. Defines the
  model and the **ports** (interfaces) it needs.
- **application** — orchestrates domain objects through ports to fulfill use cases. Owns the async
  boundary (`suspend` functions). Depends only on domain.
- **infrastructure** — implements ports and connects to the outside world: persistence, config, i18n,
  Vault, Bukkit commands/listeners. Depends on application + domain.
- **composition root** (`Geckonomy.kt`) — the only place that knows all layers; wires concrete adapters
  into use cases (manual constructor injection).

**Rule:** a class may only reference types in its own layer or an inner layer. Inner layers never import
outer layers. Ports live in `domain.port`; their implementations live in `infrastructure`.

## 2. Package layout

```
com.the1mason.geckonomy
├── domain
│   ├── model      AccountId, Account, AccountType, Currency, CurrencyCode, Money, Balance,
│   │              Transaction, TransactionType
│   ├── policy     RoundingPolicy, OverdraftPolicy, CurrencyValidation
│   └── port       AccountRepository, BalanceRepository, TransactionLog, CurrencyRegistry, UnitOfWork
├── application
│   ├── service    EconomyService (facade of suspend fns)
│   ├── usecase    CreateAccount, GetBalance, Has, Deposit, Withdraw, SetBalance, Transfer,
│   │              RenameAccount, DeleteAccount, ListCurrencies, FormatMoney
│   └── result     OperationResult, TransferResult, EconomyError (sealed)
├── infrastructure
│   ├── persistence  DataSourceFactory, SqlDialect, SqliteDialect, MariaDbDialect,
│   │                MigrationRunner, SqlAccountRepository, SqlBalanceRepository,
│   │                SqlTransactionLog, SqlUnitOfWork, IoDispatcher
│   ├── config       GeckonomyConfig, ConfigLoader, StorageConfig, CurrencyConfig, SettingsConfig
│   ├── i18n         MessageService, MiniMessageRenderer, LanguageRepository, MessageKey
│   ├── vault        VaultUnlockedEconomyProvider, GeckonomyAsyncEconomy, ResponseMapper,
│   │                OnlineBalanceMirror
│   └── bukkit       command/*, listener/PlayerConnectionListener, BukkitMainThread
└── Geckonomy.kt     composition root
```

## 3. Ports (domain interfaces)

Signatures are Kotlin `suspend` at the application boundary; domain ports are defined suspend so
implementations can do async IO.

```kotlin
interface AccountRepository {
    suspend fun create(account: Account): Boolean          // idempotent
    suspend fun exists(id: AccountId): Boolean
    suspend fun findName(id: AccountId): String?
    suspend fun nameMap(): Map<AccountId, String>
    suspend fun rename(id: AccountId, name: String): Boolean
    suspend fun delete(id: AccountId): Boolean
}

interface BalanceRepository {
    suspend fun get(id: AccountId, currency: CurrencyCode): BigDecimal?   // null = no row
    suspend fun set(id: AccountId, currency: CurrencyCode, amount: BigDecimal)
    suspend fun adjust(id: AccountId, currency: CurrencyCode, delta: BigDecimal): BigDecimal // atomic, returns new
    suspend fun top(currency: CurrencyCode, limit: Int): List<Pair<AccountId, BigDecimal>>
}

interface TransactionLog { suspend fun append(tx: Transaction) }

interface CurrencyRegistry {           // in-memory, loaded from config
    fun all(): Collection<Currency>
    fun default(): Currency
    fun byCode(code: CurrencyCode): Currency?
}

interface UnitOfWork {                  // transactional boundary for multi-step ops (transfer)
    suspend fun <T> transaction(block: suspend (TxContext) -> T): T
}
```

`TxContext` exposes repository operations bound to a single DB transaction, so `Transfer` debits,
credits, and writes two ledger rows atomically.

## 4. Threading model

- A single `IoDispatcher` (Kotlin `CoroutineDispatcher` backed by a bounded, named thread pool) carries
  all DB work. Nothing in `application` or `infrastructure.persistence` runs on the Bukkit main thread.
- Commands/listeners launch a coroutine on a plugin `CoroutineScope`, await the use case, then hop back
  to the main thread (`BukkitMainThread`) to send messages / interact with the Bukkit API.
- **Transfers** run inside `UnitOfWork.transaction { }` → one JDBC transaction.

### The synchronous Vault path
The Vault `Economy` interface is synchronous (`BigDecimal getBalance(...)` returns immediately) and is
called by third parties on the main thread. Reconciliation:

- `OnlineBalanceMirror` holds `ConcurrentHashMap<AccountId, ConcurrentHashMap<CurrencyCode, BigDecimal>>`.
- `PlayerConnectionListener` hydrates a player's balances **async on join** and evicts on quit.
- Synchronous `getBalance`/`has` read the mirror (no IO).
- Synchronous `withdraw`/`deposit`/`set` update the mirror immediately (so subsequent reads are
  consistent) **and** dispatch the authoritative DB write asynchronously through the use case.
- For **offline** accounts not in the mirror (rare via the sync API), fall back to a bounded blocking
  DB read with a timeout and a warning log.
- `supportsAsync()=true`; integrators that call `async()` get the fully-async `GeckonomyAsyncEconomy`
  which bypasses the mirror and awaits the DB directly.

> This mirror is the single concession to "DB-only + never block." It is a read-through cache for
> online players, not a write-behind buffer: the DB write is dispatched immediately, not batched.

## 5. Key flows (sequences)

### Deposit (our command / async API)
```
Command → EconomyService.deposit(id, money)
  → Deposit use case
      → CurrencyRegistry.byCode (validate)
      → RoundingPolicy.round(amount)
      → BalanceRepository.adjust(id, code, +amount)   [IoDispatcher]
      → TransactionLog.append(DEPOSIT)
  → OperationResult(success, newBalance)
Command → MessageService → main thread → player
```

### Transfer (atomic)
```
Transfer use case → UnitOfWork.transaction {
    ctx.balance.adjust(from, code, -amount)   // fails if insufficient & no overdraft
    ctx.balance.adjust(to,   code, +amount)
    ctx.log.append(TRANSFER_OUT); ctx.log.append(TRANSFER_IN)
}   // commit or rollback
→ TransferResult
```

### Vault getBalance (third-party, main thread)
```
Plugin → VaultUnlockedEconomyProvider.getBalance(plugin, uuid, world, currency)
  → OnlineBalanceMirror.get(uuid, code)          // online → immediate
  → (offline) bounded blocking BalanceRepository.get(...)  + warn
→ BigDecimal
```

## 6. Error handling

`application.result.EconomyError` is a **sealed** hierarchy: `UnknownCurrency`, `AccountNotFound`,
`InsufficientFunds`, `InvalidAmount`, `StorageFailure`. Use cases return `OperationResult`/`TransferResult`
carrying either success data or an `EconomyError`. The Vault adapter maps these to
`EconomyResponse.ResponseType` (`SUCCESS`/`FAILURE`/`NOT_IMPLEMENTED`) + `errorMessage`. Exceptions never
propagate into Bukkit callers.

## 7. Dependency injection

No framework. `Geckonomy.onEnable()` is the composition root:
1. Load config → build `CurrencyRegistry`, `StorageConfig`.
2. Build `DataSourceFactory` → `SqlDialect` → repositories, `UnitOfWork`, `MigrationRunner` (run
   migrations).
3. Build `EconomyService` from use cases + ports.
4. Build `MessageService` from language files.
5. Build `VaultUnlockedEconomyProvider` + mirror; register with `ServicesManager`.
6. Register commands + listeners.
`onDisable()` unregisters, flushes, and closes the pool + dispatcher.

## 8. Testing strategy

- **domain** — plain JUnit 5; no server. Money math, policies, invariants.
- **application** — use cases against in-memory fake ports; verify orchestration + error mapping.
- **infrastructure.persistence** — real repositories on in-memory SQLite; MariaDB via Testcontainers;
  same test suite parametrized over both dialects.
- **vault** — adapter tested with fake `EconomyService`; response mapping + mirror behavior.
- **live smoke** — a Vault-aware plugin transacts on a running Paper server (manual/scripted).
