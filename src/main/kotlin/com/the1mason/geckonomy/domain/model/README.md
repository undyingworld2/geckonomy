# domain.model

Entities and value objects: `AccountId`, `Account`, `AccountType`, `Currency`, `BonusRule`, `CurrencyCode`,
`Money`, `Balance`, `Transaction`, `TransactionType`.

`Currency.bonus: BonusRule?` (M12) pairs a currency with another that every deposit also credits, at
`ratio`; `byDefault` decides for a caller that does not say. The bonus leg is ledgered as
`TransactionType.BONUS` — the `type` column is free text, so it needed no migration.

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
