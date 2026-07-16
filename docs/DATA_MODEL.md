# Geckonomy — Data Model & Persistence

DB is the single source of truth. Two backends: **SQLite** (local file) and **MariaDB** (remote),
selectable by config. Dialect differences are isolated behind `SqlDialect`; schema is designed to be
portable and **shared-account-ready** without a future breaking migration.

## 1. Tables

Prefix `gk_`. Types shown as `SQLite / MariaDB`.

### `gk_account`
| Column | Type (SQLite / MariaDB) | Notes |
|---|---|---|
| `id` | `TEXT` / `BINARY(16)` | Account UUID (PK). SQLite stores canonical string; MariaDB stores 16 bytes. |
| `name` | `TEXT` / `VARCHAR(64)` | Display name; not unique. |
| `type` | `TEXT` / `VARCHAR(16)` | `PLAYER` (v1). `SHARED` reserved. |
| `created_at` | `INTEGER` / `BIGINT` | Epoch millis. |

PK: `id`.

### `gk_balance`
| Column | Type | Notes |
|---|---|---|
| `account_id` | `TEXT` / `BINARY(16)` | FK → `gk_account.id`. |
| `currency_code` | `TEXT` / `VARCHAR(32)` | Currency code (lowercase). |
| `scope_key` | `TEXT` / `VARCHAR(64)` | `@global` for `network` currencies; the config `server-id` for `server` (per-server) currencies. See §7. |
| `amount` | `TEXT` / `DECIMAL(38,10)` | See §3 — SQLite stores a normalized decimal string. |

PK: `(account_id, currency_code, scope_key)`. **No world column** — balances are never per-world; they
are either network-wide (`@global`) or per-server (`server-id`).
Index: `(currency_code, scope_key, amount)` to support `/baltop`.

### `gk_transaction` (audit ledger, append-only)
| Column | Type | Notes |
|---|---|---|
| `id` | `TEXT` / `BINARY(16)` | Transaction UUID (PK). |
| `account_id` | `TEXT` / `BINARY(16)` | Subject account. |
| `currency_code` | `TEXT` / `VARCHAR(32)` | |
| `scope_key` | `TEXT` / `VARCHAR(64)` | `@global` or `server-id` (matches the balance touched). |
| `delta` | `TEXT` / `DECIMAL(38,10)` | Signed change. |
| `resulting_balance` | `TEXT` / `DECIMAL(38,10)` | Balance after. |
| `type` | `TEXT` / `VARCHAR(16)` | DEPOSIT/WITHDRAW/SET/TRANSFER_IN/TRANSFER_OUT. |
| `source_plugin` | `TEXT` / `VARCHAR(64)` | Vault pluginName or `geckonomy`; nullable. |
| `counterparty_id` | `TEXT` / `BINARY(16)` | For transfers; nullable. |
| `created_at` | `INTEGER` / `BIGINT` | Epoch millis. |

Index: `(account_id, created_at)`.

### `gk_account_member` (RESERVED — created, unused in v1)
Prepares shared/bank accounts so they need no schema break later.
| Column | Type | Notes |
|---|---|---|
| `account_id` | `TEXT` / `BINARY(16)` | Shared account. |
| `member_id` | `TEXT` / `BINARY(16)` | Member UUID. |
| `is_owner` | `INTEGER` / `TINYINT(1)` | Owner flag. |
| `permissions` | `INTEGER` / `INT` | `AccountPermission` bitmask. |

PK: `(account_id, member_id)`. Empty in v1.

### `gk_schema_version` (migration tracking)
| Column | Type | Notes |
|---|---|---|
| `version` | `INTEGER` | Highest applied migration number (single row) or one row per applied. |
| `applied_at` | `INTEGER` / `BIGINT` | Epoch millis. |

## 2. Migrations

- Numbered SQL files per dialect: `resources/db/migration/sqlite/V001__init.sql`,
  `resources/db/migration/mariadb/V001__init.sql`, …
- `MigrationRunner` reads applied version from `gk_schema_version`, applies pending files in order inside
  a transaction, records the new version. Runs on enable before any repository use.
- Keeping SQLite/MariaDB SQL separate makes dialect divergence explicit and reviewable.

## 3. Money storage & precision

- Domain money is `BigDecimal`. Never store as float/double.
- **MariaDB:** `DECIMAL(38,10)` — native exact decimal.
- **SQLite:** no real DECIMAL type. Store the **canonical string** form of the `BigDecimal` (fixed scale
  = max currency fractional digits, e.g. 10) so lexical ordering matches numeric ordering for `/baltop`,
  or store as INTEGER minor-units if all currencies share a scale. **Decision (open item):** default to
  fixed-scale zero-padded decimal string; confirm at M3. `SqlDialect` owns encode/decode so the choice
  is a single place.
- Always round to the currency's fractional digits (domain) before encoding.

## 4. Atomicity & concurrency

- `BalanceRepository.adjust` is atomic: a single `UPDATE ... SET amount = amount ± ?` (MariaDB) or a
  transactional read-modify-write (SQLite) guarded so it fails when the result would go negative and
  overdraft is off.
- **Transfers** run through `UnitOfWork.transaction { }` — one JDBC transaction wrapping debit + credit +
  two ledger inserts; commit or rollback together.
- Upserts (create balance row on first touch) use dialect-specific `INSERT ... ON CONFLICT`
  (SQLite) / `INSERT ... ON DUPLICATE KEY UPDATE` (MariaDB), abstracted by `SqlDialect`.
- SQLite: enable WAL mode + `busy_timeout`; single-writer semantics handled by the IO dispatcher's
  serialized access where needed.

## 5. Connection management

- **HikariCP** pool built by `DataSourceFactory` from `StorageConfig`.
  - SQLite: `org.xerial:sqlite-jdbc`, small pool (often 1 writer), file path from config.
  - MariaDB: `org.mariadb.jdbc:mariadb-java-client`, pool size from config, host/port/db/user/pass.
- Pool + IO dispatcher closed cleanly in `onDisable`.

## 6. Account lifecycle

- Auto-create on first join (async), seeding one `gk_balance` row per currency at `starting-balance`.
  The seeded row's `scope_key` is resolved per §7 (per-server currencies seed only for **this**
  server's `server-id`; a network currency seeds one `@global` row).
- Delete removes `gk_balance` rows; `gk_transaction` retention is config-driven (default: keep for
  audit).

## 7. Currency scope (per-server vs network)

Each currency has a `scope` (`CONFIGURATION.md`): `network` or `server`. This is resolved to the
`scope_key` used in `gk_balance`/`gk_transaction`:

| Currency `scope` | `scope_key` | Meaning |
|---|---|---|
| `network` | `@global` (constant) | One balance shared by every server pointed at this DB. |
| `server` | the config `settings.server-id` | Balance is private to this server instance. |

- The scope key is derived by the persistence layer from the resolved `Currency` + config `server-id`;
  callers (use cases, Vault adapter) pass only account + currency, never a scope key.
- On a **shared MariaDB**, this lets several servers coexist: they share `network` balances and keep
  independent `server` balances (distinguished by `server-id`).
- On **local SQLite** (single server) the distinction is cosmetic — all rows use that one `server-id` or
  `@global` — but the schema is identical so a server can be migrated onto a shared DB later.
- `/baltop` for a per-server currency ranks only this server's rows (`scope_key = server-id`); for a
  network currency it ranks the `@global` rows.
- **Live cross-server propagation** (network currency changed on server A → refresh server B's online
  mirror) is deferred; see `ARCHITECTURE.md §4` for the interim read-through rule and `ROADMAP.md`
  (future: Redis sync).

## 8. Player identity & UUID resolution

Accounts are keyed by **`player.uniqueId`** (`AccountId`) — never by name. The name is stored only for
display and the UUID→name map (`getUUIDNameMap`), refreshed on join.

**Two directions, not to be confused:**
- **UUID → name:** served from `gk_account`; display only, never decides identity.
- **name → UUID** (`PlayerResolver`, for legacy `String` methods and `/pay <name>` etc.): resolved
  **without blocking Mojang lookups**, in order — exact online player → Bukkit offline-player cache →
  `gk_account` reverse lookup → give up (`false`/`FAILURE`).

**Network-currency correctness depends on network-wide UUID consistency** (same player → same UUID on
every server sharing the DB). This is a **deployment precondition**, not something the plugin enforces:
- **Online mode:** Mojang UUIDs — globally stable; survive name changes. ✅
- **Offline mode:** UUID is `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)` — a deterministic hash of
  the exact username, identical on every Spigot/Paper server. So **two offline servers produce the same
  UUID for the same username** ✅ — but identity is the *username*: case-sensitive, changes with the
  name, and **unverified** (offline mode has no auth — anyone can log in as any name and access that
  balance).
- **Do not mix** online and offline servers in one network — the same player gets different UUIDs
  (Mojang vs hashed) → different accounts/balances.
- **Proxies (BungeeCord/Velocity):** backends run `online-mode=false` but the proxy forwards the *real*
  UUID (IP-forwarding / modern forwarding). With forwarding correctly and consistently enabled on all
  backends, UUIDs match. Inconsistent forwarding is the main real-world break.

Geckonomy simply trusts `player.uniqueId` as provided by the server/proxy; correct for all-online,
all-offline, or a properly-forwarded proxy network.
