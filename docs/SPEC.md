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
- Balances scoped **per currency**: `network` (shared by every server on the same DB) or `server`
  (private to one instance). Never per-world. See FR-C5.
- Local **SQLite** or remote **MariaDB** storage, selectable by config.
- VaultUnlocked v2 provider registration with multi-currency + async capability.
- Player & admin commands, permissions.
- Localization (multiple languages) with **MiniMessage** formatting.
- Audit ledger of transactions.

**Out of scope for v1 (reserved, see §9)**
- Shared/bank accounts and `AccountPermission` membership (schema is prepared), including the legacy
  Vault v1 *bank* methods. The legacy v1 **player** API ships in v1 — see FR-V6 and §6.
- Per-world economies.
- Cross-server **live** balance sync (Redis). Network-scoped balances themselves ship in v1.
- Per-player language selection.
- Importers.

**Planned, post-v1**
- **PlaceholderAPI expansion** — specified in §4.7, built at **M9**. Read-only and mirror-backed: it
  exposes what the economy already knows, and adds no way to change a balance.

## 2. Glossary

| Term | Meaning |
|---|---|
| **Account** | A holder of balances, identified by a UUID. In v1 always a player (`PLAYER` type). |
| **Currency** | A named unit of value defined in config (code, names, symbol, fractional digits). |
| **Money** | An amount bound to a currency (`BigDecimal` + `Currency`). |
| **Balance** | The amount an account holds in one currency. |
| **Default currency** | The currency used when a caller does not specify one. |
| **Currency scope** | Whether a currency's balances are `network` (shared across all servers on the same DB) or `server` (private to one server instance). |
| **Server id** | Config value identifying this server instance; the scope key for per-server currencies. |
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
- FR-C5 Each currency has a **scope**: `network` (balance shared by all servers on the same DB) or
  `server` (balance private to this server instance, keyed by `server-id`). Balances are keyed
  accordingly; a shared DB keeps per-server balances independent and network balances shared.
- FR-C6 Each currency carries command flags — `transferable`, `balance-check-others`, `show-in-baltop`
  — that hard-gate the corresponding player commands regardless of permissions.

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
- FR-V6 **Also** implement and register the **legacy** `net.milkbowl.vault.economy.Economy` (v1)
  provider from v1, delegating to the same services. Single-currency (default currency); `double`
  amounts converted via `BigDecimal`; `OfflinePlayer`/name identifiers resolved to UUID without blocking
  Mojang lookups; bank methods `NOT_IMPLEMENTED` and `hasBankSupport()=false`.

### 4.5 Commands (see §7)
- FR-CMD1 Players check balances and pay others.
- FR-CMD2 Admins give/take/set/reset balances and reload config.
- FR-CMD3 Baltop lists richest accounts per currency.
- FR-CMD4 `balance`, `pay`, and `baltop` are gated by **per-currency permission nodes** in addition to
  base command nodes; players can be allowed some currencies and denied others.
- FR-CMD5 `/balance` is also reachable as `/bal`.

### 4.6 Localization
- FR-L1 All player-facing text comes from language files (no hard-coded strings).
- FR-L2 Messages are authored in MiniMessage and support placeholders.
- FR-L3 Server language is selectable in config; missing keys fall back to the default language.

### 4.7 Placeholders (M9)

A **PlaceholderAPI expansion** under the identifier `geckonomy`, letting scoreboard, tab-list, hologram
and chat plugins display balances, currency names and leaderboards. **Read-only**: it exposes what the
economy already knows and adds no way to change a balance. Full placeholder table and parsing rules in
`tasks/M9-placeholders.md`.

- FR-P1 Register a `PlaceholderExpansion` under identifier `geckonomy` when PlaceholderAPI is
  installed, with `persist() = true` — an internal expansion is otherwise unregistered on `/papi
  reload`. PlaceholderAPI is a **soft** dependency: absent, Geckonomy enables normally and says so in
  the log. Same posture as Vault (FR-V1).
- FR-P2 Expose, per currency: symbol, singular and plural name, fractional digits.
- FR-P3 Expose the requesting player's balance per currency — raw, formatted per the currency's
  `format` template, comma-grouped, and truncated to whole units.
- FR-P4 Expose the currency name **agreeing with an amount** (`Currency.nameFor`, so a balance of
  exactly one reads "1 Coin" and never "1.00 Coins").
- FR-P5 Expose a formatted rendering of an **arbitrary** amount, through the same `FormatMoney` a
  command uses — one formatter, so a placeholder and `/balance` can never disagree.
- FR-P6 Expose the leaderboard per currency: name and balance at a given rank, and the requesting
  player's own rank.
- FR-P7 **Placeholders never perform database IO.** Balances are served from the online-player mirror
  and the leaderboard from a periodically-refreshed snapshot. Anything unknown renders a configurable
  fallback string. This is stricter than the Vault sync path, which may do a bounded blocking read for
  an un-mirrored account (§5 NFR-1, `ARCHITECTURE.md §4`), and the difference is deliberate: Vault is
  asked once per shop sale, whereas a placeholder is re-rendered **every tick, for every viewer, for
  offline players too**. The fallback that is a rare path for Vault would be the common one here.
- FR-P8 An unrecognized placeholder returns `null`, so PlaceholderAPI leaves the text as it found it.
  Never an exception into PAPI's render loop, and never a fabricated value that a player would read as
  a real balance.
- FR-P9 The per-currency **config flags do not gate placeholders**: `transferable`,
  `balance-check-others` and `show-in-baltop` are rules about a *command* — an actor doing something,
  or a viewer looking at someone else — and PlaceholderAPI supplies neither an actor nor a viewer, only
  the target player. Placeholders are a raw data surface; the flags stay a command-layer concern.
  **A consequence worth stating plainly rather than discovering:** a `show-in-baltop: false` currency
  is still reachable through the leaderboard placeholders, so that flag hides a currency from
  `/baltop` and not from a hologram. Per-currency permission nodes are likewise unenforceable here —
  placeholders are not a privacy boundary, and a server that needs one must not print the placeholder.

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
| Currency scope (per-server / network) | ✅ | Each currency is `server`- or `network`-scoped; schema keys balances accordingly |
| Shared accounts (VaultUnlocked banks) | ❌ false | Reserved; `gk_account_member` schema ready |
| Async | ✅ true | `AsyncEconomy` provided |
| Per-world balances | ❌ | `world` param accepted, ignored |
| Cross-server live sync | ❌ | Network-scoped balances share a DB, but live propagation (Redis) is future; interim refresh-behind-the-read (see `ARCHITECTURE.md §4`) |
| Legacy Vault (v1) `Economy` provider | ✅ v1 | Register the *original* `net.milkbowl.vault.economy.Economy` (bundled in VaultUnlockedAPI) alongside v2, for the many plugins still bound to it. Single-currency → default currency. |
| Legacy Vault (v1) bank methods | ❌ | `hasBankSupport()=false`; bank methods return `NOT_IMPLEMENTED` (banks deferred; distinct from VaultUnlocked shared accounts) |
| PlaceholderAPI expansion | ⏳ M9 | Identifier `geckonomy`; soft dependency; read-only; mirror-backed, never touches the database (§4.7) |

## 7. Commands & permissions

| Command (aliases) | Description | Base permission | Per-currency permission |
|---|---|---|---|
| `/balance` (`/bal`) `[player] [currency]` | Show own or another's balance | `geckonomy.balance` (+`.others`) | `geckonomy.balance.<code>` (+`.others.<code>`) |
| `/pay <player> <amount> [currency]` | Transfer to another player | `geckonomy.pay` | `geckonomy.pay.<code>` |
| `/baltop [currency]` | Richest accounts | `geckonomy.baltop` | `geckonomy.baltop.<code>` |
| `/eco give <player> <amount> [currency]` | Add balance | `geckonomy.admin` | — |
| `/eco take <player> <amount> [currency]` | Remove balance | `geckonomy.admin` | — |
| `/eco set <player> <amount> [currency]` | Set balance | `geckonomy.admin` | — |
| `/eco reset <player> [currency]` | Reset to starting balance | `geckonomy.admin` | — |
| `/geckonomy reload` | Reload config & languages | `geckonomy.admin` | — |
| `/geckonomy version` | Show version | `geckonomy.admin` | — |

**Per-currency permissions.** For `balance`, `pay`, and `baltop`, a currency is usable only when the
player holds **both** the base node **and** the per-currency node (`geckonomy.pay.coins`,
`geckonomy.balance.others.gems`, …; wildcards `geckonomy.pay.*` etc. supported). In addition, currency
config flags are hard gates independent of permissions: `transferable: false` disables `/pay` for that
currency entirely, `balance-check-others: false` hides others' balance in it, `show-in-baltop: false`
excludes it from `/baltop`. Admin `/eco` bypasses per-currency permission nodes (but should respect the
existence of the currency).

Defaults: `.<code>` per-currency nodes default `true` (opt-out model); base player commands `true`;
admin `op`.

## 8. Acceptance (v1 done)

- A Vault-aware test plugin reads and writes Geckonomy balances in the default and a second currency.
- `/pay` moves funds atomically; a forced failure mid-transfer leaves both balances unchanged.
- Switching `storage.type` between `sqlite` and `mariadb` yields identical behavior.
- All player-facing text is localized; switching language changes output.
- No main-thread DB IO under normal operation (verified by logging/timing).

## 9. Reserved features (post-v1)

Shared/bank accounts + `AccountPermission` (incl. legacy v1 bank methods); cross-server live sync
(Redis); per-world economies; per-player language; transaction-history command; importers. Schema and
interfaces are shaped so these land without breaking v1 contracts.

_(The **PlaceholderAPI expansion** has left this list: it is specified in §4.7 and scheduled as **M9**
— `tasks/M9-placeholders.md`. It is still post-v1, but it is no longer merely reserved.)_

_(Note: the legacy v1 `Economy` **player** API ships in v1 — see §6 capability matrix and FR-V6 — only
its bank methods are deferred with shared accounts.)_

See also: `ARCHITECTURE.md`, `DOMAIN_MODEL.md`, `DATA_MODEL.md`, `VAULT_INTEGRATION.md`,
`CONFIGURATION.md`, `LOCALIZATION.md`, `ROADMAP.md`.
