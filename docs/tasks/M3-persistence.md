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
- `SqlAccountRepository`, `SqlBalanceRepository` (atomic `adjust`, `top` for baltop), `SqlTransactionLog`
  (`purge` was added in M4, when `DeleteAccount` gave `settings.keep-transaction-history` its first
  caller).
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

## Decisions taken (M3)

- **Money encoding** (the open item from DATA_MODEL §3): fixed scale **4**; SQLite `INTEGER` minor
  units, MariaDB `DECIMAL(38,4)`. Ceiling ±922 trillion; `fractional-digits` capped at 4 in config.
  Full reasoning in DATA_MODEL §3 — the decimal-string alternative mis-ranks negative balances.
- **`adjust` refusal**: the port returns `BigDecimal?`, `null` = refused by the guard. Chosen over an
  exception because CODING_STANDARDS §4 makes insufficient funds a typed result. Amended
  `BalanceRepository` and ARCHITECTURE §3.
- **`adjust` is atomic on both backends** — the minor-unit encoding lets SQLite do the arithmetic in
  SQL too, so the read-modify-write this task allowed for is not needed. See DATA_MODEL §4.
- **`allow-overdraft` is now restart-only**: the guard is compiled into the SQL at startup, so
  `ConfigService` warns on a reload that changes it. Added `OverdraftPolicy.allowsNegativeBalances()`
  for the repository to build the clause from.
- **SQLite pool is pinned to one connection**, overriding `pool.maximum-pool-size`, and the
  `IoDispatcher` is sized to match (1 thread for SQLite, pool size for MariaDB).
- **Migrations are indexed** by a `migrations.txt` per dialect (jars cannot list directories), and the
  transactional guarantee does **not** hold on MariaDB. See DATA_MODEL §2.
- **Account deletion relies on `ON DELETE CASCADE`** rather than a second `DELETE`, so it is atomic
  without a transaction. A test guards the cascade.

## MariaDB — closed at M5

MariaDB shipped unexecuted at M3 because the dev machine had no Docker. It now does, and the gap is
closed exactly as this section predicted: one `MariaDbRepositoryTest` supplying a `StorageConfig` from
a `MariaDBContainer`, with every expectation already in `RepositoryContract`. All 37 contract tests
pass against a real MariaDB 11.4 (~44 s), so **M3's "same suite passes on both" criterion is now met
for real** — `MariaDbDialect` and its `V001__init.sql` execute rather than being only string-tested.

`MigrationRunnerTest` was split the same way, into a `MigrationRunnerContract` with a subclass per
dialect (5 tests each). It gained a case the SQLite-only version could not justify: a migration whose
version row was never recorded must re-run cleanly over its own tables. That is MariaDB's *only*
recovery path — its DDL implicitly commits, so a half-applied file cannot roll back — and it is what
the `IF NOT EXISTS` on every statement exists to buy.

Each suite owns its container: the repository tests wipe rows between tests, the migration tests drop
the schema, and sharing one container would have them fighting over the same tables.

## Acceptance / tests
- A single parametrized repository suite runs on **in-memory SQLite** and **MariaDB (Testcontainers)**:
  create/exists/rename/delete accounts; get/set/adjust balances; top-N; ledger append.
- **Scope keying:** a `SERVER`-scoped currency under two different `server-id`s yields two independent
  balances for the same account; a `NETWORK` currency yields one shared `@global` balance regardless of
  server-id. `top()` for a per-server currency ranks only that server's rows.
- **Transfer atomicity:** a `UnitOfWork` block that throws after the debit leaves both balances
  unchanged (rollback proven).
- `MigrationRunner` is idempotent (running twice applies nothing the second time).
