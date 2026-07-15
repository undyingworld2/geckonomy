# Task M6 — VaultUnlocked Provider

**Goal:** Implement and register the VaultUnlocked v2 `Economy` service, backed by `EconomyService`,
with a non-blocking sync path for online players.

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

## Create (`infrastructure/bukkit`)
- `PlayerConnectionListener` — on join: async create account (seed starting balances if new) + hydrate
  mirror; on quit: evict.

## Wire (composition root)
- Register provider with `ServicesManager` at `ServicePriority.Highest`; declare Vault dependency in
  `paper-plugin.yml`. Unregister on disable.

## Sync-path rules
- Sync reads → mirror. Sync writes → update mirror immediately + dispatch authoritative async DB write
  via `EconomyService`.
- Offline UUID via sync API → bounded blocking DB call + warning log.

## Acceptance / tests
- Adapter unit-tested with a fake `EconomyService`: response mapping for success + each error;
  shared-account methods return NOT_IMPLEMENTED; flags correct.
- **Live smoke:** a real Vault-aware plugin (or a tiny test plugin) reads/writes balances in the default
  and a second currency; transfer produces a correct `MultiEconomyResponse`.
- No main-thread DB IO for online players (verify via timing/log).
