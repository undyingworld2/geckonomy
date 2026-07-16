# infrastructure.vault

`VaultUnlockedEconomyProvider` (v2), `LegacyVaultEconomyProvider` (v1), `GeckonomyAsyncEconomy`,
`ResponseMapper`, `LegacyResponseMapper`, `OnlineBalanceMirror`, `PlayerResolver`.

Both providers register with the `ServicesManager` from the first release: v2 multi-currency, and
legacy v1 mapped onto the default currency (`double` ↔ `BigDecimal`, banks NOT_IMPLEMENTED).

This is the one place bridging Vault's **synchronous** API to our async core. `OnlineBalanceMirror`
serves main-thread reads for online players; offline accounts fall back to a bounded, logged blocking
read. The mirror is only trusted for `SERVER`-scoped currencies — `NETWORK` ones read through to the
DB, since another server sharing the DB could have changed them (until Redis sync ships).

No exception may escape into a Vault caller: map everything through the response mappers. Method-by-method
mapping in `docs/VAULT_INTEGRATION.md`. Arrives with **M6**.
