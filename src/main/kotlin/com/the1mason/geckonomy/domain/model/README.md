# domain.model

Entities and value objects: `AccountId`, `Account`, `AccountType`, `Currency`, `CurrencyCode`, `Money`,
`Balance`, `Transaction`, `TransactionType`.

Immutable (`val`), invariants enforced at construction. Amounts are `BigDecimal`. See
`docs/DOMAIN_MODEL.md`.

Two things worth knowing before using these types:

- **`CurrencyCode` has a private constructor.** A value class cannot normalize its own field, so
  `CurrencyCode("Coins")` goes through a companion `invoke` that lowercases and validates
  `[a-z0-9_-]`, throwing `InvalidCurrencyCode` on a miss. For untrusted input (config, command args)
  use `CurrencyCode.parseOrNull`, which returns `null` instead.
- **`Money` does not normalize scale.** `Money(1.5, coins) != Money(1.50, coins)`, because data-class
  equality inherits `BigDecimal`'s scale-sensitive `equals`. Rounding is an explicit step
  (`Money.rounded` / `RoundingPolicy`) applied before persistence, not a hidden one at every
  intermediate sum. Compare amounts with `compareTo`.
