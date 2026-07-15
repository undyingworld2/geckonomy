# Task M1 — Domain Core

**Goal:** The pure domain: value objects, entities, policies, and port interfaces. Zero framework
dependencies. Fully unit-tested.

**Read first:** `../DOMAIN_MODEL.md`, `../ARCHITECTURE.md §3`, `../CODING_STANDARDS.md`.

## Create (`domain/model`)
- `AccountId` (value class over `UUID`).
- `CurrencyCode` (value class, normalized lowercase, validated `[a-z0-9_-]`).
- `Currency` (data class per `DOMAIN_MODEL.md`).
- `Money` (data class: `amount` + `Currency`; `plus`/`minus` with same-currency guard; `isNegative`;
  `rounded(mode)`).
- `Account` + `AccountType { PLAYER, SHARED }`.
- `Balance`.
- `Transaction` + `TransactionType { DEPOSIT, WITHDRAW, SET, TRANSFER_IN, TRANSFER_OUT }`.

## Create (`domain/policy`)
- `RoundingPolicy` — round `BigDecimal`/`Money` to currency digits with a `RoundingMode`.
- `OverdraftPolicy` — allow/deny sub-zero results based on a flag.
- `CurrencyValidation` — resolve code → currency via `CurrencyRegistry`; typed miss.

## Create (`domain/port`)
Interfaces exactly as in `ARCHITECTURE.md §3`: `AccountRepository`, `BalanceRepository`,
`TransactionLog`, `CurrencyRegistry`, `UnitOfWork` (+ `TxContext`).

## Implementation notes
- `Money` arithmetic across differing currencies throws a domain exception (`CurrencyMismatch`) — caught
  in application, never surfaced to Bukkit.
- Keep everything immutable. No `var`. No Bukkit/JDBC imports anywhere in `domain`.

## Acceptance / tests
- Unit tests for: `Money` add/sub/round, cross-currency guard, `CurrencyCode` normalization/validation,
  `RoundingPolicy` (0 and N digits, HALF_UP + one other mode), `OverdraftPolicy` both directions.
- Tests compile and run with **no Bukkit/JDBC on the classpath** (verify by not importing them).
- 100% of listed types exist with the documented signatures.
