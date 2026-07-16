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
- **Per-server (`SERVER`) currencies:** sync reads → mirror; sync writes → update mirror immediately +
  dispatch authoritative async DB write via `EconomyService`.
- **Network (`NETWORK`) currencies:** the mirror may be stale (another server can write the shared
  balance), so sync reads **read through to the DB** (bounded) and writes persist authoritatively;
  do not trust the mirror. (When Redis sync ships, mirror + invalidate instead.)
- Offline UUID via sync API → bounded blocking DB call + warning log.
- The mirror is keyed by `(AccountId, CurrencyCode)`; scope resolution happens in the persistence layer,
  not the mirror.

## Acceptance / tests
- **v2** adapter unit-tested with a fake `EconomyService`: response mapping for success + each error;
  shared-account methods return NOT_IMPLEMENTED; flags correct.
- **Legacy v1** adapter unit-tested: `OfflinePlayer` + name resolution; `double`↔`BigDecimal` round-trip;
  bank methods return NOT_IMPLEMENTED; `hasBankSupport()=false`; name resolution never blocks on Mojang.
- **Live smoke:** a v2 Vault plugin reads/writes ≥2 currencies (transfer → correct
  `MultiEconomyResponse`); a **legacy v1** Vault plugin reads/writes the default currency.
- Both services resolvable via `ServicesManager.getRegistration(...)`.
- No main-thread DB IO for online players (verify via timing/log).
