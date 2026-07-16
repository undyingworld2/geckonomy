# Geckonomy — VaultUnlocked v2 Integration

Geckonomy implements **two** economy interfaces, both bundled in VaultUnlockedAPI 2.16, and registers
each as a Bukkit service:
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

`paper-plugin.yml`: add `dependencies` on `VaultUnlocked` (softdepend acceptable — register only if
Vault present). On enable, after wiring:

```kotlin
// v2 (multi-currency)
val v2 = VaultUnlockedEconomyProvider(economyService, currencyRegistry, mirror, messageService)
server.servicesManager.register(
    net.milkbowl.vault2.economy.Economy::class.java, v2, this, ServicePriority.Highest)

// legacy v1 (single-currency) — registered from v1; see §8
val v1 = LegacyVaultEconomyProvider(economyService, currencyRegistry, mirror, playerResolver)
server.servicesManager.register(
    net.milkbowl.vault.economy.Economy::class.java, v1, this, ServicePriority.Highest)
```
Unregister both in `onDisable`. `softdepend` Vault/VaultUnlocked so the service classes exist at runtime.

## 2. Response types (from source)

- `EconomyResponse(BigDecimal amount, BigDecimal balance, ResponseType type, String errorMessage)`;
  `transactionSuccess()` → `type == SUCCESS`. `ResponseType`: `SUCCESS(1)`, `FAILURE(2)`,
  `NOT_IMPLEMENTED(3)`.
- `MultiEconomyResponse(amount, type, errorMessage)` + `addBalance(UUID, BigDecimal)` /
  `balance(UUID): Optional<BigDecimal>`. Used for transfers (per-party resulting balances).
- `AccountPermission` enum: `DEPOSIT, WITHDRAW, BALANCE, TRANSFER_OWNERSHIP, INVITE_MEMBER,
  REMOVE_MEMBER, CHANGE_MEMBER_PERMISSION, OWNER, DELETE`.

`ResponseMapper` builds these from `application.result` types:
| EconomyError | ResponseType | errorMessage source |
|---|---|---|
| (success) | SUCCESS | `""` |
| InsufficientFunds | FAILURE | localized/plain reason |
| UnknownCurrency | FAILURE | reason |
| InvalidAmount | FAILURE | reason |
| AccountNotFound | FAILURE | reason |
| StorageFailure | FAILURE | reason |
| shared-account method | NOT_IMPLEMENTED | "Shared accounts not supported" |

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
  from `currency.scope` + the config `server-id` (see `DATA_MODEL.md §7`). For **network** currencies on
  a shared DB, the sync path reads through to the DB rather than the (possibly stale) mirror until Redis
  sync ships — see `ARCHITECTURE.md §4`.
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
| `getBalance(...)` / `balance(...)` (all world/currency overloads) | `GetBalance`; **online → mirror**, offline → bounded blocking read (§7) |
| `has(...)` (all overloads) | `Has` use case (mirror/DB) |
| `accountSupportsCurrency(...)` | `true` for known currencies (all accounts support all currencies) |
| `set(...)` (all overloads) | `SetBalance` → mirror update + async DB; `EconomyResponse` |

### Withdraw / deposit
| Vault method | Mapping |
|---|---|
| `canWithdraw(...)` | pre-flight `Withdraw` check (no mutation) → `EconomyResponse` |
| `withdraw(...)` | `Withdraw`; mirror update + async DB persist |
| `canDeposit(...)` | pre-flight `Deposit` check |
| `deposit(...)` | `Deposit`; mirror update + async DB persist |

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

`GeckonomyAsyncEconomy` mirrors the sync methods but returns futures/awaitable results and talks to the
`EconomyService` (DB) directly — **no mirror**, fully async. Integrators that check `supportsAsync()`
get exact, un-cached values. (Confirm exact `AsyncEconomy` signatures against the pinned source at M6.)

## 7. Sync path & the online mirror

The sync `Economy` interface returns values immediately and is called on the main thread. To satisfy
"never block the game thread":

- `OnlineBalanceMirror` holds online players' balances, hydrated async on join, evicted on quit.
- Sync **reads** hit the mirror. Sync **writes** update the mirror and dispatch the authoritative DB
  write asynchronously (DB stays source of truth; not a batched write-behind).
- Sync calls for **offline** UUIDs (rare) do a bounded blocking DB read/write with a timeout + warning.

See `ARCHITECTURE.md §4`.

## 8. Legacy v1 provider (`net.milkbowl.vault.economy.Economy`)

Shipped from v1. Single-currency, `double` amounts, `String`/`OfflinePlayer` identifiers. Package + a
separate legacy `EconomyResponse(double amount, double balance, ResponseType type, String errorMessage)`
(same `SUCCESS/FAILURE/NOT_IMPLEMENTED` enum). `LegacyVaultEconomyProvider` delegates to the same
`EconomyService` + `OnlineBalanceMirror` as v2.

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
- **Sync path:** same mirror rules as §7 (default currency's scope decides mirror vs read-through).

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
| `getBalance(...)` (all overloads) | `GetBalance` (default currency); mirror/read-through |
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
