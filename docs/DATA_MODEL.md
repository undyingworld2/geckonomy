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
| `amount` | `TEXT` / `DECIMAL(38,10)` | See §3 — SQLite stores a normalized decimal string. |

PK: `(account_id, currency_code)`. **No world column** — balances are global.
Index: `(currency_code, amount)` to support `/baltop`.

### `gk_transaction` (audit ledger, append-only)
| Column | Type | Notes |
|---|---|---|
| `id` | `TEXT` / `BINARY(16)` | Transaction UUID (PK). |
| `account_id` | `TEXT` / `BINARY(16)` | Subject account. |
| `currency_code` | `TEXT` / `VARCHAR(32)` | |
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
- Delete removes `gk_balance` rows; `gk_transaction` retention is config-driven (default: keep for
  audit).
