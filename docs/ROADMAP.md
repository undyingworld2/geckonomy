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

### M6 — Vault providers (v2 + legacy v1)  ·  `tasks/M6-vault-provider.md`  ✅
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

### M7 — Commands & UX  ·  `tasks/M7-commands.md`  ✅
- `/balance`, `/pay`, `/baltop`, admin `/eco give|take|set|reset`, `/geckonomy reload|version`.
- Permissions, tab completion, async execution, localized output.
- **Depends on:** M4, M5
- **Done when:** all commands work end-to-end on a live server with correct permissions and messages.
- **Done:** 706 tests green (591 at M6), and for the first time **no suite is skipped** — the two
  MariaDB suites ran here. M5's bet paid: the whole vocabulary was already in `MessageKey`/`en.yml`, and
  M7 added **no message key**.

  Review chose Cloud v2, then a spike disproved its premise and it was **reverted to Paper Brigadier**.
  Every Cloud command manager — modern *and* legacy — reflects into NMS/CraftBukkit in its constructor
  (`ArgumentTypeInfos`, `VanillaCommandWrapper`), so none can be built under MockBukkit, and M7's
  acceptance criteria are MockBukkit tests per command. The legacy manager was checked precisely because
  it makes Brigadier opt-in and looked like the escape hatch; the reflection is in the Bukkit base class,
  above that choice. Brigadier passes, needs no dependency, and is in `paper-api` already. The order was
  the point: the spike ran before a line of command code existed.

  `/baltop` had no use case at all — `BalanceRepository.top` shipped at M3 with nothing above it. Added
  `ListTopBalances` plus an `AccountRepository.namesOf(ids)` port method (one `IN` query, bounded by
  `baltop-size`) rather than spending `nameMap()`'s full table scan to label ten rows, which
  `ListAccountNames`' own KDoc had already warned against.

  Three bugs the tests caught that review had not. **`/balance gems` read as a player name**: two
  `word()` arguments cannot disambiguate by content — Brigadier matches the first child greedily — so
  the handler decides, and `/balance <word>` is a currency if it names one and a player otherwise (a
  word that is neither reads as a missing player; the unambiguous position still reports the currency).
  **`/eco give` reported the balance, not the amount** — every use case returns the resulting balance,
  so giving $100 to a player holding $1000 said "Gave $1100.00". **`GeckonomyPermissions.register`
  threw on a node that already existed**, which would have failed the *enable* after an unclean disable;
  it now replaces rather than adds, and tracks what it registered because a reload swaps the registry
  before it is called.

  The permission traps are real and invisible to an op: an unregistered node defaults to **op**, so the
  per-currency nodes had to be registered at enable from the registry with `PermissionDefault.TRUE`
  (SPEC §7's opt-out model), and a wildcard grants nothing unless registered holding its children.
  `GeckonomyPermissionsTest` asserts a **non-op** passes them.

  Also fixed a latent build bug: `io.mockk:mockk` is the multiplatform artifact whose jar holds no
  classes, so MockK had never worked under Maven — declared since M0 and unnoticed because nothing used
  it until now. It is `mockk-jvm`.

### M8 — Hardening & release  ·  `tasks/M8-hardening-release.md`  ✅
- Structured logging, slow-op warnings, optional bStats, error-path review.
- Finalize docs; packaging/relocation; versioned release build.
- **Depends on:** M0–M7
- **Done when:** release artifact builds; docs complete; acceptance scenarios in `SPEC.md §8` pass.
- **Done:** 717 tests green (706 at M7), 0 skipped, `geckonomy-1.0.0.jar`. bStats was dropped rather
  than deferred — it was the only thing that wanted the shading M0 decided against, and "optional" was
  the honest answer. Logging stayed on JUL: `application/README.md` already whitelists it, the
  composition root already passes `JavaPlugin.logger` with no adapter, and a logging port would have
  bought structure nobody reads at the cost of a layer.

  **The live smoke test earned its place twice.** `storage.type: mariadb` **had never once worked on a
  real server** — SPEC §8 requires the two backends to behave identically, and the milestone that was
  supposed to prove it is where it turned out to be false. Hikari resolves a driver through
  `DriverManager`, whose registry is a `ServiceLoader` scan of the *system* classloader;
  `GeckonomyLoader` puts our libraries on the plugin's isolated one, which that scan never sees. The
  test suite cannot see this and never could: Testcontainers puts the driver on the system classpath,
  where `DriverManager` finds it regardless. **SQLite had only appeared to work** — Paper ships
  `sqlite-jdbc` for itself, so a driver was registered, at Paper's version rather than the one
  `geckonomy-libraries.txt` pins. `DataSourceFactory` now names `driverClassName` for both. The same
  shape as M6's `name: Vault` discovery, and the same lesson: the classloader and the service registry
  are the two things a unit test is structurally blind to.

  It took an afternoon only because the error path threw the diagnosis away — `openStorage` logged
  `e.message`, and Hikari's message is the symptom (`Failed to get driver instance`) while the cause
  chain underneath holds the answer (`ConnectException: Connection refused`). It now logs the whole
  chain. An error path that a human reads under pressure is worth the same care as the happy one.

  **The error-path audit found a real bug, not hygiene.** Every use case was guarded; none of the
  coroutines *calling* them were. A throw in a command body reached the scope's `SupervisorJob`, which
  cancels that one child and logs nothing — so the command never replied, which is the one failure a
  player cannot report. `GeckonomyCommands.launchGuarded` is now the only place a command coroutine
  starts; `PlayerConnectionListener` catches around its `runBlocking` (a cold mirror must never cost a
  player entry); `GeckonomyAsyncEconomy` logs a future that `future {}` would otherwise swallow. The
  scope's `CoroutineExceptionHandler` is a net, not a plan — by the time it fires there is nobody left
  to answer. Each guard was verified to fail before it was kept.

  Slow-op timing went into `StorageGuard` because it is already the one place that sees every storage
  call *and* already knows what each was doing. Warnings are throttled but not keyed: per-account keys
  come from callers, so the map would grow fastest exactly during the flood it exists to survive.

  Docs: the M6 threading rewrite had never propagated — the rejected read-through rule survived in six
  places, including the **shipped `config.yml`**, and `VAULT_INTEGRATION.md §4` contradicted its own §7.
  `OnlineBalanceMirror`'s own KDoc still described the optimistic write M6 rejected. `SPEC.md §1`
  contradicted FR-C5 and FR-V6. A first user-facing `README.md` now exists at the root; everything in
  `docs/` was written for contributors. The MariaDB suites became `disabledWithoutDocker` so a
  contributor without Docker gets the other 700 rather than a hard failure — the release build runs
  where Docker does, and "0 skipped" is the thing to check.

---

## Future (post-v1)
Schema and interfaces are already shaped for these:
- **Shared/bank accounts** + `AccountPermission` (uses `gk_account_member`, `AccountType.SHARED`).
- **Cross-server live sync** for `network`-scoped currencies via Redis pub/sub: publish balance-change
  events so another server's mirror is invalidated when it changes, rather than refreshed just after it
  is next read — which is the interim rule, and the reason a `network` balance on MariaDB can be read
  one call stale. (Per-server scope + the `@global` keying already ship in v1; this milestone adds only
  the live propagation.)
- **Per-world economies** (new coordinate on repositories).
- **Per-player language** (`MessageService` already takes a locale).
- **Legacy Vault (v1) bank methods** — implement `createBank`/`bankDeposit`… once shared/bank accounts
  ship (the legacy *player* API already ships in v1's M6).
- **`/geckonomy seed`** — backfill balance rows for a currency added to config after accounts already
  existed. Today those players read as `0` rather than the currency's `starting-balance`, which is
  what `BalanceRepository.adjust`'s "a missing row counts as zero" contract forces (M4). Harmless, but
  an admin may want the opening balance handed out retroactively.
- PlaceholderAPI expansion, transaction-history command, importers from other economy plugins.
