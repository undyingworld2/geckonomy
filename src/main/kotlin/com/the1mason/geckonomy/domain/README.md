# domain — the innermost layer

Pure Kotlin. The model, and the ports it needs.

**Rule (CODING_STANDARDS.md §2):** imports nothing from `application` or `infrastructure`, and no
Bukkit, JDBC, Vault, or config libraries. If a type in here needs a framework, the design is wrong —
define a port instead and let `infrastructure` implement it.

Money is always `BigDecimal`, never `Double`/`Float`.

| Package | Holds |
|---|---|
| `model` | `AccountId`, `Account`, `AccountType`, `Currency`, `CurrencyScope`, `CurrencyCode`, `Money`, `Balance`, `Transaction`, `TransactionType` |
| `policy` | `RoundingPolicy`, `OverdraftPolicy`, `CurrencyValidation`, `CurrencyResolution` |
| `port` | `AccountRepository`, `BalanceRepository`, `TransactionLog`, `CurrencyRegistry`, `UnitOfWork` (+ `TxContext`) |
| *(root)* | `DomainException` — sealed base, with `CurrencyMismatch` and `InvalidCurrencyCode` |

`DomainException` sits in the root package rather than a sub-package because it spans all three:
exceptions here mean a **broken invariant** (a caller bug), never an expected failure. Expected
failures are typed results instead (CODING_STANDARDS.md §4) — which is why an unknown currency code
is `CurrencyResolution.Unknown` and not a throw.

Tested with plain JUnit 5 — no server, no DB.
