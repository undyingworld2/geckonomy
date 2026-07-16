# Task M4 — Application Services

**Status: done.** The use cases and the `EconomyService` facade that orchestrate domain + ports.
Typed results. This is the async boundary, and the exception boundary.

**Read first:** `../ARCHITECTURE.md §3,§5,§6`, `../DOMAIN_MODEL.md`.

## Created (`application/result`)
- `EconomyError` (sealed): `UnknownCurrency`, `AccountNotFound`, `InsufficientFunds`, `InvalidAmount`,
  `StorageFailure`.
- `Outcome<T>` (sealed: `Success<T>` | `Failure`) with `then`/`map`. `OperationResult` and
  `TransferResult` are typealiases over it — see `application/result/README.md` for why one generic
  type rather than a hierarchy per shape.
- `Transferred` (both parties' resulting balances).
- `Attribution` — who asked; feeds `Transaction.sourcePlugin`.

## Created (`application/usecase`)
`CreateAccount`, `GetBalance`, `Has`, `Deposit`, `Withdraw`, `SetBalance`, `Transfer`, `RenameAccount`,
`DeleteAccount`, `ListCurrencies`, `FormatMoney`.

**Added beyond the original list**, because SPEC requires them and the alternative was M6 reaching
past `EconomyService` to the repositories: `AccountExists`, `FindAccountName`, `ListAccountNames`
(FR-A2/A3), `CanDeposit`, `CanWithdraw` (FR-B4).

Plus three `internal` collaborators stating the shared rules once: `StorageGuard`, `Amounts`,
`TransactionFactory`.

## Created (`application/service`)
- `EconomyService` — the suspend-fn facade. Takes `CurrencyCode` + `BigDecimal` in, returns `Money`
  out, defaults the currency and the attribution.

## Port change
- `TransactionLog.purge(id)` + `SqlTransactionLog.purge` — the ledger's one non-append operation, so
  `settings.keep-transaction-history: false` actually works rather than shipping as a dead knob.
  Covered by `RepositoryContract`.

## Implementation notes
- Validate currency (→ `UnknownCurrency`) then amount (→ `InvalidAmount`) before touching storage. In
  that order: `/pay Bob -5 coinz` is two problems and the currency is the actionable one.
- Apply `RoundingPolicy` before persistence; reject an amount that *rounds* to zero as well as one
  that starts at zero.
- Overdraft is **not** enforced on withdraw — the guard is inside `BalanceRepository.adjust`, atomic
  with the update, and `null` is its refusal. `SetBalance` is the only use case that checks
  `OverdraftPolicy` itself, because `set` has no delta for a SQL guard to work with.
- Deposit/withdraw/set append a `Transaction`, **inside a `UnitOfWork`** — a divergence from
  ARCHITECTURE §5's original bare sequence; see §5 there for why.
- **Transfer** uses `UnitOfWork.transaction { }` and aborts by throwing, never by returning a failure
  from inside the block. See `application/usecase/README.md`.
- Storage exceptions → `StorageFailure` in `StorageGuard`, stated once. Cancellation is rethrown.
- `FormatMoney` renders `Currency.format` in one regex pass and returns a `String` (no Adventure).
- Reloadable settings are injected as suppliers; `allow-overdraft` is a shared instance. See
  ARCHITECTURE §7.

## Acceptance / tests — all green
- Each use case against **fake ports** (`application/InMemoryPorts.kt`), happy path + each error. The
  fakes reproduce the real contracts they stand in for: `adjust` seeds at zero and refuses with
  `null`; a missing account fails on the foreign key.
- Transfer: success moves funds; insufficient funds fails without mutation; simulated storage failure
  rolls back. `a refusal rolls back the row adjust seeded` is the test that pins the `Abort`
  mechanism — swap the throw for a return and only it fails.
- `EconomyServiceSqliteTest` — real M3 repositories through `EconomyService` on SQLite.
- Verified enabling and disabling cleanly on a live Paper server.

## Deferred (decided, not oversights)
- **Zero amounts:** DOMAIN_MODEL §4.3 says "configurable whether zero is allowed", but no such setting
  exists in `CONFIGURATION.md` or `SettingsConfig`. Zero is rejected as `InvalidAmount`; no config
  added. Revisit if an integrator complains.
- **A currency added after accounts exist** gets no row for them, so `GetBalance` reports `0` rather
  than `starting-balance`. Forced by `adjust`'s documented "a missing row counts as zero" contract —
  the alternative has the read and the next write disagreeing. A `/geckonomy seed` backfill is the fix
  if it ever matters.
- **MariaDB deadlock** on simultaneous A→B and B→A transfers: debit-then-credit is not a consistent
  lock order. InnoDB detects it and rolls one side back, which surfaces as `StorageFailure`. Ordering
  the adjusts by ascending `AccountId` would prevent it in ~2 lines but contradicts the documented
  sequence; revisit if reported. SQLite cannot hit it (pool pinned to one connection).
- **`/baltop`** (`BalanceRepository.top`) is not exposed by `EconomyService` — M7 owns the result
  shape and is its only caller.
- **`Currency.transferable`** is M7's rule, not `Transfer`'s: DOMAIN_MODEL §1 puts those flags in the
  command layer, and an admin-initiated move must not be blocked by a player-facing rule.
