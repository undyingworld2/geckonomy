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
  **MariaDB** an async refresh is scheduled *behind* the read so the mirror converges. Separately,
  `balance.MirrorRefresher` re-reads every online player every `settings.mirror-refresh-seconds`,
  catching changes no write path of ours reported (another server, a manual SQL fix). An account that
  is not mirrored (offline, or not yet hydrated) falls back to a bounded, logged blocking read.
- **Writes go through the use case**, then put the *authoritative* returned balance into the mirror —
  for a deposit, the bonus currency's too (M12). The main thread waits a bounded time, but **the
  timeout bounds the wait, never the write** (M13): a timed-out caller hears FAILURE, while the write
  finishes, mirrors what it committed and logs `… COMMITTED after its caller was told it failed`. Deciding in the adapter and dispatching the write asynchronously would mean
  re-implementing currency resolution, `Amounts.positive`, the reloadable rounding mode and
  `OverdraftPolicy` — and could still report SUCCESS for a write the database later refuses.

`VaultRegistration` is the only class naming a Vault type at wiring time: VaultUnlocked is a soft
dependency, so the composition root checks for the plugin before anything loads it.

v2's `default` methods are traps — `transfer` is a non-atomic withdraw-then-deposit, `set` is
read-then-adjust, `canWithdraw`/`canDeposit` return NOT_IMPLEMENTED. `VaultDefaultsTest` pins that we
override them.

`GeckonomyAsyncEconomy` never reads the mirror but writes every committed balance into it (M13), as
every other write path does. Every Vault deposit passes `bonus = null`, so the currency's `by-default`
decides; responses describe the deposited currency only.

No exception may escape into a Vault caller: map everything through the response mappers.
Method-by-method mapping in `docs/VAULT_INTEGRATION.md`.
