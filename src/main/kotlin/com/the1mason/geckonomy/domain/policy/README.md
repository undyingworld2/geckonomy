# domain.policy

Business rules that are not owned by a single entity: `RoundingPolicy`, `OverdraftPolicy`,
`CurrencyValidation`.

Pure functions over model types — no IO, no clock, no randomness (inject a `Clock` if time is ever
needed).

Each holds the one config value its rule depends on (`settings.rounding-mode`,
`settings.allow-overdraft`), so no call site has to remember to pass it and none can quietly pick a
different one.

`CurrencyValidation.resolve` returns the sealed `CurrencyResolution` (`Resolved` | `Unknown`) rather
than a nullable `Currency`: an unknown code is routine — a typo'd `/pay`, a Vault caller naming a
currency this server lacks — so it is data to handle, not an error to raise. The application layer
maps `Unknown` → `EconomyError.UnknownCurrency`; domain cannot name that type, as it lives a layer out.
