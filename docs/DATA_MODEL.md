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
| `amount` | `INTEGER` / `DECIMAL(38,4)` | See §3 — SQLite stores a count of minor units at scale 4. |

PK: `(account_id, currency_code, scope_key)`. **No world column** — balances are never per-world; they
are either network-wide (`@global`) or per-server (`server-id`).
FK: `account_id` → `gk_account.id` `ON DELETE CASCADE` (§6).
Index: `(currency_code, scope_key, amount)` to support `/baltop`. Ordering by `amount` is only correct
because it is numeric — see §3.

### `gk_transaction` (audit ledger, append-only)
| Column | Type | Notes |
|---|---|---|
| `id` | `TEXT` / `BINARY(16)` | Transaction UUID (PK). |
| `account_id` | `TEXT` / `BINARY(16)` | Subject account. |
| `currency_code` | `TEXT` / `VARCHAR(32)` | |
| `scope_key` | `TEXT` / `VARCHAR(64)` | `@global` or `server-id` (matches the balance touched). |
| `delta` | `INTEGER` / `DECIMAL(38,4)` | Signed change. Encoded as `gk_balance.amount` (§3). |
| `resulting_balance` | `INTEGER` / `DECIMAL(38,4)` | Balance after. |
| `type` | `TEXT` / `VARCHAR(16)` | DEPOSIT/WITHDRAW/SET/TRANSFER_IN/TRANSFER_OUT. |
| `source_plugin` | `TEXT` / `VARCHAR(64)` | Vault pluginName or `geckonomy`; nullable. |
| `counterparty_id` | `TEXT` / `BINARY(16)` | For transfers; nullable. |
| `created_at` | `INTEGER` / `BIGINT` | Epoch millis. |

**No foreign key** on `account_id`, unlike `gk_balance`: the ledger outlives the account it describes
when `keep-transaction-history` is on (§6), which a foreign key would forbid.
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
- Each dialect directory carries a `migrations.txt` listing its files in apply order. A classloader
  cannot reliably list a directory inside a jar, so the index is what the runner reads — the same
  approach as `geckonomy-libraries.txt`. **A migration not listed is a migration never applied.**
- `MigrationRunner` reads the applied versions from `gk_schema_version`, applies pending files in
  order, and records each version in the same transaction as the file it applied. Runs on enable,
  before any repository use; the plugin refuses to start if it fails.
- The tracking table is created by `V001` like any other, so the first run has nothing to read from.
  The runner asks the driver's metadata whether the table exists rather than catching a failed
  `SELECT` — "no schema yet" is a fact to look up, not an error to recover from.
- **The transaction does not protect MariaDB.** DDL there implicitly commits, so a file that fails
  halfway leaves the statements that already ran; only SQLite makes DDL transactional. The recovery is
  that the version row is written only on success, so the next start re-runs the whole file — which is
  safe **only because every statement in every migration is `IF NOT EXISTS`**. That is an invariant
  each migration author must keep, not something the runner can enforce.
- Keeping SQLite/MariaDB SQL separate makes dialect divergence explicit and reviewable.

## 3. Money storage & precision  ·  *decided at M3*

- Domain money is `BigDecimal`. Never store as float/double.
- **Fixed scale of 4**, both backends. `SqlDialect.MONEY_SCALE` is the single source of truth;
  `ConfigLoader` caps `fractional-digits` at it (CONFIGURATION.md §3) so no currency can ask for
  precision the store would truncate.
- **SQLite:** `INTEGER` count of minor units (amount × 10⁴).
- **MariaDB:** `DECIMAL(38,4)` — native exact decimal, same scale.
- Always round to the currency's fractional digits (domain, `RoundingPolicy`) before encoding.
  `SqlDialect` owns encode/decode, so the representation is one place; an amount finer than scale 4 or
  beyond the range below throws `MoneyOutOfRange` rather than being silently truncated.

### Why (the open item this replaces)

The earlier proposal was a fixed-scale zero-padded **decimal string** on SQLite, so lexical ordering
would serve `/baltop`. It does not: `"-9.00" > "-1.00"` lexically, so any server with
`allow-overdraft: true` would rank negative balances backwards. Integer minor units sort numerically,
support `amount = amount + ?` in a single guarded `UPDATE` (§4), and stay exact.

The cost is a **ceiling**: a 64-bit integer at scale *n* caps a balance at `2^63-1 / 10^n`.

| Scale | Max balance |
|---|---|
| 10 | ~922 million — reachable by a real economy |
| **4** | **±922,337,203,685,477** (~922 trillion) — not |

Hence scale 4: two more digits than the conventional currency needs, and a ceiling nobody meets.

The scale is **fixed rather than per-currency** because it is how a stored integer is *interpreted*.
Read from a currency's `fractional-digits`, editing that value in `config.yml` would silently multiply
or divide every existing balance by a power of ten. Fixed, config decides only rounding.

MariaDB uses `DECIMAL(38,4)` rather than a wider `DECIMAL` for the same reason its range is still
checked against SQLite's 64-bit ceiling: both stores must hold **exactly the same set of values**, or a
balance written on MariaDB could not be migrated to SQLite.

## 4. Atomicity & concurrency

- `BalanceRepository.adjust` is atomic on **both** backends — the minor-unit encoding (§3) means SQLite
  can do arithmetic in SQL too, so the transactional read-modify-write once planned for it is not
  needed. Three statements in one transaction:
  1. `INSERT OR IGNORE` a zero row — a missing row means zero, and a currency added to config after an
     account exists has none until something touches it (§6).
  2. `UPDATE ... SET amount = amount + ? WHERE <key> AND amount + ? >= 0` — check and write in one
     statement, so two concurrent withdrawals cannot both pass and jointly overdraw. The guard clause
     is omitted entirely when `allow-overdraft` is on. **Zero rows updated = refused**, which the port
     returns as `null` rather than an exception (CODING_STANDARDS.md §4).
  3. `SELECT` the new balance — MariaDB has no `UPDATE ... RETURNING`. The transaction is what makes
     this trustworthy: without it, another server on the same database could change the balance
     between the update and the read.
- The overdraft rule is **compiled into the guard at startup**, because it must live in the same
  statement as the update. A reload cannot change it; `ConfigService` warns and asks for a restart
  (CONFIGURATION.md §4).
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
- **`driverClassName` is set explicitly for both, and must stay that way.** Without it Hikari resolves
  the driver through `DriverManager`, whose registry comes from a `ServiceLoader` scan of the *system*
  classloader — and `GeckonomyLoader` puts our libraries on the plugin's own isolated classloader,
  which that scan never sees. The symptom is Hikari's `Failed to get driver instance for jdbcUrl=…`.
  Naming the class makes Hikari instantiate it from the classloader that holds it.
  - Found at M8 by running MariaDB on a real server for the first time. It is **invisible to the test
    suite by construction**: Testcontainers and the SQLite suites put the driver on the system
    classpath, where `DriverManager` finds it anyway.
  - SQLite had only *appeared* to work — Paper ships `sqlite-jdbc` for its own use, so a SQLite driver
    was registered already. It was Paper's copy at Paper's version, not the one
    `geckonomy-libraries.txt` pins.
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
  mirror) is deferred; see `ARCHITECTURE.md §4` for the interim refresh-behind-the-read rule and
  `ROADMAP.md` (future: Redis sync).

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
