# Task M2 — Config & Currency Registry

**Goal:** Load, validate, and type the configuration; build the in-memory `CurrencyRegistry`.

**Read first:** `../CONFIGURATION.md`, `../DOMAIN_MODEL.md` (Currency), plan §7, §13.

## Create (`infrastructure/config`)
- `StorageConfig` (type, sqlite file, mariadb host/port/db/user/pass, properties, pool sizes).
- Mapping of each currency entry to `domain.model.Currency` — including `scope`
  (`network|server` → `CurrencyScope`), `transferable`, `balance-check-others`, `show-in-baltop`.
- `SettingsConfig` (**server-id**, language, allow-overdraft, rounding-mode, keep-transaction-history,
  baltop-size).
- `GeckonomyConfig` aggregate + `ConfigLoader` that reads `config.yml`, validates, and produces typed
  objects.
- Default `resources/config.yml` matching `CONFIGURATION.md`.

**Built without a `CurrencyConfig` DTO** (deviation from this doc's original file list). Its fields
would have been a copy of `domain.model.Currency`'s, one for one, with nothing renamed, dropped, or
reshaped — a second class to keep in sync by hand for no gain. `ConfigLoader` maps each entry straight
to the domain type; layering is unaffected, since config imports domain and never the reverse. If
config keys and domain fields ever diverge, that is when the DTO earns its place.

## Create (`infrastructure/config` or `domain` impl)
- `ConfigCurrencyRegistry : CurrencyRegistry` — holds currencies, exposes `all()`, `default()`,
  `byCode()`. Rebuilt on reload.

## Validation (fail fast → disable plugin)
- `storage.type` valid + required fields present.
- currencies non-empty; **exactly one** default; unique well-formed codes;
  `0 ≤ fractional-digits ≤ 10` (the stored scale — DATA_MODEL.md §3; an eleventh decimal would be
  truncated on write).
- `scope` ∈ {`network`, `server`}; `server-id` non-empty (warn if left default with a network currency).
- `rounding-mode` parses to `RoundingMode`.
- Clear, actionable error messages; on failure, log and disable.

## Reload
- Provide a `reload()` entry that re-reads config + rebuilds the registry. Warn (don't apply) on storage
  connection changes. (Command wiring lands in M7; expose the callable now.)

## Library
- **Decided: Bukkit `YamlConfiguration`** (`loadFromString`) — no runtime dependency, and Configurate's
  comment-preserving writes and `ObjectMapper` typing both go unused here. Rationale in
  `../CONFIGURATION.md §5`. The rest of the code sees only the typed objects.

## Acceptance / tests
- Valid config → correct typed objects + registry with right default.
- Each validation rule has a failing-config test asserting rejection.
- Reload rebuilds the registry with new currencies.
