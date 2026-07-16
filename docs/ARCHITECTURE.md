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
│   │              RenameAccount, DeleteAccount, ListCurrencies, FormatMoney, AccountExists,
│   │              FindAccountName, ListAccountNames, CanDeposit, CanWithdraw;
│   │              StorageGuard, Amounts, TransactionFactory (internal, shared)
│   ├── result     Outcome (sealed), OperationResult, TransferResult, Transferred,
│   │              EconomyError (sealed)
│   └── Attribution.kt
├── infrastructure
│   ├── persistence  DataSourceFactory, SqlDialect, SqliteDialect, MariaDbDialect,
│   │                MigrationRunner, SqlAccountRepository, SqlBalanceRepository,
│   │                SqlTransactionLog, SqlUnitOfWork, IoDispatcher
│   ├── config       GeckonomyConfig, ConfigLoader, StorageConfig, CurrencyConfig, SettingsConfig
│   ├── i18n         MessageService, MiniMessageRenderer, LanguageRepository, MessageKey
│   ├── vault        VaultUnlockedEconomyProvider (v2), LegacyVaultEconomyProvider (v1),
│   │                GeckonomyAsyncEconomy, ResponseMapper, LegacyResponseMapper,
│   │                OnlineBalanceMirror, PlayerResolver
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
    // Takes the full Currency (not just the code) so the impl can resolve the scope key
    // (@global vs server-id) from currency.scope + the injected server-id.
    suspend fun get(id: AccountId, currency: Currency): BigDecimal?   // null = no row
    suspend fun set(id: AccountId, currency: Currency, amount: BigDecimal)   // unguarded; SetBalance checks
    // Atomic. Returns the new balance, or null when the overdraft guard refused it — a typed refusal,
    // not an exception, because insufficient funds is routine (CODING_STANDARDS §4). Seeds a missing
    // row at zero. See DATA_MODEL §4 for how the guard stays atomic.
    suspend fun adjust(id: AccountId, currency: Currency, delta: BigDecimal): BigDecimal?
    suspend fun top(currency: Currency, limit: Int): List<Pair<AccountId, BigDecimal>>
}

interface TransactionLog {
    suspend fun append(tx: Transaction)
    // Append-only has exactly one exception, shaped so it cannot be mistaken for a general delete:
    // an account's whole history goes, and only as part of deleting the account, when
    // settings.keep-transaction-history is off. There is no way to express "forget this one row".
    suspend fun purge(id: AccountId): Int
}

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

**Scope resolution.** A currency is `NETWORK` or `SERVER` scoped. The persistence layer holds a
`ScopeResolver(serverId)` (server-id from config) that maps a `Currency` → the `scope_key` stored in
`gk_balance`/`gk_transaction` (`@global` for network, the server-id for per-server). This lives entirely
in `infrastructure` — domain/application never see a server id. See `DATA_MODEL.md §7`.

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

**Scope & the mirror (important).** The mirror is only trustworthy when this server is the sole writer
of a balance:
- **Per-server (`SERVER`) currencies** — mirrored normally (only this server writes them).
- **Network (`NETWORK`) currencies** — another server sharing the DB can change the balance, so the
  mirror would go stale. Until live propagation ships (future: Redis pub/sub invalidation), the
  synchronous Vault path for network currencies **reads through to the DB** (bounded, off the async
  path where possible) instead of trusting the mirror; writes still persist to the DB authoritatively.
  Our own command/listener paths are already fully async against the DB, so they are always correct.
  When Redis sync lands, network currencies can be mirrored too, invalidated on cross-server change.

## 5. Key flows (sequences)

### Deposit (our command / async API)
```
Command → EconomyService.deposit(id, amount, code)
  → Deposit use case
      → CurrencyRegistry.byCode (validate)
      → RoundingPolicy.round(amount)
      → UnitOfWork.transaction {
            ctx.accounts.exists(id)                    // → AccountNotFound, not an FK error
            ctx.balance.adjust(id, currency, +amount)  [IoDispatcher]
            ctx.log.append(DEPOSIT)
        }
  → OperationResult(success, newBalance)
Command → MessageService → main thread → player
```
**Why the transaction**, when a deposit touches one account: the balance change and its ledger row
have to commit together, or a failed append leaves money moved with nothing recording it and FR-B7 is
quietly false. It costs nothing — `SqlBalanceRepository` defers to an ambient transaction rather than
opening its own — so `Withdraw` and `SetBalance` are wrapped the same way. `SetBalance` needs it for a
second reason: it reads the previous balance to compute the ledger row's `delta`, and that read must
be inside the write's transaction to be exact.

Unlike `Transfer`, these three return a typed failure *normally* and let the transaction commit —
nothing has been written that anyone would want undone.

### Transfer (atomic)
```
Transfer use case → UnitOfWork.transaction {
    ctx.accounts.exists(from) / exists(to)    // throw Abort(AccountNotFound)
    ctx.balance.adjust(from, currency, -amount)   // null if insufficient → throw Abort(InsufficientFunds)
    ctx.balance.adjust(to,   currency, +amount)
    ctx.log.append(TRANSFER_OUT); ctx.log.append(TRANSFER_IN)
}   // commit or rollback
→ TransferResult
```
**Aborting is a `throw`, never a `return`.** `SqlUnitOfWork` commits whatever the block returns and
rolls back only on a throwable, so `return@transaction Outcome.Failure(...)` would commit the debit
and report failure — destroying money in the one operation the transaction exists to protect. The use
case throws a private, stackless `Abort(EconomyError)` and catches it immediately outside the block,
turning it back into a typed failure. Insufficient funds is not exceptional; *aborting a transaction*
is the only thing a throw can express.

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

The mapping happens in exactly one place: `application.usecase.StorageGuard`, which every use case
wraps its port calls in. `SQLException`, `MoneyOutOfRange`, and `LedgerFailure` become
`StorageFailure` (logged at WARNING with context); a `DomainException` becomes the same variant but is
logged at SEVERE, because it means a bug rather than a sick database. `CancellationException` is
caught **first and rethrown** — it is an `IllegalStateException`, so `catch (Exception)` would
otherwise swallow it, and `SqlUnitOfWork` rolls back a half-done transfer precisely because
cancellation reaches it. There is no `Internal` error variant: a bug and a broken database read the
same to a player, and the log level is what distinguishes them for us.

## 7. Dependency injection

No framework. `Geckonomy.onEnable()` is the composition root:
1. Load config → build `CurrencyRegistry`, `StorageConfig`.
2. Build `DataSourceFactory` → `SqlDialect` → repositories, `UnitOfWork`, `MigrationRunner` (run
   migrations).
3. Build `EconomyService` from use cases + ports. Settings that a reload is allowed to change
   (`rounding-mode`, `keep-transaction-history`) are injected as **suppliers** reading
   `ConfigService.current`, not captured values — `restartWarnings` stays silent about them, which is
   a promise that `/geckonomy reload` applies them. `allow-overdraft` is the opposite: one
   `OverdraftPolicy` instance is shared between the balance repository's compiled SQL guard and
   `SetBalance`'s check, so the two can never disagree, and changing it needs a restart.
4. Build `MessageService` from language files.
5. Build `VaultUnlockedEconomyProvider` (v2) **and** `LegacyVaultEconomyProvider` (v1) + mirror; register
   both with `ServicesManager`.
6. Register commands + listeners.
`onDisable()` unregisters, flushes, and closes the pool + dispatcher.

## 8. Testing strategy

- **domain** — plain JUnit 5; no server. Money math, policies, invariants.
- **application** — use cases against in-memory fake ports; verify orchestration + error mapping.
- **infrastructure.persistence** — real repositories on in-memory SQLite; MariaDB via Testcontainers;
  same test suite parametrized over both dialects.
- **vault** — adapter tested with fake `EconomyService`; response mapping + mirror behavior.
- **live smoke** — a Vault-aware plugin transacts on a running Paper server (manual/scripted).
