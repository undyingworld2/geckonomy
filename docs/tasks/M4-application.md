# Task M4 — Application Services

**Goal:** The use cases and the `EconomyService` facade that orchestrate domain + ports. Typed results.
This is the async boundary.

**Read first:** `../ARCHITECTURE.md §3,§5,§6`, `../DOMAIN_MODEL.md`.

## Create (`application/result`)
- `EconomyError` (sealed): `UnknownCurrency`, `AccountNotFound`, `InsufficientFunds`, `InvalidAmount`,
  `StorageFailure`.
- `OperationResult` (success: resulting balance / failure: `EconomyError`).
- `TransferResult` (both parties' resulting balances / failure).

## Create (`application/usecase`) — each a small class taking ports via constructor
`CreateAccount`, `GetBalance`, `Has`, `Deposit`, `Withdraw`, `SetBalance`, `Transfer`, `RenameAccount`,
`DeleteAccount`, `ListCurrencies`, `FormatMoney`.

## Create (`application/service`)
- `EconomyService` — suspend-fn facade delegating to the use cases; the single entry point used by the
  Vault adapter and commands.

## Implementation notes
- Validate currency (→ `UnknownCurrency`) and amount (→ `InvalidAmount`) before touching storage.
- Apply `RoundingPolicy` before persistence; enforce `OverdraftPolicy` on withdraw.
- Deposit/withdraw/set append a `Transaction` via `TransactionLog`.
- **Transfer** uses `UnitOfWork.transaction { }` for atomic debit + credit + two ledger rows; maps a
  rollback/insufficient-funds to the right `EconomyError`.
- Catch storage exceptions at this boundary → `StorageFailure` (log with context). No exception escapes
  to callers.
- `FormatMoney` produces the display string per `Currency.format` (shared with M5 renderer).

## Acceptance / tests
- Each use case tested against **fake ports** (happy path + each error).
- Transfer: success moves funds; insufficient funds fails without mutation; simulated storage failure
  rolls back.
- Integration test wiring real M3 repositories through `EconomyService` on SQLite.
