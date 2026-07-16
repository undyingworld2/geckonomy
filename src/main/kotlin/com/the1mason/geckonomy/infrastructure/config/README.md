# infrastructure.config

`GeckonomyConfig`, `ConfigLoader`, `ConfigService`, `ConfigCurrencyRegistry`, `StorageConfig`,
`SettingsConfig` (incl. `server-id`).

Parses `config.yml` into typed objects and validates it. Invalid config disables the plugin with a
clear error rather than starting in a half-configured state. Builds the `CurrencyRegistry` that
`domain.port` declares. Schema in `docs/CONFIGURATION.md`. Arrived with **M2**.

- `ConfigLoader` — text in, `ConfigLoad` (typed config + warnings, or every error at once) out.
  Stateless and IO-free, so it tests without a server. Parsing is Bukkit's `YamlConfiguration`
  (`CONFIGURATION.md §5` for why).
- `ConfigService` — owns the file, the current `GeckonomyConfig`, and `reload()` for M7's
  `/geckonomy reload`. A rejected reload keeps the running config untouched.
- `ConfigCurrencyRegistry` — a stable object whose immutable currency snapshot is swapped on reload,
  so ports injected once at wiring see the new currencies.
- No `CurrencyConfig` DTO: entries map straight to `domain.model.Currency` (`docs/tasks/M2` for why).
