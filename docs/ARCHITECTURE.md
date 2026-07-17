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
│   ├── Attribution.kt
│   └── Throttle.kt   "at most one warning per interval", for the logs on hot paths
├── infrastructure
│   ├── persistence  DataSourceFactory, SqlDialect, SqliteDialect, MariaDbDialect,
│   │                MigrationRunner, SqlAccountRepository, SqlBalanceRepository,
│   │                SqlTransactionLog, SqlUnitOfWork, IoDispatcher
│   ├── config       GeckonomyConfig, ConfigLoader, StorageConfig, CurrencyConfig, SettingsConfig
│   ├── i18n         MessageService, MiniMessageRenderer, Placeholders, LanguageRepository,
│   │                MessageKey, ErrorMessages
│   ├── vault        VaultUnlockedEconomyProvider (v2), LegacyVaultEconomyProvider (v1),
│   │                GeckonomyAsyncEconomy, ResponseMapper, LegacyResponseMapper,
│   │                OnlineBalanceMirror, PlayerResolver, VaultRegistration, VaultSyncPath
│   └── bukkit       BukkitMainThread, PlayerTargets, CurrencyAccess,
│                    command/{GeckonomyCommands, GeckonomyPermissions, CommandReplies,
│                             Balance,Pay,Baltop,Eco,Geckonomy Command},
│                    listener/PlayerConnectionListener
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
    suspend fun nameMap(): Map<AccountId, String>          // unbounded; Vault's getUUIDNameMap
    // Bounded by its argument, for a caller that already knows which accounts it wants — /baltop
    // labels ten rows, and nameMap() would read every account on the server to do it. Ids with no
    // account are absent from the result rather than mapped to null.
    suspend fun namesOf(ids: Collection<AccountId>): Map<AccountId, String>
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
called by third parties on the main thread. `infrastructure.vault.VaultSyncPath` is the only class that
knows the reconciliation rules; both providers go through it.

- `OnlineBalanceMirror` holds `ConcurrentHashMap<AccountId, ConcurrentHashMap<CurrencyCode, BigDecimal>>`.
- `PlayerConnectionListener` hydrates a player's balances on **`AsyncPlayerPreLoginEvent`** — already off
  the main thread, and done before the player exists to anyone else, so the mirror is warm before any
  plugin can ask. Evicted on quit, and on a login refused after pre-login allowed it.
- **Hydration claims the mirror slot before it reads.** An offline player can be paid at any time — the
  write goes to storage regardless — and a payment landing *during* a login must not be lost. `put` only
  writes to an account already mirrored, so without the claim it would no-op and the balances read before
  the payment would be installed over the top of it; on SQLite, where no refresh fires, the mirror would
  then stay wrong for the player's whole session. `completeHydration` fills only what is still absent,
  and does nothing if the slot was evicted meanwhile (a quit mid-login) or a later hydration replaced it.
- Synchronous `getBalance`/`has` **read the mirror and never read through** (no IO).
- For accounts **not in the mirror** (offline, or not yet hydrated), fall back to a bounded blocking DB
  read with a timeout. Measured at ~99 µs against SQLite versus ~400 ns for a mirror hit, so it is 250×
  the cost and belongs on the rare path only.
- **A failed read answers zero**, and that is not a detail. `getBalance` returns a bare `BigDecimal` and
  `has` a bare `boolean` — neither has a failure channel, and nothing may be thrown at a Vault caller, so
  a sick database has to become *some* number. Zero is chosen because it fails closed: `has` then says
  false and a shop refuses the sale instead of giving goods away. It is always logged first (by
  `StorageGuard` at WARNING, or by the timeout arm). The `Outcome`-returning paths — `canDeposit`,
  `canWithdraw`, every write — propagate `StorageFailure` properly and fabricate nothing.
- `supportsAsync()=true`; integrators that call `async()` get the fully-async `GeckonomyAsyncEconomy`
  which bypasses the mirror and awaits the DB directly.

**Reads: mirror always; refresh only where staleness is possible.** A mirror hit is answered from the
mirror. An async refresh is scheduled *behind* the read (deduplicated per account+currency) only when
`scope == NETWORK && storage == MARIADB` — the one combination where another server can change a balance
underneath us. On **SQLite no refresh ever fires**: a SQLite file cannot be shared between servers, so a
network currency there has exactly one writer and cannot go stale.

> The earlier rule here — network currencies read through to the DB — was rejected at M6 review. It
> would have made the *shipped default config* (`coins: network`, `storage: sqlite`) do a blocking
> main-thread DB read on every third-party call, to protect against a staleness that configuration
> cannot produce. When Redis sync lands, MariaDB's refresh can become pub/sub invalidation.

**Writes await the use case** under a bounded timeout, then put the authoritative returned balance into
the mirror. The optimistic alternative — decide in the adapter, dispatch the DB write async — would have
to re-implement four rules that exist exactly once already: currency resolution, `Amounts.positive`'s
double sign-check (`0.004` is positive going in and `0.00` after rounding to a 2-digit currency), the
reloadable `rounding-mode`, and `OverdraftPolicy.permits`. It would also report SUCCESS for a write the
database later refuses. Reads are the overwhelming majority of Vault traffic and stay free, so NFR-1
holds where it matters.

> The mirror is the single concession to "DB-only + never block." It is a read cache, never a
> write-behind buffer: only a value the database actually returned is ever put into it.

### The placeholder path
PlaceholderAPI's `onRequest` is synchronous and main-thread like Vault's, but the traffic is not
comparable: a scoreboard re-renders **every tick, for every viewer**, and a tab list asks about
**offline** players. Vault is asked once per shop sale. So the bounded blocking read that is a
defensible rare path above would become the common one, and `infrastructure.placeholder` does **no**
database IO at all (SPEC.md FR-P7) — it is stricter than the Vault path, deliberately.

- Online balances: `OnlineBalanceMirror`, the same instance Vault reads.
- Offline balances: `OfflineBalanceCache` — answers from memory, schedules the read *behind* the
  render on the plugin scope, and the next tick shows the truth. TTL'd and swept, so a tab list
  cannot grow it without bound. This is what lets an offline player's balance be shown at all
  without blocking; the alternative was rendering `0` forever for anyone logged out.
- Leaderboard: `BaltopSnapshot`, rebuilt on a timer, read as a volatile field.

Both live in `infrastructure.balance` rather than under either adapter, because both adapters read
the mirror and a package named for one of them would misdescribe it.

**Observability (NFR-8).** Two things say out loud what is otherwise invisible, and both are throttled
because a third party controls how often they happen — a plugin looping over offline players would turn
a useful hint into the reason nobody reads the console:
- `StorageGuard` times every guarded operation and warns past 250ms. It is the one place that sees them
  all, and it already knows what each was doing, so the measurement costs nothing to take.
- `VaultSyncPath` warns when an un-mirrored account sends the main thread to storage — the fallback is
  correct and expected for an offline account, but at ~99µs a loop over them spends a tick every ~500
  lookups, and nothing else connects the stutter to its cause.

The throttle is deliberately not keyed by account or operation: the keys would come from callers, so the
map would grow fastest exactly when the flood it exists to survive arrives. One budget per class of
warning, plus a count of what it swallowed.

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
  → VaultSyncPath.balance(id, currency)
      → OnlineBalanceMirror.get(id, code)        // hit → immediate, no IO
          → if (NETWORK && MARIADB) refresh behind the read, deduped   // never on SQLite
      → (miss) bounded blocking EconomyService.balance(...) + warn
→ BigDecimal
```

### Vault deposit (third-party, main thread)
```
Plugin → VaultUnlockedEconomyProvider.deposit(plugin, uuid, world, currency, amount)
  → VaultSyncPath.deposit(...)
      → runBlocking(timeout) { EconomyService.deposit(...) }   // the use case decides, not the adapter
      → mirror.put(id, code, result.balance)                   // authoritative value only
  → ResponseMapper.response(...)                               // localized; no exception escapes
→ EconomyResponse
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

**The guard protects the use cases; it does not protect their callers.** Every adapter that starts a
coroutine catches for itself, because a throw from the code *above* the economy has no `Outcome` to
land in:
- `GeckonomyCommands.launchGuarded` is the only place a command coroutine may start. It logs at SEVERE
  and answers the player with the same `StorageFailure` shape the guarded paths produce. Without it a
  bug reached the scope's `SupervisorJob`, which cancels one child and tells nobody — the command
  simply never replies, which is the one failure a player cannot report.
- `PlayerConnectionListener.onPreLogin` catches around its `runBlocking`: it runs inside Bukkit's login
  dispatch, and hydration touches the mirror and the registry outside any guard. Warming the mirror is
  an optimisation, so failing to warm it costs latency, never entry.
- `GeckonomyAsyncEconomy.promise` logs an exceptionally-completed future, which `future {}` would
  otherwise swallow entirely.
- The plugin scope carries a `CoroutineExceptionHandler` as a **net, not a plan**: by the time it fires
  there is no sender left to answer, so it can only log.

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
