# application — use cases

Orchestrates domain objects through ports to fulfil use cases. Owns the async boundary: this is where
`suspend` starts.

**Rule (CODING_STANDARDS.md §2):** imports only `domain`. Never Bukkit, JDBC, or Vault — an
application type must be testable against fake ports with no server and no database.

Failures are typed, not thrown: use cases return `OperationResult`/`TransferResult` carrying either
success data or an `EconomyError`.

| Package | Holds |
|---|---|
| `service` | `EconomyService` — the facade of suspend functions |
| `usecase` | `CreateAccount`, `GetBalance`, `Has`, `Deposit`, `Withdraw`, `SetBalance`, `Transfer`, `RenameAccount`, `DeleteAccount`, `ListCurrencies`, `FormatMoney` |
| `result` | `OperationResult`, `TransferResult`, `EconomyError` (sealed) |
