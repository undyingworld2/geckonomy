# infrastructure.persistence

The SQL adapters: `DataSourceFactory` (Hikari), `SqlDialect` + `SqliteDialect`/`MariaDbDialect`,
`MigrationRunner`, `SqlAccountRepository`, `SqlBalanceRepository`, `SqlTransactionLog`, `SqlUnitOfWork`,
`ConnectionSource`, `ScopeResolver`, `IoDispatcher`.

The DB is the single source of truth. `ScopeResolver(serverId)` maps a `Currency` to its stored
`scope_key` — `@global` for `NETWORK`, the server id for `SERVER` — and lives **here** so no server id
ever reaches domain or application.

All work runs on the `IoDispatcher`; JDBC must never touch the Bukkit main thread. Balance `adjust` is
atomic on both backends and returns `null` when the overdraft guard refuses it. Money is stored at a
fixed scale of 4 — SQLite as INTEGER minor units, MariaDB as `DECIMAL(38,4)`; `SqlDialect` owns the
encoding and `SqlDialect.MONEY_SCALE` is the one place the scale is stated. Schema and the reasoning
behind both decisions are in `docs/DATA_MODEL.md` §3–§4.

`ConnectionSource` is the seam that lets one repository serve two lifetimes: `Pooled` gives a
connection per call, `Pinned` gives the one `SqlUnitOfWork` opened — which is what makes a transfer's
debit, credit, and ledger rows commit together. `SqlBalanceRepository` defers to an ambient
transaction (`if (!connection.autoCommit) return block()`), which is why M4 can wrap even a
single-account deposit in a `UnitOfWork` for free.

`SqlTransactionLog.purge` is the ledger's only non-append operation, added in M4 for
`settings.keep-transaction-history: false`. It deletes across **all** scope keys: the caller is
deleting an account, and an account is not per-server.

Tested by `RepositoryContract`, one suite per dialect subclass. **SQLite only so far**: the MariaDB
subclass needs Docker for Testcontainers and has not been written, so `MariaDbDialect` ships with only
its generated SQL unit-tested. See `docs/tasks/M3-persistence.md` § Not done.
