# infrastructure.vault

`VaultUnlockedEconomyProvider` (v2), `LegacyVaultEconomyProvider` (v1), `GeckonomyAsyncEconomy`,
`ResponseMapper`, `LegacyResponseMapper`, `VaultSyncPath`, `PlayerResolver`, `VaultRegistration`,
`EconomyClaim`.

`OnlineBalanceMirror` lives in `infrastructure.balance` — M9's placeholder adapter reads it too, so
it belongs to neither adapter.

Both providers register with the `ServicesManager` at `Highest`: v2 multi-currency, and legacy v1
mapped onto the default currency (`double` ↔ `BigDecimal`, banks NOT_IMPLEMENTED).

This is the one place bridging Vault's **synchronous** API to our async core. `VaultSyncPath` is the
only class that knows the rules, and both providers go through it:

- **Reads answer from the mirror**, never through to the database. For a `NETWORK` currency on
  **MariaDB** an async refresh is scheduled *behind* the read so the mirror converges; on **SQLite no
  refresh fires at all**, because a SQLite file cannot be shared between servers, so a network
  currency there has exactly one writer and cannot go stale. An account that is not mirrored (offline,
  or not yet hydrated) falls back to a bounded, logged blocking read.
- **Writes await the use case** under a bounded timeout, then put the *authoritative* returned balance
  into the mirror. Deciding in the adapter and dispatching the write asynchronously would mean
  re-implementing currency resolution, `Amounts.positive`, the reloadable rounding mode and
  `OverdraftPolicy` — and could still report SUCCESS for a write the database later refuses.

`VaultRegistration` is the only class naming a Vault type at wiring time: VaultUnlocked is a soft
dependency, so the composition root checks for the plugin before anything loads it.

v2's `default` methods are traps — `transfer` is a non-atomic withdraw-then-deposit, `set` is
read-then-adjust, `canWithdraw`/`canDeposit` return NOT_IMPLEMENTED. `VaultDefaultsTest` pins that we
override them.

No exception may escape into a Vault caller: map everything through the response mappers.
Method-by-method mapping in `docs/VAULT_INTEGRATION.md`.
