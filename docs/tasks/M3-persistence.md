# Task M3 — Persistence

**Goal:** Implement the domain ports against SQLite and MariaDB, with migrations, atomic adjustments,
and transactional transfers. All IO off the main thread.

**Read first:** `../DATA_MODEL.md`, `../ARCHITECTURE.md §3,§4`, `../CODING_STANDARDS.md §3`.

## Create (`infrastructure/persistence`)
- `DataSourceFactory` — builds a HikariCP `DataSource` from `StorageConfig` for sqlite | mariadb.
- `SqlDialect` interface + `SqliteDialect`, `MariaDbDialect`: identifier quoting, upsert syntax,
  UUID encode/decode (TEXT vs BINARY(16)), **money encode/decode** (§DATA_MODEL 3), boolean handling.
- `MigrationRunner` — reads `gk_schema_version`, applies pending `resources/db/migration/<dialect>/Vxxx__*.sql`
  in order inside a transaction, records version. Runs on enable.
- `V001__init.sql` per dialect creating: `gk_account`, `gk_balance` (PK `(account_id, currency_code,
  scope_key)`), `gk_transaction` (with `scope_key`), `gk_account_member` (reserved), `gk_schema_version`
  + indexes from `DATA_MODEL.md`.
- `SqlAccountRepository`, `SqlBalanceRepository` (atomic `adjust`, `top` for baltop), `SqlTransactionLog`.
- `SqlUnitOfWork` implementing `UnitOfWork`/`TxContext` — one JDBC transaction; used by transfers.
- `ScopeResolver(serverId)` — maps a `Currency` → `scope_key` (`@global` for `NETWORK`, the config
  `server-id` for `SERVER`). Injected into the balance/transaction repositories; see `DATA_MODEL.md §7`.
- `IoDispatcher` — named bounded thread pool as a `CoroutineDispatcher`; all repository suspend fns run
  here.

## Implementation notes
- `adjust` must be atomic and reject sub-zero results when overdraft is off (single UPDATE guarded by a
  WHERE, or transactional read-modify-write on SQLite). Return the new balance.
- Upserts via dialect (`ON CONFLICT` / `ON DUPLICATE KEY UPDATE`).
- SQLite: WAL mode + `busy_timeout`; serialize writes as needed via the dispatcher.
- Money: round to currency digits **before** encode; decode back to `BigDecimal`.
- No Bukkit imports in this package.

## Acceptance / tests
- A single parametrized repository suite runs on **in-memory SQLite** and **MariaDB (Testcontainers)**:
  create/exists/rename/delete accounts; get/set/adjust balances; top-N; ledger append.
- **Scope keying:** a `SERVER`-scoped currency under two different `server-id`s yields two independent
  balances for the same account; a `NETWORK` currency yields one shared `@global` balance regardless of
  server-id. `top()` for a per-server currency ranks only that server's rows.
- **Transfer atomicity:** a `UnitOfWork` block that throws after the debit leaves both balances
  unchanged (rollback proven).
- `MigrationRunner` is idempotent (running twice applies nothing the second time).
