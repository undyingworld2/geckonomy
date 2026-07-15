# Geckonomy — Domain Model

The domain layer is pure Kotlin: no Bukkit, JDBC, Vault, or config types. It defines the model, its
invariants, and the ports the model needs. Everything here is unit-testable without a server.

## 1. Value objects

### `AccountId`
Wraps a `java.util.UUID`. Identity of an account (matches Vault's UUID keying). Immutable; equality by
UUID.
```kotlin
@JvmInline value class AccountId(val value: UUID)
```

### `CurrencyCode`
Stable machine identifier of a currency (e.g. `coins`, `gems`). Case-insensitive; normalized to
lowercase on construction. Distinct from the display name.
```kotlin
@JvmInline value class CurrencyCode(val value: String) // normalized lowercase, [a-z0-9_-]
```

### `Currency`
Definition of a currency (loaded from config, held by `CurrencyRegistry`).
```kotlin
data class Currency(
    val code: CurrencyCode,
    val singular: String,       // "Coin"
    val plural: String,         // "Coins"
    val symbol: String,         // "$"
    val fractionalDigits: Int,  // 2  (0 = whole units only)
    val startingBalance: BigDecimal,
    val isDefault: Boolean,
    val format: String          // display template, see LOCALIZATION.md
)
```

### `Money`
An amount bound to a currency. All monetary arithmetic goes through here.
```kotlin
data class Money(val amount: BigDecimal, val currency: Currency) {
    operator fun plus(other: Money): Money   // requires same currency, else DomainException
    operator fun minus(other: Money): Money
    fun isNegative(): Boolean
    fun rounded(mode: RoundingMode): Money    // to currency.fractionalDigits
}
```
Invariants:
- Arithmetic between different currencies throws `CurrencyMismatch` (a domain exception, never leaks to
  Bukkit — caught and mapped in application).
- `rounded` scales to `currency.fractionalDigits`.

## 2. Entities

### `Account`
```kotlin
data class Account(
    val id: AccountId,
    val name: String,
    val type: AccountType,       // PLAYER now; SHARED reserved
    val createdAt: Instant
)
enum class AccountType { PLAYER, SHARED }   // SHARED unused in v1
```

### `Balance`
Association of an account, a currency, and an amount. Typically materialized from the repository rather
than constructed directly.
```kotlin
data class Balance(val accountId: AccountId, val currency: CurrencyCode, val amount: BigDecimal)
```

### `Transaction` (ledger entry — immutable)
```kotlin
data class Transaction(
    val id: UUID,
    val accountId: AccountId,
    val currency: CurrencyCode,
    val delta: BigDecimal,           // signed
    val resultingBalance: BigDecimal,
    val type: TransactionType,
    val sourcePlugin: String?,       // Vault pluginName, or "geckonomy"
    val counterparty: AccountId?,    // for transfers
    val createdAt: Instant
)
enum class TransactionType { DEPOSIT, WITHDRAW, SET, TRANSFER_IN, TRANSFER_OUT }
```

## 3. Policies

Small, pure strategy objects so behavior is explicit and testable.

- **`RoundingPolicy`** — rounds a `BigDecimal` to a currency's fractional digits using a configured
  `RoundingMode` (default `HALF_UP`). Applied before any persistence.
- **`OverdraftPolicy`** — decides whether a withdrawal that would drop below zero is allowed. Default:
  reject. When `allow-overdraft` is on: permit.
- **`CurrencyValidation`** — resolves a `CurrencyCode` to a `Currency` via the registry; unknown code →
  typed failure (mapped by application to `EconomyError.UnknownCurrency`).

## 4. Invariants (enforced in domain / application)

1. Balance ≥ 0 unless overdraft is enabled.
2. Amounts are non-null `BigDecimal`, rounded to the currency's fractional digits before persistence.
3. A negative or zero amount to deposit/withdraw is an `InvalidAmount` failure (configurable whether
   zero is allowed).
4. Exactly one default currency exists (validated at config load, surfaced here as a registry
   guarantee).
5. Cross-currency arithmetic is impossible (`Money` guards it).
6. Ledger rows are append-only; never updated or deleted by normal operations.

## 5. Ports needed by the domain/application

Defined in `domain.port`, implemented in `infrastructure` (see `ARCHITECTURE.md §3`):
`AccountRepository`, `BalanceRepository`, `TransactionLog`, `CurrencyRegistry`, `UnitOfWork`.

## 6. Notes for reserved features

- `AccountType.SHARED` and the `counterparty`/ownership concepts are placeholders so shared/bank
  accounts slot in without reshaping `Account`.
- No `world` dimension in the model — global balances by decision. If per-world lands later it becomes a
  new coordinate on `Balance` and the repositories, leaving `Money`/`Currency` untouched.
