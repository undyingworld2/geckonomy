# infrastructure.persistence

The SQL adapters: `DataSourceFactory` (Hikari), `SqlDialect` + `SqliteDialect`/`MariaDbDialect`,
`MigrationRunner`, `SqlAccountRepository`, `SqlBalanceRepository`, `SqlTransactionLog`, `SqlUnitOfWork`,
`ScopeResolver`, `IoDispatcher`.

The DB is the single source of truth. `ScopeResolver(serverId)` maps a `Currency` to its stored
`scope_key` — `@global` for `NETWORK`, the server id for `SERVER` — and lives **here** so no server id
ever reaches domain or application.

All work runs on the `IoDispatcher`; JDBC must never touch the Bukkit main thread. Balance `adjust` is
atomic. Schema in `docs/DATA_MODEL.md`; tested against in-memory SQLite and MariaDB (Testcontainers)
with one parametrized suite. Arrives with **M3**.
