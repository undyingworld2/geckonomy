# Task M13 — Balance write path: contention-safe, write-through, never guessing

**Goal:** No write is lost to lock contention, on one server or several sharing one MariaDB, and no
caller is told a write failed when it committed.

**Read first:** `../DATA_MODEL.md §4`, `../ARCHITECTURE.md §4–§5`, `../SPEC.md §5 (NFR-2, NFR-9)`.

## Background: the incident

Production (MariaDB, several servers) lost deposits to `1213 Deadlock found when trying to get lock`.
`SHOW ENGINE INNODB STATUS` showed two concurrent deposits of `coins` (6000, 3000) to one player. Each
transaction held an **S** lock on the `gk_balance` row and waited for the **X** lock its `UPDATE`
needed. InnoDB rolled one back, `StorageGuard` reported it as a storage failure, and that deposit was
gone.

The S lock came from M2's `adjust`: `INSERT IGNORE` (seed) → `UPDATE` → `SELECT`. An ignored duplicate
on InnoDB takes a shared lock on the existing row, and the update then has to upgrade it. Two writers to
one row deadlock every time they overlap. This was not caused by M12, whose bonus leg was not configured
there. It surfaced under burst deliveries, and M12's two-row deposits would only have made it more
likely.

Found alongside it:
- Nothing retried a conflict, so the losing deposit was simply dropped.
- `VaultSyncPath` cancelled a write at its 2s timeout, but JDBC is not interruptible. The write could
  still commit, and the caller heard FAILURE for money that moved. A delivery plugin that retries
  would then pay twice.
- Transfers locked payer then payee, which is a textbook AB/BA deadlock across servers.
- The async Vault API never updated the mirror.

## Design (as built)

### 1. Lock first, never upgrade (`SqlBalanceRepository`)
- `adjust` is `SELECT … FOR UPDATE` (X on first touch), then the guarded
  `UPDATE amount = amount + ?`. The new balance is locked + delta, so the read-back is gone.
- A missing row is created by a plain `INSERT` in the same transaction. If a concurrent writer created
  it first, that insert waits, fails on the duplicate key, and becomes a `WriteConflict` → retry.
- New port method `BalanceRepository.lock(rows)`: takes several rows' X locks **in one global order**
  (account id as unsigned big-endian, then currency). `Transfer` locks both legs, `Deposit` with a
  bonus locks both currencies, and `SetBalance` locks before reading its `previous`.
- MariaDB pool runs **READ COMMITTED** (`DataSourceFactory`). Under REPEATABLE READ a locking read that
  finds no row takes a gap lock, and two such transactions deadlock on their inserts.
- Dialect: `lockingRead` (`" FOR UPDATE"` / `""`) and `isConstraintViolation`.

### 2. One writer per account per server (`AccountWriteGate`)
`UnitOfWork.transaction(writes = setOf(…))` names the accounts a transaction changes. `SqlUnitOfWork`
holds a striped (256) mutex per account while it runs, so a burst of deliveries to one player waits in
memory, not on the row lock with a pooled connection held. Multi-account ops take stripes in ascending
order. This lives in infrastructure, not application: it is a property of the SQL store, and the
in-memory fakes need none.

### 3. Retry what certainly rolled back, settle what might not have (`SqlUnitOfWork`)
- **Retry** (`isWriteConflict`): `WriteConflict`, SQLState `40001` (deadlock victim) and MariaDB 1205
  (lock wait timeout). Each is rolled back in full, then the block runs again, up to
  `MAX_WRITE_ATTEMPTS` = 3 attempts with jittered backoff. The port now documents that **a block may
  run more than once**, so it must touch only its `TxContext`.
- **Commit verification:** a `commit()` that throws has an unknown outcome. The transaction's first
  ledger row id is its marker. Once the failed connection is back in the pool (SQLite's pool holds one),
  the marker is looked up: present means it committed and the result stands; absent means it rolled
  back and is retried. A transaction with no ledger row has nothing to look up and reports the failure.
- Anything else (a broken disk, a bad statement) is reported, not retried.

> Plan deviation: the plan proposed op ids derived into ledger ids plus a start-of-attempt lookup. Since
> only definitely-rolled-back conflicts are retried, the only ambiguous case is a failed commit, and the
> ledger row the transaction already wrote identifies it exactly. So no id scheme or migration is
> needed.

### 4. The timeout bounds the wait, not the write (`VaultSyncPath`)
Sync Vault writes run on the plugin scope. The main thread waits up to the timeout, and on expiry gets
FAILURE (Vault has no "pending"), but **the write keeps running**. It mirrors its committed balance and
logs `… COMMITTED after its caller was told it failed` (or that it did not apply), so the operator can
reconcile the "gave up" line.

### 5. Mirror: read-only for Vault, written by every write, re-synced from the store
- `GeckonomyAsyncEconomy` now mirrors set/withdraw/deposit (+bonus)/transfer results, as the sync
  path, `/eco` and `/pay` already did.
- `MirrorRefresher` re-reads all online players **in one query per currency**
  (`BalanceRepository.getAll` → `GetBalance.many` → `EconomyService.balances`), keeping the
  compare-and-set rule. Redis invalidation stays post-v1.

## Acceptance / tests
- `MariaDbConcurrencyTest` (two harnesses = two servers on one MariaDB). It was **red on the M12 code**
  (234 deadlocks across the scenarios) and is green now. **InnoDB's `Innodb_deadlocks` counter is
  unchanged** across each test, which proves the deadlocks are gone rather than retried away.
  - 2 × 50 concurrent deposits to one account: all land, exact sum, 100 ledger rows.
  - Concurrent first deposits racing to create the row: all land.
  - Opposite-direction transfers from both servers: all succeed, money conserved.
  - 150 withdrawals of 1.00 against 100.00: exactly 50 `InsufficientFunds`, balance 0.00.
- `SqlUnitOfWorkRetryTest` (SQLite + fault-injecting `DataSource`): deadlock victim retried once; a
  commit that happened but threw is not re-applied; a commit that did not happen is retried; a
  non-conflict error is not retried; conflicts past the limit are reported.
- `VaultSyncPathTest`: a write slower than the timeout reports FAILURE, still commits, mirrors, and logs
  `COMMITTED`.
- `VaultUnlockedEconomyProviderTest`: async deposit and transfer reach the mirror.
- Full suite: 841 tests, 0 failures, 0 skipped.
- **Live (to do):** a burst of store deliveries to one player from two servers. Expect no 1213 in the log
  and `LATEST DETECTED DEADLOCK` unchanged.
