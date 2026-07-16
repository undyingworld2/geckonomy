# infrastructure — adapters

Implements `domain.port` and connects to the outside world. May use frameworks freely.

**Rule (CODING_STANDARDS.md §2):** depends on `application` + `domain`, never the reverse, and never
leaks a framework type into a signature that `domain`/`application` can see. Wiring happens in the
composition root (`Geckonomy.kt`) — no service locators, no global singletons.

**Threading (§3):** all DB IO on the `IoDispatcher`, never the Bukkit main thread; never touch the
Bukkit API off the main thread. The Vault sync adapter is the only place allowed to bridge to blocking
behaviour, and only for offline accounts (bounded + logged).

| Package | Holds |
|---|---|
| `persistence` | `DataSourceFactory`, `SqlDialect`, `SqliteDialect`, `MariaDbDialect`, `MigrationRunner`, `SqlAccountRepository`, `SqlBalanceRepository`, `SqlTransactionLog`, `SqlUnitOfWork`, `ScopeResolver`, `IoDispatcher` |
| `config` | `GeckonomyConfig`, `ConfigLoader`, `StorageConfig`, `CurrencyConfig`, `SettingsConfig` |
| `i18n` | `MessageService`, `MiniMessageRenderer`, `LanguageRepository`, `MessageKey` |
| `vault` | `VaultUnlockedEconomyProvider` (v2), `LegacyVaultEconomyProvider` (v1), `GeckonomyAsyncEconomy`, `ResponseMapper`, `LegacyResponseMapper`, `OnlineBalanceMirror`, `PlayerResolver` |
| `bukkit` | `command/*`, `listener/PlayerConnectionListener`, `BukkitMainThread` |
