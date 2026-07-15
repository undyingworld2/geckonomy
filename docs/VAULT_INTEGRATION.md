# Geckonomy — VaultUnlocked v2 Integration

Geckonomy implements `net.milkbowl.vault2.economy.Economy` (VaultUnlockedAPI 2.16) and registers it as a
Bukkit service. This document is the authoritative method-by-method mapping. Every interface method is
either **implemented** or explicitly **NOT_IMPLEMENTED** with a reason (v1 has no shared accounts).

Reference source (pin a local copy under `.reference/VaultUnlockedAPI/` for the coding agents):
`net/milkbowl/vault2/economy/{Economy,AsyncEconomy,EconomyResponse,MultiEconomyResponse,AccountPermission}.java`.

## 1. Registration

`paper-plugin.yml`: add `dependencies` on `VaultUnlocked` (softdepend acceptable — register only if
Vault present). On enable, after wiring:

```kotlin
val provider = VaultUnlockedEconomyProvider(economyService, currencyRegistry, mirror, messageService)
server.servicesManager.register(
    net.milkbowl.vault2.economy.Economy::class.java,
    provider, this, ServicePriority.Highest
)
```
Unregister in `onDisable`.

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
- `worldName: String` — **accepted and ignored** (global balances).
- `currency: String` — resolved to a `CurrencyCode`; when absent, the **default currency** is used.
  Unknown currency → `FAILURE` (or `false`/empty for boolean/collection returns).
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

## 8. Verification

- Register against a real Vault-aware plugin; confirm deposit/withdraw/transfer/balance in the default
  and a second currency.
- Assert every interface method is present (compile-time) and shared-account methods return
  `NOT_IMPLEMENTED`/empty.
- Confirm `EconomyResponse.balance` reflects the post-operation balance.
