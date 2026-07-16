# application.result

`OperationResult`, `TransferResult`, and the sealed `EconomyError` (`UnknownCurrency`,
`AccountNotFound`, `InsufficientFunds`, `InvalidAmount`, `StorageFailure`).

Cross-layer failures travel as these types, not exceptions — sealed so adapters get an exhaustive
`when` and a new error variant fails the build rather than slipping through as a generic failure.

Arrives with **M4**.
