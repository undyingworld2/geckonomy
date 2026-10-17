# application.result

`OperationResult`, `TransferResult`, `DepositResult`, and the sealed `EconomyError` (`UnknownCurrency`,
`AccountNotFound`, `InsufficientFunds`, `InvalidAmount`, `NoBonusCurrency`, `StorageFailure`).

Cross-layer failures travel as these types, not exceptions — sealed so adapters get an exhaustive
`when` and a new error variant fails the build rather than slipping through as a generic failure.

All of it is one generic `Outcome<T>` (`Success<T>` | `Failure`), with `OperationResult` and
`TransferResult` as typealiases over it. The use cases answer with six shapes between them — a
balance, two balances, a boolean, a name, a name map, nothing — and a sealed pair per shape would be
six near-identical hierarchies. Decisively, one shape is what lets `StorageGuard` exist: a helper that
wraps every port call can only be written once if there is a single type for it to return. `then` and
`map` keep the use-case bodies flat.

`DepositResult` (M12) is `Outcome<Deposited>` rather than a bare balance: `Deposited.bonus` is the
`BonusCredit` (amount and the bonus currency's new balance) that rode along, or `null` when none was
credited. The sync Vault path needs that second balance to keep the mirror honest, and `/eco give`
needs the amount for `admin.given-bonus`. `NoBonusCurrency` is a caller asking for a bonus explicitly
on a currency that has none — refused before any write.

`EconomyError` carries only what a message needs. `InsufficientFunds` holds the *required* amount and
not the available balance, because `lang/en.yml`'s `pay.insufficient` renders only the former and
reading the latter would cost the extra query that `BalanceRepository.adjust`'s typed `null` refusal
exists to avoid. `StorageFailure.cause` is an exception's *message*, never the exception — the guard
already logged the trace, and an adapter must not be able to render a stack trace at a player.

Arrived with **M4**.
