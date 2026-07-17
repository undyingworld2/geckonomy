# Task M6 — Vault Providers (v2 + legacy v1)

**Goal:** Implement and register **both** the VaultUnlocked v2 `Economy` service **and** the legacy
`net.milkbowl.vault.economy.Economy` (v1) service, backed by the same `EconomyService`, with a
non-blocking sync path for online players. Both interfaces ship in the existing VaultUnlockedAPI
`provided` dependency — no new dependency.

**Read first:** `../VAULT_INTEGRATION.md` (authoritative mapping), `../ARCHITECTURE.md §4`, pinned
`.reference/VaultUnlockedAPI/` source.

## Create (`infrastructure/vault`)
- `OnlineBalanceMirror` — `ConcurrentHashMap<AccountId, ConcurrentHashMap<CurrencyCode, BigDecimal>>`;
  `hydrate(id)` (async load all currencies), `evict(id)`, `get`, `put`.
- `ResponseMapper` — `EconomyError`/results → `EconomyResponse` / `MultiEconomyResponse` per
  `VAULT_INTEGRATION.md §2`.
- `VaultUnlockedEconomyProvider : net.milkbowl.vault2.economy.Economy` — implement **every** method per
  the mapping table:
  - capability flags (multi-currency true, shared false, async true);
  - currency/format/account/balance/has/set/withdraw/deposit/canX/transfer;
  - `world` ignored; `currency` resolved (default when absent);
  - shared-account methods → `false`/empty/`NOT_IMPLEMENTED`.
- `GeckonomyAsyncEconomy` — the `AsyncEconomy` impl talking directly to `EconomyService` (no mirror).
- `LegacyVaultEconomyProvider : net.milkbowl.vault.economy.Economy` — implement **every** legacy method
  per `VAULT_INTEGRATION.md §8`:
  - single-currency → default currency; `hasBankSupport()=false`.
  - `double`↔`BigDecimal` conversion, rounded to default-currency digits.
  - identifier resolution via `PlayerResolver` (`OfflinePlayer`/name → UUID from online/offline-cache/
    account name-map only — **no blocking Mojang lookups**).
  - player account/balance/has/withdraw/deposit → delegate to `EconomyService` (+ mirror per §Sync).
  - all bank methods → legacy `EconomyResponse(0,0,NOT_IMPLEMENTED,…)`; `getBanks()` → empty.
- `LegacyResponseMapper` — build the legacy (double-based) `EconomyResponse` from `application.result`.
- `PlayerResolver` — name/OfflinePlayer → `AccountId`, non-blocking.

## Create (`infrastructure/bukkit`)
- `PlayerConnectionListener` — on join: async create account (seed starting balances if new) + hydrate
  mirror; on quit: evict.

## Wire (composition root)
- Register **both** providers (`net.milkbowl.vault2.economy.Economy` and
  `net.milkbowl.vault.economy.Economy`) with `ServicesManager` at `ServicePriority.Highest`; declare the
  Vault/VaultUnlocked dependency in `paper-plugin.yml`. Unregister both on disable.

## Sync-path rules

Revised at review; these supersede the read-through rule this section originally carried. All of them
live in `VaultSyncPath`, which both providers go through — one copy, or they drift.

- **Reads always answer from the mirror.** For `NETWORK` currencies on **MariaDB** an async refresh runs
  behind the read (deduped per account+currency) so it converges. On **SQLite no refresh fires**: the file
  cannot be shared, so a network currency has exactly one writer and cannot go stale. The old rule would
  have made the shipped default config (`coins: network`, `storage: sqlite`) block the main thread on
  every third-party call, guarding against staleness that configuration cannot produce.
- **Writes await the use case** (bounded `withTimeout`), then put the balance the DB actually returned
  into the mirror. Not optimistic: deciding in the adapter would duplicate currency resolution,
  `Amounts.positive`, the reloadable rounding mode and `OverdraftPolicy`, and could report SUCCESS for a
  write the database refuses.
- Un-mirrored account (offline, or not yet hydrated) → bounded blocking DB call + warning log. A timeout
  → `StorageFailure`, never a fabricated zero.
- The mirror is keyed by `(AccountId, CurrencyCode)`; scope resolution happens in the persistence layer,
  not the mirror.
- Hydration happens on `AsyncPlayerPreLoginEvent`, not `PlayerJoinEvent`: already off the main thread,
  and finished before the player is visible, so no plugin can hit the blocking fallback on join.

## Acceptance / tests
- **v2** adapter unit-tested over `EconomyFixture` (a *real* `EconomyService` on in-memory ports, not a
  fake service): response mapping for success + each error; shared-account methods return
  NOT_IMPLEMENTED; flags correct.
- **Legacy v1** adapter unit-tested: `OfflinePlayer` + name resolution; `double`↔`BigDecimal` round-trip;
  bank methods return NOT_IMPLEMENTED; `hasBankSupport()=false`; name resolution never blocks on Mojang
  (asserted: `getOfflinePlayer(String)` is never called — `getOfflinePlayerIfCached` is).
- **`VaultDefaultsTest`** — reflection over `declaringClass` asserting we override every dangerous v2
  `default`. Without it a silent regression to the non-atomic default `transfer` loses FR-B5.
- **Live smoke:** a v2 Vault plugin reads/writes ≥2 currencies (transfer → correct
  `MultiEconomyResponse`); a **legacy v1** Vault plugin reads/writes the default currency; plugin still
  enables cleanly with VaultUnlocked **absent**.
- Both services resolvable via `ServicesManager.getRegistration(...)`.
- No main-thread DB IO for online players (profile with `spark`; assert no JDBC frames on the main thread
  while a Vault plugin transacts).

## Status

Done. 591 unit tests green (the 2 MariaDB suites need Docker, unavailable here), and the live smoke test
passed 45/45 on Paper 26.1.2 with VaultUnlocked 2.20.2 + ChestShop.

**The smoke test earned its keep — it caught a bug every unit test missed.** VaultUnlocked's *plugin
name* is `Vault`, not `VaultUnlocked`: it is a drop-in replacement and keeps the original's name. The
presence check and the `paper-plugin.yml` dependency both looked for `VaultUnlocked`, so registration was
skipped entirely and ChestShop reported "No Vault compatible Economy plugin found!". M6 did not work at
all on a real server while every test was green. The name alone cannot distinguish the two (the original
is also `Vault`), so `onEnable` now checks for the v2 API class by string.

Measured on the main thread, SQLite:
| path | cost |
|---|---|
| read, mirrored | ~400 ns |
| read, un-mirrored fallback | ~99 µs |
| write, awaited | median 1.4 ms, p95 2.0 ms, max 3.8 ms |
| pre-login hydrate | ~6 ms, off the main thread |

So NFR-1 holds as approved: no JDBC on the main thread anywhere, mirrored reads are free, and a write
parks the tick ~1.4 ms — the cost of the "await writes" decision. ~35 Vault writes in one tick would
consume it.

Not covered: MariaDB's async refresh path (no Docker here), and concurrent cross-server staleness.
