# infrastructure.persistence

The SQL adapters: `DataSourceFactory` (Hikari), `SqlDialect` + `SqliteDialect`/`MariaDbDialect`,
`MigrationRunner`, `SqlAccountRepository`, `SqlBalanceRepository`, `SqlTransactionLog`, `SqlUnitOfWork`,
`AccountWriteGate`, `WriteConflict`, `ConnectionSource`, `ScopeResolver`, `IoDispatcher`.

The DB is the single source of truth. `ScopeResolver(serverId)` maps a `Currency` to its stored
`scope_key` — `@global` for `NETWORK`, the server id for `SERVER` — and lives **here** so no server id
ever reaches domain or application.

All work runs on the `IoDispatcher`; JDBC must never touch the Bukkit main thread. Balance `adjust` is
atomic on both backends and returns `null` when the overdraft guard refuses it. Money is stored at a
fixed scale of 4 — SQLite as INTEGER minor units, MariaDB as `DECIMAL(38,4)`; `SqlDialect` owns the
encoding and `SqlDialect.MONEY_SCALE` is the one place the scale is stated. Schema and the reasoning
behind both decisions are in `docs/DATA_MODEL.md` §3–§4.

**The write path (M13)** — rebuilt after concurrent deposits deadlocked on MariaDB in production:

- `adjust` **locks first, never upgrades**: `SELECT … FOR UPDATE`, then the guarded `UPDATE`; the new
  balance is the locked one plus the delta, with no read-back. A missing row is a plain `INSERT`, and
  losing that race is a `WriteConflict`. `lock(rows)` takes several rows in one global order (account
  id unsigned big-endian, then currency). The MariaDB pool runs READ COMMITTED (`DataSourceFactory`),
  so a locking read that finds no row takes no gap lock.
- `SqlUnitOfWork` holds `AccountWriteGate`'s striped mutex for every account in `writes`, so our own
  writers to one account queue in memory rather than on the row lock with a connection held.
- It **retries only what certainly rolled back** (`isWriteConflict`: `WriteConflict`, SQLState
  `40001`, MariaDB 1205), up to 3 attempts with jittered backoff. A `commit()` that throws is settled by
  looking up the transaction's first ledger row: present means it committed, absent means retry.
  Anything else is reported, never re-run on a guess.

`ConnectionSource` is the seam that lets one repository serve two lifetimes: `Pooled` gives a
connection per call, `Pinned` gives the one `SqlUnitOfWork` opened — which is what makes a transfer's
debit, credit, and ledger rows commit together. `SqlBalanceRepository` defers to an ambient
transaction (`if (!connection.autoCommit) return block()`), which is why M4 can wrap even a
single-account deposit in a `UnitOfWork` for free.

`SqlTransactionLog.purge` is the ledger's only non-append operation, added in M4 for
`settings.keep-transaction-history: false`. It deletes across **all** scope keys: the caller is
deleting an account, and an account is not per-server.

Tested by `RepositoryContract`, one suite per dialect subclass (`SqliteRepositoryTest`,
`MariaDbRepositoryTest`; the MariaDB ones need Docker for Testcontainers and skip without it).
`MariaDbConcurrencyTest` runs two harnesses — two servers — against one MariaDB and asserts InnoDB's
`Innodb_deadlocks` counter does not move; `SqlUnitOfWorkRetryTest` covers retry and commit
verification on SQLite with a fault-injecting `DataSource`.
