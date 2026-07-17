# infrastructure — adapters

Implements `domain.port` and connects to the outside world. May use frameworks freely.

**Rule (CODING_STANDARDS.md §2):** depends on `application` + `domain`, never the reverse, and never
leaks a framework type into a signature that `domain`/`application` can see. Wiring happens in the
composition root (`Geckonomy.kt`) — no service locators, no global singletons.

**Threading (§3):** all DB IO on the `IoDispatcher`, never the Bukkit main thread; never touch the
Bukkit API off the main thread. The Vault sync adapter is the only place allowed to bridge to blocking
behaviour, and only for offline accounts (bounded + logged). The placeholder adapter is stricter still
and blocks for nothing at all (SPEC.md FR-P7).

| Package | Holds |
|---|---|
| `persistence` | `DataSourceFactory`, `SqlDialect`, `SqliteDialect`, `MariaDbDialect`, `MigrationRunner`, `SqlAccountRepository`, `SqlBalanceRepository`, `SqlTransactionLog`, `SqlUnitOfWork`, `ScopeResolver`, `IoDispatcher` |
| `config` | `GeckonomyConfig`, `ConfigLoader`, `StorageConfig`, `CurrencyConfig`, `SettingsConfig`, `PlaceholderConfig` |
| `i18n` | `MessageService`, `MiniMessageRenderer`, `LanguageRepository`, `MessageKey`, `Placeholders` (MiniMessage tags — unrelated to `placeholder/` below) |
| `balance` | `OnlineBalanceMirror`, `OfflineBalanceCache` — synchronous-read state shared by the `vault` and `placeholder` adapters, which is why it belongs to neither |
| `vault` | `VaultUnlockedEconomyProvider` (v2), `LegacyVaultEconomyProvider` (v1), `GeckonomyAsyncEconomy`, `VaultRegistration`, `VaultSyncPath`, `ResponseMapper`, `LegacyResponseMapper`, `PlayerResolver` |
| `placeholder` | `GeckonomyExpansion` (the only class naming a PAPI type), `PlaceholderRegistration`, `PlaceholderResolver`, `PlaceholderVariant`, `BaltopSnapshot` |
| `bukkit` | `command/*`, `listener/PlayerConnectionListener`, `BukkitMainThread`, `CurrencyAccess`, `PlayerTargets` |
