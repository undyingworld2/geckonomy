# domain.port

The interfaces the domain needs the outside world to satisfy: `AccountRepository`,
`BalanceRepository`, `TransactionLog`, `CurrencyRegistry`, `UnitOfWork`.

Defined here because this is where they are **used**; implemented in `infrastructure`, where the tech
lives. Signatures are `suspend` so implementations can do async IO, and speak only in domain types —
a port that mentions a `Connection` or a `Player` is a bug.

`BalanceRepository` takes the full `Currency` (not just its code) so the implementation can resolve the
scope key without domain ever learning that server ids exist. Signatures in `docs/ARCHITECTURE.md §3`.

`CurrencyRegistry` is the one port whose functions are **not** `suspend`: it is backed by config held
in memory, so there is no IO to do, and forcing callers into a coroutine for a map lookup would
misrepresent the cost.

`UnitOfWork.transaction` hands the block a `TxContext` (`accounts`, `balance`, `log`) — the same ports,
but bound to the enclosing transaction. Ports captured from *outside* the block do not participate in
it; that distinction is the whole reason the type exists.

Since M13, `transaction(writes = …)` also names the accounts the block may change, so the SQL store can
queue our own writers to one account instead of letting them contend for its row lock — and the block
**may run more than once**, because a transaction lost to a lock conflict is retried. Anything it does
outside its `TxContext` would happen once per attempt. `BalanceRepository.lock(rows)` takes several
rows' write locks in one global order, whatever order they are named in, which is what keeps opposite
transfers on two servers from deadlocking; `getAll` reads many accounts in one query.
