# Geckonomy — VaultUnlocked v2 Integration

Geckonomy implements **two** economy interfaces, both bundled in VaultUnlockedAPI (the `vault.version`
property in `pom.xml`), and registers each as a Bukkit service:
- `net.milkbowl.vault2.economy.Economy` (**v2**, multi-currency) — primary, §1–§7 below.
- `net.milkbowl.vault.economy.Economy` (**legacy v1**, single-currency) — §8, shipped from v1 because
  many plugins still bind to it.

This document is the authoritative method-by-method mapping. Every interface method is either
**implemented** or explicitly **NOT_IMPLEMENTED** with a reason (v1 has no shared accounts / banks).

Reference source (pin a local copy under `.reference/VaultUnlockedAPI/` for the coding agents):
`net/milkbowl/vault2/economy/{Economy,AsyncEconomy,EconomyResponse,MultiEconomyResponse,AccountPermission}.java`
and `net/milkbowl/vault/economy/{Economy,EconomyResponse}.java` (legacy). **No extra dependency needed —
both packages ship in the existing `provided` VaultUnlockedAPI artifact.**

## 1. Registration

**The dependency is named `Vault`, not `VaultUnlocked`.** VaultUnlocked ships as `name: Vault` because
it is a drop-in replacement, and keeping the name is what leaves every plugin that depends on `Vault`
working. The original Vault is called `Vault` too, so the *name* cannot tell them apart — only the
original lacks the `vault2` package. `Geckonomy.vaultUnlockedInstalled()` therefore asks for the v2
`Economy` **class**, by string; a class literal would resolve the very thing in doubt. This cost M6 a
live smoke test to find: the presence check looked for a plugin named `VaultUnlocked`, found nothing,
and registration silently never ran.

`paper-plugin.yml` (there is no `softdepend` in the Paper format — that is legacy `plugin.yml`):

```yaml
dependencies:
  server:
    Vault:
      load: BEFORE          # our onEnable registers with the ServicesManager
      required: false       # Geckonomy runs without it; only third-party integration is lost
      join-classpath: true
```

`VaultRegistration` is the **only** class that names a Vault type at wiring time, so the soft dependency
can be absent without a `NoClassDefFoundError`; `Geckonomy` holds it as an `AutoCloseable`. Both services
register at `ServicePriority.Highest` and are unregistered on close. Both providers are built over one
`VaultSyncPath`, which is what makes them observe one set of mirror rules (§7) rather than two copies.

## 2. Response types (from source)

- `EconomyResponse(BigDecimal amount, BigDecimal balance, ResponseType type, String errorMessage)`;
  `transactionSuccess()` → `type == SUCCESS`. `ResponseType`: `SUCCESS(1)`, `FAILURE(2)`,
  `NOT_IMPLEMENTED(3)`.
- `MultiEconomyResponse(amount, type, errorMessage)` + `addBalance(UUID, BigDecimal)` /
  `balance(UUID): Optional<BigDecimal>`. Used for transfers (per-party resulting balances).
- `AccountPermission` enum: `DEPOSIT, WITHDRAW, BALANCE, TRANSFER_OWNERSHIP, INVITE_MEMBER,
  REMOVE_MEMBER, CHANGE_MEMBER_PERMISSION, OWNER, DELETE`.

`ResponseMapper` builds these from `application.result` types. **`errorMessage` is localized**: each of
the five `EconomyError` variants maps 1:1 onto an existing `error.*` message key, rendered through
`MessageService` and serialized with `PlainTextComponentSerializer` (Vault wants a `String`, and a
console or a third-party plugin's own formatting has nowhere to put a `Component`). The `<prefix>` is
kept, so an operator reading a shop plugin's error still sees which plugin said it.

| EconomyError | ResponseType | errorMessage source |
|---|---|---|
| (success) | SUCCESS | `""` |
| InsufficientFunds | FAILURE | `error.insufficient-funds`, localized, names the account |
| UnknownCurrency | FAILURE | `error.unknown-currency`, localized |
| InvalidAmount | FAILURE | `error.invalid-amount`, localized |
| AccountNotFound | FAILURE | `error.account-not-found`, localized |
| StorageFailure | FAILURE | `error.storage-failure`, localized |
| shared-account method | NOT_IMPLEMENTED | "Shared accounts not supported" |

`LegacyResponseMapper` wraps the same instance for the v1 `double`-based `EconomyResponse`, so both
providers say the same thing in the same language.

`InsufficientFunds` carries the account's **name**, read by the use case on the failure path only, so the
message says "Alice doesn't have $50.00" rather than a UUID — this string reaches players through any
plugin that surfaces `errorMessage`. It falls back to the UUID when no name could be read.
`AccountNotFound` keeps the UUID by necessity: there is no account row, so there is no name to read.

## 3. Capability flags

| Method | Returns |
|---|---|
| `isEnabled()` | plugin enabled state |
| `getName()` | `"Geckonomy"` |
| `hasMultiCurrencySupport()` | **true** |
| `hasSharedAccountSupport()` | **false** (v1) |
| `supportsAsync()` | **true** |
| `async()` | `Optional.of(GeckonomyAsyncEconomy)` |

## 4. Parameter conventions

- `pluginName: String` — recorded as `Transaction.sourcePlugin`; not used for currency namespacing
  (currencies are global). Never keys data.
- `worldName: String` — **accepted and ignored** (never per-world).
- `currency: String` — resolved to a `Currency`; when absent, the **default currency** is used.
  Unknown currency → `FAILURE` (or `false`/empty for boolean/collection returns).
- **Scope has no Vault parameter.** Whether a balance is network- or server-scoped is a property of the
  resolved `Currency` (config), not something callers pass. The persistence layer derives the scope key
  from `currency.scope` + the config `server-id` (see `DATA_MODEL.md §7`). A **network** currency on a
  shared MariaDB is the one case the mirror can go stale, and it is answered from the mirror anyway with
  a refresh scheduled behind the read — see §7 and `ARCHITECTURE.md §4`.
- `UUID accountID` — the `AccountId`.
- All amounts are `BigDecimal`, rounded to the currency's fractional digits before use.

## 5. Method mapping

### Currency / formatting
| Vault method | Mapping |
|---|---|
| `fractionalDigits(plugin)` / `(plugin, currency)` | `CurrencyRegistry` → default / named currency digits (`-1` unsupported→ use default) |
| `format(amount)` / `(plugin, amount)` | `FormatMoney` with default currency |
| `format(amount, currency)` / `(plugin, amount, currency)` | `FormatMoney` with named currency |
| `hasCurrency(currency)` | `CurrencyRegistry.byCode != null` |
| `getDefaultCurrency(plugin)` | default currency code |
| `defaultCurrencyNameSingular/Plural(plugin)` | default currency singular/plural |
| `currencies()` | all currency codes |

### Account lifecycle
| Vault method | Mapping |
|---|---|
| `createAccount(id, name)` and world/player overloads | `CreateAccount` (idempotent). Player/world flags ignored beyond `AccountType.PLAYER`. |
| `hasAccount(id)` / `(id, world)` | `AccountRepository.exists` (world ignored) |
| `getAccountName(id)` | `AccountRepository.findName` → `Optional` |
| `getUUIDNameMap()` | `AccountRepository.nameMap` |
| `renameAccount(id, name)` / `(plugin, id, name)` | `RenameAccount` |
| `deleteAccount(plugin, id)` | `DeleteAccount` |

### Balance & checks
| Vault method | Mapping |
|---|---|
| `getBalance(...)` / `balance(...)` (all world/currency overloads) | `GetBalance`; **mirrored → mirror**, un-mirrored → bounded blocking read (§7) |
| `has(...)` (all overloads) | `Has` use case (mirror/DB) |
| `accountSupportsCurrency(...)` | `true` for known currencies (all accounts support all currencies) |
| `set(...)` (all overloads) | `SetBalance`, awaited; then the returned balance into the mirror; `EconomyResponse` |

### Withdraw / deposit
| Vault method | Mapping |
|---|---|
| `canWithdraw(...)` | pre-flight `Withdraw` check (no mutation) → `EconomyResponse` |
| `withdraw(...)` | `Withdraw`, awaited; then the returned balance into the mirror |
| `canDeposit(...)` | pre-flight `Deposit` check |
| `deposit(...)` | `Deposit`, awaited; then the returned balance into the mirror |

### Transfers
| Vault method | Mapping |
|---|---|
| `transfer(plugin, from, to, [world,] [currency,] amount)` | `Transfer` (atomic via `UnitOfWork`) → `MultiEconomyResponse` with both resulting balances via `addBalance` |

### Shared accounts — v1 NOT_IMPLEMENTED
All of the following return `false` / empty list / `NOT_IMPLEMENTED` and log at debug:
`createSharedAccount`, `accountsOwnedBy`, `accountsWithOwnerOf`, `accountsMemberOf`,
`accountsWithMembershipTo`, `accountsAccessTo`, `accountsWithAccessTo`, `isAccountOwner`, `setOwner`,
`isAccountMember`, `addAccountMember` (both overloads), `removeAccountMember`, `hasAccountPermission`,
`updateAccountPermission`.

> The `gk_account_member` table + `AccountType.SHARED` already exist so these light up later without a
> breaking change.

## 6. AsyncEconomy

`GeckonomyAsyncEconomy` mirrors the sync methods but returns `CompletableFuture`s and talks to the
`EconomyService` (DB) directly — **no mirror**, fully async. Integrators that check `supportsAsync()`
get exact, un-cached values. Futures are produced with `kotlinx.coroutines.future.future { }` on the
plugin scope, so a disable cancels them rather than leaking past the classloader.

## 7. Sync path & the online mirror

The sync `Economy` interface returns values immediately and is called on the main thread.
`VaultSyncPath` is the only class holding these rules; both providers go through it.

- `OnlineBalanceMirror` holds online players' balances, hydrated on `AsyncPlayerPreLoginEvent` (before
  the player is visible to anyone else, so the mirror is warm before any plugin can ask), evicted on quit.
- Sync **reads** answer from the mirror and **never read through**. An async refresh runs behind the read
  only when `scope == NETWORK && storage == MARIADB`; on SQLite no refresh fires, because the file cannot
  be shared, so there is exactly one writer and staleness is impossible.
- Sync **writes await the use case** under a bounded timeout and then store the balance the database
  actually returned. The adapter never decides the outcome itself — currency resolution, amount
  validation, rounding and overdraft live in the use case, and duplicating them here is how the two
  paths drift apart.
- Calls for **un-mirrored** accounts (offline, or not yet hydrated) do a bounded blocking DB read with a
  timeout. Measured ~99 µs on SQLite against ~400 ns for a mirror hit — fine occasionally, but a plugin
  looping over offline players pays a whole tick every ~500 lookups.
- **A failed read answers zero.** `getBalance` returns a bare `BigDecimal` and `has` a bare `boolean`, so
  neither can report a failure, and nothing may be thrown at a Vault caller. Zero fails closed — `has`
  says false, so a shop refuses the sale rather than handing over goods — and it is logged before it is
  returned. Everything carrying an `Outcome` (`canDeposit`, `canWithdraw`, all writes) reports
  `StorageFailure` honestly instead.

See `ARCHITECTURE.md §4` for why the read-through rule this section used to state was dropped.

## 8. Legacy v1 provider (`net.milkbowl.vault.economy.Economy`)

Shipped from v1. Single-currency, `double` amounts, `String`/`OfflinePlayer` identifiers. Package + a
separate legacy `EconomyResponse(double amount, double balance, ResponseType type, String errorMessage)`
(same `SUCCESS/FAILURE/NOT_IMPLEMENTED` enum). `LegacyVaultEconomyProvider` delegates to the same
`EconomyService` and the same `VaultSyncPath` as v2, so both providers observe one set of mirror rules.

**Do not extend `AbstractEconomy`.** It implements the `OfflinePlayer` overloads by delegating to the
`String playerName` ones — the wrong direction, discarding the UUID we want and forcing a name lookup we
already have the answer to. `LegacyVaultEconomyProvider` implements the interface directly.

### Conventions
- **Currency:** always the **default currency** (legacy has no currency param). `fractionalDigits()`,
  `currencyNameSingular/Plural()`, `format(double)` all read the default currency.
- **Identifiers:** `OfflinePlayer` → `uniqueId` → `AccountId` (preferred). Deprecated `String name`
  overloads resolve to a UUID via a `PlayerResolver` that consults online players, the Bukkit
  offline-player cache, and our account name-map **only** — it never triggers a blocking Mojang web
  lookup. Unresolvable name → `false` / `FAILURE`.
- **Amounts:** `double → BigDecimal.valueOf(amount)` then round to default-currency digits; results back
  via `BigDecimal.toDouble()`. (Precision beyond ~15 significant digits is lost — an inherent limit of
  the legacy `double` API; documented, unavoidable.)
- **World:** ignored (as v2).
- **Sync path:** same mirror rules as §7 — it is the same `VaultSyncPath` instance, not a parallel copy
  of the rules.

### Method mapping
| Legacy method | Mapping |
|---|---|
| `isEnabled()`, `getName()` | plugin state / `"Geckonomy"` |
| `hasBankSupport()` | **false** |
| `fractionalDigits()` | default currency digits |
| `format(double)` | `FormatMoney` (default currency) |
| `currencyNameSingular/Plural()` | default currency names |
| `hasAccount(...)` (all overloads) | `AccountRepository.exists` |
| `createPlayerAccount(...)` (all overloads) | `CreateAccount` (idempotent) |
| `getBalance(...)` (all overloads) | `GetBalance` (default currency); mirror, or a bounded read when un-mirrored (§7) |
| `has(...)` (all overloads) | `Has` (default currency) |
| `withdrawPlayer(...)` (all overloads) | `Withdraw` → legacy `EconomyResponse` |
| `depositPlayer(...)` (all overloads) | `Deposit` → legacy `EconomyResponse` |
| `createBank`, `deleteBank`, `bankBalance`, `bankHas`, `bankWithdraw`, `bankDeposit`, `isBankOwner`, `isBankMember` | `EconomyResponse(0,0,NOT_IMPLEMENTED,"Banks not supported")` |
| `getBanks()` | empty list |

A `LegacyResponseMapper` builds the double-based legacy `EconomyResponse` from the same
`application.result` types used by v2.

## 9. Verification

- Register against a real Vault-aware plugin; confirm deposit/withdraw/transfer/balance in the default
  and a second currency (**v2**), and deposit/withdraw/balance via a **legacy v1** plugin.
- Assert every interface method (both v2 and legacy) is present (compile-time) and shared-account/bank
  methods return `NOT_IMPLEMENTED`/empty.
- Confirm `EconomyResponse.balance` reflects the post-operation balance in both providers.
- Confirm legacy name-based methods never trigger a blocking Mojang lookup.
