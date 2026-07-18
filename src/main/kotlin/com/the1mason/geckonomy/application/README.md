# application — use cases

Orchestrates domain objects through ports to fulfil use cases. Owns the async boundary: this is where
`suspend` starts.

**Rule (CODING_STANDARDS.md §2):** imports only `domain`. Never Bukkit, JDBC, or Vault — an
application type must be testable against fake ports with no server and no database.

Failures are typed, not thrown: use cases return `OperationResult`/`TransferResult` carrying either
success data or an `EconomyError`. This is also where thrown things *stop* — `StorageGuard` is the
boundary that `MoneyOutOfRange` and `LedgerFailure` promise in their KDoc, and no exception passes it
except `CancellationException`, which must.

| Package | Holds |
|---|---|
| `service` | `EconomyService` — the facade of suspend functions |
| `usecase` | `CreateAccount`, `GetBalance`, `Has`, `Deposit`, `Withdraw`, `SetBalance`, `Transfer`, `RenameAccount`, `DeleteAccount`, `ListCurrencies`, `AccountExists`, `FindAccountName`, `ListAccountNames`, `CanDeposit`, `CanWithdraw`; `StorageGuard`, `Amounts`, `TransactionFactory` (internal) |
| `result` | `Outcome` (sealed), `OperationResult`, `TransferResult`, `Transferred`, `EconomyError` (sealed) |
| — | `Attribution` — who asked for a change |

The layering rule is worth stating concretely, because it is easy to breach by reflex: the only
non-`domain` imports here are JDK types (`java.math`, `java.time`, `java.text`, `java.util.logging`)
and `kotlin.coroutines`. `FormatMoney` moved to `infrastructure.i18n` at **M10** for exactly this
reason, once it started rendering an Adventure `Component` — a framework type this layer may not hold.

Arrived with **M4**.
