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

### M9 — PlaceholderAPI expansion  ·  `tasks/M9-placeholders.md`  ✅
A read-only `geckonomy` expansion: currency symbol/name/digits, the player's balance per currency
(raw, formatted, comma-grouped, fixed), an arbitrary amount through `FormatMoney`, and the
leaderboard (name/balance at rank, own rank).
- `GeckonomyExpansion` (the only class naming a PAPI type), `PlaceholderResolver` (all the logic, no
  PAPI types, plain-JUnit testable), `BaltopSnapshot` (timer-refreshed leaderboard cache).
- Soft dependency + presence check + `AutoCloseable` registration, exactly as M6 did for Vault.
- `placeholders:` config block; `me.clip:placeholderapi` at `provided` scope.
- **Depends on:** M4, M5, M7 — the leaderboard needs `ListTopBalances`, which M7 added for `/baltop`.
- **Done when:** every placeholder in the M9 table renders on a live Paper server through a real
  scoreboard plugin; the expansion survives `/papi reload`; the plugin still enables cleanly with
  PlaceholderAPI **absent**; and `spark` shows **no JDBC frames on the main thread** while a
  scoreboard renders leaderboard placeholders for online and offline players alike.
- **Done:** 770 tests green (717 at M8), 0 skipped. Review overturned the task spec twice, and the
  spec had argued both sides of the first one itself.

  **Offline balances resolve rather than rendering `0` forever.** The spec said balances come "from
  the mirror or not at all", which makes an offline player's balance permanently unknowable — the
  exact lie ("a scoreboard will show it for hours") the same page argued against two paragraphs
  earlier. `OfflineBalanceCache` answers from memory and fills **behind** the render, so PAPI's next
  tick shows the truth and the main thread still never touches JDBC. `FR-P7`'s intent survives
  unchanged; only its wording moved, and `SPEC.md §4.7` was rewritten rather than left to rot — M8's
  lesson about a rejected rule surviving in six places. The cost is a TTL, an in-flight dedupe and a
  sweep, without which a tab list is a memory leak.

  **The spike came out opposite to M7's, which is why it was worth running either way.**
  `PlaceholderExpansion`'s constructor only calls `Object.<init>` and sets a field — no static
  initializer, no PAPI singleton — so it builds under plain JUnit *and* MockBukkit with no PAPI
  present. `GeckonomyExpansion` is therefore tested, `persist()` included; only `register()` is not.
  The spike also found the trap it was not looking for: `PlaceholderHook.onRequest` passes **`null`**
  to `onPlaceholderRequest` for an offline player, discarding who was asked about — overriding that
  method instead of `onRequest` would have made most of a tab list unanswerable, and every offline
  test would have been written against the wrong hook.

  **A stale `target/` was inflating the count.** Deleting the spike's source did not delete its
  class, and moving `OnlineBalanceMirrorTest` to `infrastructure.balance` left the `vault` copy
  behind — both kept running, and 717 read as 728 before a line of M9 test code existed. A deleted
  test that still passes is worse than a failing one. The number above is from `mvn clean test`.

  `StorageGuard` catches `Exception` wholesale, so **no use-case call can throw** and the obvious way
  to test `BaltopSnapshot`'s guard does not reach it. The reachable throw is the unguarded edge —
  `size()` and `amounts.currency()`, which `ListTopBalances` leaves outside `guarding` — and M8 had
  already found the same edge from the other side in `CommandFailureTest`. The guard was verified to
  fail before being kept: without it the loop dies silently and the snapshot freezes forever.

  Two smaller decisions the spec left contradictory. A rank that **cannot exist**
  (`baltop_player_abc`, `_0`) renders `null`; a well-formed rank the snapshot does not hold — every
  rank in the first minute after boot — renders the fallback, because the alternative prints raw
  `%geckonomy_baltop_player_1%` across every scoreboard on every restart. And `OnlineBalanceMirror`
  moved to `infrastructure/balance`: two adapters read it, so a package named for one of them lied.

  **Live smoke on Paper 26.1.2 + PlaceholderAPI 2.12.3 + VaultUnlocked 2.20.2 + ChestShop.** The
  expansion registers (`Successfully registered internal expansion: geckonomy`), `papi list` shows
  it, and it **survives `/papi reload`** — the `persist()` check the task doc singled out as "the one
  that will be wrong", and it is right. `/papi parse --null` returned correct values for everything
  that needs no player: `format_1234.5` → `$1,234.50`, `format_5_gems` → `5 Gems`, the metadata for
  both currencies, and — `FR-P8` on a real server — an unknown variant *and* an unknown currency each
  came back as the **literal** `%geckonomy_…%`, left untouched rather than faked. With the
  PlaceholderAPI jar removed, the plugin enables cleanly and logs that placeholders are unavailable;
  no exception either way.

  What headless console cannot reach: `/papi parse <name>` requires an **online** player, so the
  balance, offline-fill and `baltop` placeholders, a scoreboard rendering every tick, and the spark
  main-thread-JDBC profile all need a connected client. Those rest on the 770 unit tests — including
  the `FR-P7` call-counter test that fails if a port is ever touched synchronously — and on the
  structural fact that `PlaceholderResolver` has no blocking path at all, unlike `VaultSyncPath`'s
  `runBlocking`: nothing in `onRequest` can reach JDBC on the calling thread by construction. Still
  worth a client-driven pass before calling the invariant proven, which is the one thing left.

### M10 — Styled & localizable currency display  ·  `tasks/M10-currency-display.md`  ✅
- Currency `symbol`/`singular`/`plural` and the `format` template rendered as **self-contained
  MiniMessage components** (SPEC §4.6 FR-L4); player input stays unparsed.
- `FormatMoney` and `Placeholders.currency` return/insert Components via `Placeholder.component`
  instead of unparsed strings; PAPI expansion serializes to legacy text.
- Lang-file currency-name overrides: `currencies.<code>.singular|plural`, config as fallback
  (FR-L5). New `CurrencyNames` resolver in `infrastructure/i18n` consulted by `FormatMoney` and
  `Placeholders`, so the "one rule" property survives (LOCALIZATION.md §4).
- Docs: `LOCALIZATION.md §3/§4`, `CONFIGURATION.md §2`, shipped `config.yml`, `lang/en.yml` +
  `lang/ru.yml`.
- **Depends on:** M5, M7, M9
- **Done when:** a `<gradient>`-styled symbol renders in `/balance`, `/pay`, `/baltop` and
  placeholders without bleeding; player input cannot inject MiniMessage; a `currencies:` block in
  `ru.yml` overrides names live on `/geckonomy reload`; PAPI returns styled names as legacy text.
- **Done:** 725 tests green (712 at M9, plus 13 for `CurrencyNames`/the `currencies:` override and a
  styled-symbol PAPI case). `FormatMoney` **relocated from `application.usecase` to
  `infrastructure.i18n`**, not merely edited: once it renders a `Component` it holds a framework type,
  which `application` may not import (CODING_STANDARDS.md §2) — `application/README.md` had said as
  much about why it returned a `String`. `EconomyService` lost its `format` method entirely rather than
  keep a hollowed-out passthrough; every consumer (commands, both Vault providers, PlaceholderAPI) now
  holds the same `FormatMoney` instance directly, which is what makes FR-P5's "one formatter" property
  hold structurally rather than by convention. The relocation moved a wiring dependency, too: `Economy`
  (which builds `FormatMoney`) now needs `CurrencyNames`, which needs the `LanguageRepository`
  `loadMessages` builds — so `onEnable` had to build the language repository *before* `Economy`, a
  reversal of M4's original order.

  **`MessageKeyCoverageTest` caught a bug in its own twin.** `LanguageRepository.parse()`'s section
  filter drops `getKeys(true)`'s intermediate paths (`currencies`, `currencies.coins`) but not their
  *leaves* — `currencies.coins.singular` is a plain string, not a section, so it survived the filter
  and read as an orphan message key the moment `lang/en.yml` grew a `currencies:` block. The coverage
  test's own bundled-map builder duplicates that same flattening (and had since M5), so both needed the
  identical fix: exclude the `currencies` subtree outright, not just its section markers. Caught by the
  test suite, not review — the exact case `MessageKeyCoverageTest` exists for.

  Vault's `currencyNameSingular()`/`currencyNamePlural()` (both interfaces) previously read
  `currency.singular`/`.plural` straight off the config object, bypassing any lang override — a latent
  inconsistency nobody had reason to notice before there was an override to disagree with. Routing them
  through `CurrencyNames` fixed it as a side effect of the relocation, not a separate task.

### M11 — Globalization  ·  `tasks/M11-globalization.md`
- CLDR plural categories via **ICU4J `PluralRules`**: `Currency.nameFor` generalizes from binary
  singular/plural to a category selected by amount + locale (SPEC §4.6 FR-L6).
- Lang files gain per-category name forms (`one`/`few`/`many`/`other`…), additive over M10's
  `singular`/`plural` (which map to `one`/`other`).
- **Per-player language** (FR-L7): resolve an effective locale per audience; number grouping and
  messages follow it; `settings.language` is the default. Wire the `MessageService.locale` param that
  has been reserved since M5.
- ICU4J added as a library (GeckonomyLoader entry, CODING_STANDARDS §8) — verify it loads under the
  plugin's isolated classloader (M8 classloader lesson) and that its bundled locale data resolves.
- **Depends on:** M10
- **Done when:** `ru` renders "1 рубль / 2 рубля / 5 рублей / 1.5 рубля" correctly; English "1 Coin /
  5 Coins" via one/other; a Russian-client player sees Russian while others see the server default;
  number grouping matches the effective locale; the plugin enables with ICU4J isolated.

### M12 — Bonus currency  ·  `tasks/M12-bonus-currency.md`
- Per-currency `bonus:` block (`currency`, `ratio`, `by-default`): a deposit also credits the paired
  currency, atomically, with a `BONUS` ledger row (SPEC §4.3 FR-B9/B10).
- `/eco give … [bonus|nobonus]`; take/set/reset and transfers never touch the bonus.
- Vault deposits follow `by-default`; the sync path mirrors the bonus balance.
- **Depends on:** M4, M6, M7
- **Done when:** a Vault deposit of the source currency raises the bonus balance for online and offline
  players; `/eco give` honours both keywords; a failed bonus write leaves the deposit unwritten; `/pay`
  mints nothing.
- **Done:** 830 tests green, 0 skipped. No migration — the ledger's `type` column is unconstrained, so
  `BONUS` is just a new value. `Deposit` now returns `Deposited(balance, bonus)` rather than a bare
  balance, because the sync path needs the bonus balance to keep the mirror honest. Found on the way:
  `asSection()` copied a list entry's nested map as a raw `Map`, invisible to `child()` — harmless until
  a currency entry had a nested block. Also found: `/eco` had never written through to
  `OnlineBalanceMirror`, so after an admin change a Vault plugin read an online player's old balance
  until they relogged — it now does, as `VaultSyncPath` always has, and so does `/pay`. Both had leaned
  on a doc claim that SQLite "cannot go stale", which was only true of other *servers*, not of our own
  write paths that bypassed the mirror. `MirrorRefresher` now re-reads online balances every
  `settings.mirror-refresh-seconds` as a backstop, storing a read only if no write overtook it.

### M13 — Balance write path rework  ·  `tasks/M13-write-path.md`
- Prompted by a production incident: concurrent deposits to one player deadlocked on MariaDB (1213) and
  the losing deposit was dropped. The cause dates from M2: `adjust`'s `INSERT IGNORE` seed took a shared
  lock that its `UPDATE` then had to upgrade (DATA_MODEL.md §4).
- Lock first (`SELECT … FOR UPDATE`) under READ COMMITTED; multi-row writes lock in one global order;
  one writer per account per server (`AccountWriteGate`); conflicts retried only when certainly rolled
  back; a commit of unknown outcome settled by its ledger row (SPEC §5 NFR-9).
- Sync Vault writes: the timeout bounds the wait, never the write. The async API writes the mirror.
  `MirrorRefresher` reads in one query per currency.
- **Depends on:** M3, M4, M6, M12
- **Done when:** concurrent deposits/transfers/withdrawals from two servers on one MariaDB lose nothing,
  overdraw nothing, and leave InnoDB's deadlock counter unchanged; a timed-out Vault write still commits
  and says so.
- **Done (code):** 841 tests green, 0 skipped. `MariaDbConcurrencyTest` was written red first against the
  M12 code (234 deadlocks across its scenarios) and is green with `Innodb_deadlocks` flat. No migration.
  Deviation from the plan: no op-id scheme. Only definitely-rolled-back conflicts are retried, so the one
  ambiguous case, a failed commit, is identified by the ledger row the transaction already wrote. **Live
  verification on the network is still to do.**

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
- **Legacy Vault (v1) bank methods** — implement `createBank`/`bankDeposit`… once shared/bank accounts
  ship (the legacy *player* API already ships in v1's M6).
- **`/geckonomy seed`** — backfill balance rows for a currency added to config after accounts already
  existed. Today those players read as `0` rather than the currency's `starting-balance`, which is
  what `BalanceRepository.adjust`'s "a missing row counts as zero" contract forces (M4). Harmless, but
  an admin may want the opening balance handed out retroactively.
- Transaction-history command, importers from other economy plugins.
