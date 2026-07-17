# Geckonomy — Roadmap

Milestones are ordered by the inward dependency rule (domain first, adapters last). Each is
independently reviewable, has explicit acceptance criteria, and maps to a task spec in `docs/tasks/`.
A coding agent takes one milestone at a time.

## Legend
- **Depends on** — milestones that must be complete first.
- **Done when** — objective, testable acceptance criteria.

---

### M0 — Foundation  ·  `tasks/M0-foundation.md`
Build tooling and the empty layered skeleton.
- Add deps: kotlinx-coroutines, HikariCP, sqlite-jdbc, mariadb-java-client, JUnit 5, MockK, MockBukkit,
  Testcontainers. Decide PluginLoader-libraries vs shade-relocate.
- Create the package skeleton (`domain`, `application`, `infrastructure`, composition root).
- Logging + plugin lifecycle stubs.
- **Depends on:** —
- **Done when:** `mvn package` builds; plugin enables/disables cleanly with empty wiring.

### M1 — Domain core  ·  `tasks/M1-domain-core.md`
Pure model + ports, fully unit-tested.
- Value objects (`AccountId`, `CurrencyCode`, `Money`, `Currency`, `Balance`, `Transaction`, enums).
- Policies (`RoundingPolicy`, `OverdraftPolicy`, `CurrencyValidation`).
- Port interfaces in `domain.port`.
- **Depends on:** M0
- **Done when:** domain unit tests green with no Bukkit/JDBC on the test classpath; invariants covered.

### M2 — Config & currency registry  ·  `tasks/M2-config-currencies.md`
- `config.yml` schema + typed loader + validation (§CONFIGURATION).
- `CurrencyRegistry` from config; default-currency validation; currency `scope` + command flags
  (`transferable`, `balance-check-others`, `show-in-baltop`).
- `StorageConfig`, `SettingsConfig` (incl. `server-id`); `/geckonomy reload` plumbing (config side).
- **Depends on:** M1
- **Done when:** valid config loads to typed objects; invalid config disables the plugin with a clear
  error; currency registry returns default + by-code.

### M3 — Persistence  ·  `tasks/M3-persistence.md`  ✅
- `DataSourceFactory` (Hikari) for sqlite + mariadb.
- `SqlDialect` (+ `SqliteDialect`, `MariaDbDialect`): identifiers, upserts, money encode/decode.
- `MigrationRunner` + `V001__init` per dialect.
- `SqlAccountRepository`, `SqlBalanceRepository` (atomic `adjust`), `SqlTransactionLog`, `SqlUnitOfWork`
  (transactional transfer), `ScopeResolver` (per-server vs network `scope_key`).
- `IoDispatcher`.
- **Depends on:** M1, M2
- **Done when:** the same repository test suite passes on in-memory SQLite and MariaDB (Testcontainers);
  transfer atomicity proven (forced failure rolls back both sides); per-server vs network scope keying
  verified (independent per-server balances, one shared network balance).
- **Done:** `RepositoryContract` (37 tests) green on both SQLite and MariaDB 11.4; `MigrationRunner`
  likewise via `MigrationRunnerContract`. The MariaDB half was deferred at M3 for lack of Docker and
  landed alongside M5 once Docker arrived — until then, `MariaDbDialect` had never executed.

### M4 — Application services  ·  `tasks/M4-application.md`  ✅
- Use cases: Create/Has/Get/Deposit/Withdraw/SetBalance/Transfer/Rename/Delete/ListCurrencies/FormatMoney,
  plus AccountExists/FindAccountName/ListAccountNames (FR-A2/A3) and CanDeposit/CanWithdraw (FR-B4).
- `EconomyService` facade (suspend fns); typed `OperationResult`/`TransferResult`/`EconomyError` over a
  generic sealed `Outcome`.
- Added `TransactionLog.purge` so `keep-transaction-history` works rather than shipping dead.
- **Depends on:** M1, M3
- **Done when:** use-case tests pass against fake ports and real repositories; error mapping covered.
- **Done:** 279 tests green, including `EconomyServiceSqliteTest` against real SQLite; enables and
  disables cleanly on a live Paper server.

### M5 — Localization & formatting  ·  `tasks/M5-localization.md`  ✅
- `MessageService` + MiniMessage rendering; `LanguageRepository`; `lang/en.yml`.
- `FormatMoney` wired to currency templates; safe (unparsed) player-input insertion.
- **Depends on:** M2, M4
- **Done when:** messages render with placeholders and correct money formatting; missing-key fallback
  works.
- **Done:** 431 tests green. `MessageKey` ships the full key set M6/M7 will spend, held to `en.yml` in
  both directions by `MessageKeyCoverageTest`. Fallback is three deep — active → disk `en` → bundled
  `en` → raw key — the extra layer being upgrade insurance for an owner-edited `en.yml`
  (LOCALIZATION.md §1). `FormatMoney` now takes its locale from `settings.language` rather than the
  host JVM, per call so a reload applies it.

### M6 — Vault providers (v2 + legacy v1)  ·  `tasks/M6-vault-provider.md`
- `VaultUnlockedEconomyProvider` implementing v2 `Economy`; `GeckonomyAsyncEconomy`; `ResponseMapper`;
  `OnlineBalanceMirror` + join/quit listener.
- `LegacyVaultEconomyProvider` implementing legacy `net.milkbowl.vault.economy.Economy` (single-currency
  → default currency; `double`↔`BigDecimal`; `OfflinePlayer`/name resolution without Mojang lookups;
  banks NOT_IMPLEMENTED); `LegacyResponseMapper`; `PlayerResolver`.
- Capability flags; register **both** services with `ServicesManager`; shared-account/bank methods
  NOT_IMPLEMENTED.
- **Depends on:** M4 (M5 for any messaged paths)
- **Done when:** a v2 Vault-aware plugin reads/writes balances in ≥2 currencies **and** a legacy-v1
  Vault plugin reads/writes the default currency; every interface method (both) implemented; no
  main-thread DB IO for online players.
- **Done:** 591 tests green (+2 MariaDB suites needing Docker). Both services register at `Highest`;
  `VaultRegistration` is the only class naming a Vault type at wiring time, so the soft dependency can
  be absent without a `NoClassDefFoundError`. Review changed two rules the docs had stated
  (`ARCHITECTURE.md §4` rewritten to match): sync reads **always** answer from the mirror, with an async
  refresh only for `NETWORK` currencies on **MariaDB** — the shipped default (`coins: network`,
  `storage: sqlite`) would otherwise have blocked the main thread on every third-party call — and sync
  **writes await the use case** rather than deciding optimistically in the adapter, which would have
  duplicated currency/amount/rounding/overdraft rules and could report SUCCESS for a write the database
  refuses. v2's `default` methods are traps (`transfer` is a non-atomic withdraw-then-deposit, `set` is
  read-then-adjust, `canWithdraw`/`canDeposit` return NOT_IMPLEMENTED); `VaultDefaultsTest` pins the
  overrides by reflection, and the guard was verified to actually fail before being kept. Live smoke on
  Paper 26.1.2 + VaultUnlocked 2.20.2 + ChestShop: 45/45, both services resolvable, ChestShop bound to
  our v1 provider, atomic transfer correct and a failed transfer moving no money. It caught what the
  tests could not — VaultUnlocked's *plugin name* is `Vault`, so the presence check found nothing and
  registration never ran; the check now tests for the v2 API class instead. Measured on the main thread
  (SQLite): mirrored read ~400 ns, un-mirrored fallback ~99 µs, awaited write ~1.4 ms median.

### M7 — Commands & UX  ·  `tasks/M7-commands.md`
- `/balance`, `/pay`, `/baltop`, admin `/eco give|take|set|reset`, `/geckonomy reload|version`.
- Permissions, tab completion, async execution, localized output.
- **Depends on:** M4, M5
- **Done when:** all commands work end-to-end on a live server with correct permissions and messages.

### M8 — Hardening & release  ·  `tasks/M8-hardening-release.md`
- Structured logging, slow-op warnings, optional bStats, error-path review.
- Finalize docs; packaging/relocation; versioned release build.
- **Depends on:** M0–M7
- **Done when:** release artifact builds; docs complete; acceptance scenarios in `SPEC.md §8` pass.

---

## Future (post-v1)
Schema and interfaces are already shaped for these:
- **Shared/bank accounts** + `AccountPermission` (uses `gk_account_member`, `AccountType.SHARED`).
- **Cross-server live sync** for `network`-scoped currencies via Redis pub/sub: publish balance-change
  events, invalidate/refresh the online mirror on other servers so `network` currencies can be mirrored
  instead of read-through. (Per-server scope + the `@global` keying already ship in v1; this milestone
  adds only the live propagation.)
- **Per-world economies** (new coordinate on repositories).
- **Per-player language** (`MessageService` already takes a locale).
- **Legacy Vault (v1) bank methods** — implement `createBank`/`bankDeposit`… once shared/bank accounts
  ship (the legacy *player* API already ships in v1's M6).
- **`/geckonomy seed`** — backfill balance rows for a currency added to config after accounts already
  existed. Today those players read as `0` rather than the currency's `starting-balance`, which is
  what `BalanceRepository.adjust`'s "a missing row counts as zero" contract forces (M4). Harmless, but
  an admin may want the opening balance handed out retroactively.
- PlaceholderAPI expansion, transaction-history command, importers from other economy plugins.
