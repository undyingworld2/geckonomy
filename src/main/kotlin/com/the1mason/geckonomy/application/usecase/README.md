# application.usecase

One class per operation, verb-first, each taking its ports via the constructor and tested against
fakes: `CreateAccount`, `GetBalance`, `Has`, `Deposit`, `Withdraw`, `SetBalance`, `Transfer`,
`RenameAccount`, `DeleteAccount`, `ListCurrencies` — plus `AccountExists`,
`FindAccountName`, `ListAccountNames` (SPEC FR-A2/A3) and `CanDeposit`, `CanWithdraw` (FR-B4), which
M4's original list omitted. Without them M6's Vault adapter would have to reach past `EconomyService`
to the repositories, and `EconomyService` would stop being the single entry point.

(`FormatMoney` lived here through M9; it moved to `infrastructure.i18n` at **M10**, once rendering
`Money` meant producing an Adventure `Component` — a framework type this layer may not hold.)

Three `internal` collaborators state the repeated rules once, which is why the use cases are as short
as they are:

- **`StorageGuard`** — the exception boundary (ARCHITECTURE.md §6). Its catch *order* is the design:
  `CancellationException` is caught first and **rethrown**, because it is an `IllegalStateException`
  that a bare `catch (Exception)` would eat, and `SqlUnitOfWork`'s rollback depends on it propagating.
  `DomainException` is caught next and logged at SEVERE — our bug, not a sick database.
- **`Amounts`** — resolve the currency, judge the amount, round it. Takes a `() -> RoundingPolicy`
  **supplier**: `settings.rounding-mode` is reloadable, and capturing it would make `/geckonomy
  reload` report success and change nothing. `DeleteAccount` takes `keep-transaction-history` the same
  way, for the same reason.
- **`TransactionFactory`** — ledger rows, with the `Clock` and the id source injected so tests assert
  a whole row instead of picking around two unpredictable fields.

`Transfer` is the one that must run inside `UnitOfWork.transaction { }`, and the one subtlety worth
knowing before editing anything here: it aborts by **throwing** `Abort`, never by returning a
`Failure` from inside the block. `SqlUnitOfWork` commits whatever the block returns and rolls back
only on a throwable, so a returned failure would commit the debit — the exact money-destroying bug the
transaction exists to prevent. `TransferTest.a refusal rolls back the row adjust seeded` is the test
that catches the substitution.

`Deposit`, `Withdraw`, and `SetBalance` also run in a transaction, which ARCHITECTURE.md §5's sequence
did not originally show: a balance change whose ledger row fails to write makes FR-B7 silently false.
They need no `Abort`, because a refusal there has nothing written to undo. Only `Transfer` does.

Arrived with **M4**.
