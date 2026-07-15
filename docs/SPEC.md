# Geckonomy — Functional & Non-Functional Specification

Status: v1 design · Owner: the1mason · Analyst/PM: Claude

## 1. Purpose & scope

Geckonomy is a **multi-currency economy _provider_** for modern Paper/Spigot servers. It owns player
accounts and balances and exposes them to the rest of the server through the **VaultUnlocked v2 API**
(`net.milkbowl.vault2.economy.Economy`). Any Vault-aware plugin (shops, jobs, quests) transacts against
Geckonomy without knowing its storage or internals.

**In scope (v1)**
- Personal, owner-only accounts keyed by player UUID.
- Multiple currencies defined in config, one marked default.
- Global balances (not per-world, not per-server).
- Local **SQLite** or remote **MariaDB** storage, selectable by config.
- VaultUnlocked v2 provider registration with multi-currency + async capability.
- Player & admin commands, permissions.
- Localization (multiple languages) with **MiniMessage** formatting.
- Audit ledger of transactions.

**Out of scope for v1 (reserved, see §9)**
- Shared/bank accounts and `AccountPermission` membership (schema is prepared).
- Per-world economies.
- Cross-server balance sync (Redis).
- Per-player language selection.
- PlaceholderAPI expansion, importers, legacy Vault bridge.

## 2. Glossary

| Term | Meaning |
|---|---|
| **Account** | A holder of balances, identified by a UUID. In v1 always a player (`PLAYER` type). |
| **Currency** | A named unit of value defined in config (code, names, symbol, fractional digits). |
| **Money** | An amount bound to a currency (`BigDecimal` + `Currency`). |
| **Balance** | The amount an account holds in one currency. |
| **Default currency** | The currency used when a caller does not specify one. |
| **Provider** | Geckonomy's implementation of the Vault `Economy` service. |
| **Ledger / transaction** | An immutable audit record of a balance change. |
| **Mirror** | In-memory balance snapshot for online players, used only by the synchronous Vault path. |

## 3. Actors

- **Player** — checks own balance, pays other players.
- **Admin** — grants/removes/sets balances, reloads config.
- **Third-party plugin** — reads/writes balances via the Vault `Economy` service.
- **Server owner** — configures currencies, storage, language.

## 4. Functional requirements

### 4.1 Accounts
- FR-A1 Create an account for a UUID with a display name; idempotent (creating an existing account is a
  no-op returning success).
- FR-A2 Report whether an account exists.
- FR-A3 Look up an account's name by UUID and expose the UUID→name map.
- FR-A4 Rename an account.
- FR-A5 Delete an account (removes balances + optionally retains ledger per config).
- FR-A6 On first join, a player's account is auto-created and seeded with each currency's
  `starting-balance`.

### 4.2 Currencies
- FR-C1 Load currencies from config at startup and on reload.
- FR-C2 Exactly one currency is the default; validation fails startup if zero or more than one.
- FR-C3 Expose currency list, default currency, singular/plural names, symbol, and fractional digits.
- FR-C4 Reject operations against unknown currency codes with a typed error.

### 4.3 Balances & transactions
- FR-B1 Get an account's balance in a currency (default currency if unspecified).
- FR-B2 `has` — check an account holds at least an amount.
- FR-B3 Deposit, withdraw, set — each returns a typed result with resulting balance.
- FR-B4 `canDeposit` / `canWithdraw` — pre-flight checks without mutating state.
- FR-B5 Transfer between two accounts is **atomic** (both sides commit or neither).
- FR-B6 Withdrawals below zero are rejected unless `allow-overdraft` is enabled.
- FR-B7 Every mutating operation appends an immutable ledger row.
- FR-B8 All amounts are rounded to the currency's fractional digits before persistence.

### 4.4 Vault integration
- FR-V1 Register as the Vault `Economy` service at highest priority on enable.
- FR-V2 Advertise `hasMultiCurrencySupport()=true`, `hasSharedAccountSupport()=false`,
  `supportsAsync()=true`.
- FR-V3 Implement every v2 `Economy` method; shared-account methods return
  `false`/empty/`NOT_IMPLEMENTED` gracefully.
- FR-V4 Map internal results to `EconomyResponse` / `MultiEconomyResponse`.
- FR-V5 The synchronous Vault path must not block the main thread for online players (§ mirror).

### 4.5 Commands (see §7)
- FR-CMD1 Players check balances and pay others.
- FR-CMD2 Admins give/take/set/reset balances and reload config.
- FR-CMD3 Baltop lists richest accounts per currency.

### 4.6 Localization
- FR-L1 All player-facing text comes from language files (no hard-coded strings).
- FR-L2 Messages are authored in MiniMessage and support placeholders.
- FR-L3 Server language is selectable in config; missing keys fall back to the default language.

## 5. Non-functional requirements

- NFR-1 **No main-thread DB IO.** All database access runs on a dedicated dispatcher; the sync Vault
  path is served from the online-player mirror.
- NFR-2 **Consistency.** DB is the single source of truth; transfers are transactional.
- NFR-3 **Precision.** Monetary math uses `BigDecimal`; no floating-point money.
- NFR-4 **Portability.** Identical behavior on SQLite and MariaDB; dialect differences isolated behind
  `SqlDialect`.
- NFR-5 **Extensibility.** Clean layering; adding shared accounts or a new storage backend requires no
  change to domain or application layers' public contracts.
- NFR-6 **Testability.** Domain/application testable without a server; persistence tested on both
  backends.
- NFR-7 **Resilience.** DB errors surface as typed failures, never uncaught exceptions crossing into
  Bukkit callers.
- NFR-8 **Observability.** Structured logging of failures and slow operations.

## 6. Capability matrix (Vault v2)

| Capability | v1 value | Notes |
|---|---|---|
| Multi-currency | ✅ true | Config-defined currencies |
| Shared accounts | ❌ false | Reserved; schema ready |
| Async | ✅ true | `AsyncEconomy` provided |
| Per-world balances | ❌ | `world` param accepted, ignored |
| Bank/legacy Vault bridge | ❌ | Future |

## 7. Commands & permissions

| Command | Description | Permission |
|---|---|---|
| `/balance [player] [currency]` | Show own or another's balance | `geckonomy.balance` (+`.others`) |
| `/pay <player> <amount> [currency]` | Transfer to another player | `geckonomy.pay` |
| `/baltop [currency]` | Richest accounts | `geckonomy.baltop` |
| `/eco give <player> <amount> [currency]` | Add balance | `geckonomy.admin` |
| `/eco take <player> <amount> [currency]` | Remove balance | `geckonomy.admin` |
| `/eco set <player> <amount> [currency]` | Set balance | `geckonomy.admin` |
| `/eco reset <player> [currency]` | Reset to starting balance | `geckonomy.admin` |
| `/geckonomy reload` | Reload config & languages | `geckonomy.admin` |
| `/geckonomy version` | Show version | `geckonomy.admin` |

Defaults: player commands `true`; admin `op`.

## 8. Acceptance (v1 done)

- A Vault-aware test plugin reads and writes Geckonomy balances in the default and a second currency.
- `/pay` moves funds atomically; a forced failure mid-transfer leaves both balances unchanged.
- Switching `storage.type` between `sqlite` and `mariadb` yields identical behavior.
- All player-facing text is localized; switching language changes output.
- No main-thread DB IO under normal operation (verified by logging/timing).

## 9. Reserved features (post-v1)

Shared/bank accounts + `AccountPermission`; cross-server sync (Redis); per-world economies; per-player
language; PlaceholderAPI expansion; transaction-history command; importers; legacy Vault bridge. Schema
and interfaces are shaped so these land without breaking v1 contracts.

See also: `ARCHITECTURE.md`, `DOMAIN_MODEL.md`, `DATA_MODEL.md`, `VAULT_INTEGRATION.md`,
`CONFIGURATION.md`, `LOCALIZATION.md`, `ROADMAP.md`.
