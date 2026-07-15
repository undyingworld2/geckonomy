# Task M2 — Config & Currency Registry

**Goal:** Load, validate, and type the configuration; build the in-memory `CurrencyRegistry`.

**Read first:** `../CONFIGURATION.md`, `../DOMAIN_MODEL.md` (Currency), plan §7, §13.

## Create (`infrastructure/config`)
- `StorageConfig` (type, sqlite file, mariadb host/port/db/user/pass, properties, pool sizes).
- `CurrencyConfig` (one per currency entry) and mapping to `domain.model.Currency`.
- `SettingsConfig` (language, allow-overdraft, rounding-mode, keep-transaction-history, baltop-size).
- `GeckonomyConfig` aggregate + `ConfigLoader` that reads `config.yml`, validates, and produces typed
  objects.
- Default `resources/config.yml` matching `CONFIGURATION.md`.

## Create (`infrastructure/config` or `domain` impl)
- `ConfigCurrencyRegistry : CurrencyRegistry` — holds currencies, exposes `all()`, `default()`,
  `byCode()`. Rebuilt on reload.

## Validation (fail fast → disable plugin)
- `storage.type` valid + required fields present.
- currencies non-empty; **exactly one** default; unique well-formed codes; `fractional-digits ≥ 0`.
- `rounding-mode` parses to `RoundingMode`.
- Clear, actionable error messages; on failure, log and disable.

## Reload
- Provide a `reload()` entry that re-reads config + rebuilds the registry. Warn (don't apply) on storage
  connection changes. (Command wiring lands in M7; expose the callable now.)

## Library
- Recommended: Configurate (`configurate-yaml`); fallback Bukkit `YamlConfiguration`. Whichever, the rest
  of the code sees only the typed objects.

## Acceptance / tests
- Valid config → correct typed objects + registry with right default.
- Each validation rule has a failing-config test asserting rejection.
- Reload rebuilds the registry with new currencies.
