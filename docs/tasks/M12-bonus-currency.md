# Task M12 — Bonus Currency

**Goal:** Let a currency earn a second one alongside it: whenever a player receives gems, they also
receive bonuses, which they then spend elsewhere like any other currency.

**Read first:** `../SPEC.md §4.3 (FR-B9, FR-B10)`, `../CONFIGURATION.md §2`, `../DOMAIN_MODEL.md §1`.

## Background — what changes and why

A server sells gems, and wants every gem bought to also count toward a loyalty balance that is spent in
a different shop. Two plugins calling `deposit` twice would not do: the second call can fail after the
first has landed, and every Vault plugin that hands out gems would need to know about the pairing. The
pairing belongs to the economy, so it lives on the currency and is applied by the one use case every
deposit already goes through.

## Config (`infrastructure/config`)

```yaml
- code: "gems"
  ...
  bonus:                 # optional
    currency: "bonuses"  # another configured currency
    ratio: 1             # bonus = amount × ratio, rounded to the bonus currency's digits
    by-default: true     # whether a caller that does not say grants it
```

- `ratio` defaults to `1`, `by-default` to `true`.
- Errors: unknown currency, a currency that is its own bonus, a bonus currency with a `bonus` of its
  own (no chains — one deposit is at most two credits, and a cycle would never end), `ratio ≤ 0`.
- Warning: a currency coded `bonus` or `nobonus` shadows the `/eco give` keyword in its short spelling
  (below). Same posture as M9's placeholder-keyword collision.
- Several currencies may share one bonus currency. The bonus currency is an ordinary currency in every
  other respect: its own scope, flags, permissions, Vault visibility.
- Applies live on `/geckonomy reload`; the registry is rebuilt wholesale.

## Domain

- `Currency.bonus: BonusRule?` — `BonusRule(currency, ratio, byDefault)`.
- `TransactionType.BONUS`. The ledger's `type` column is unconstrained text, so **no migration**.

## Application

`Deposit` takes `bonus: Boolean?` — `true`/`false` force it, `null` follows `by-default` — and returns
`DepositResult = Outcome<Deposited(balance, bonus: BonusCredit?)>`.

- The bonus is credited **in the same transaction** as the deposit, with its own `BONUS` ledger row and
  the deposit's attribution. Both commit or neither.
- The bonus currency is resolved and the amount rounded **before** the transaction opens. A bonus that
  rounds to zero is dropped, not recorded.
- `bonus = true` on a currency with no bonus is `EconomyError.NoBonusCurrency`, refused before any
  write: the caller asked for something that will not happen.
- **Only deposits.** `Withdraw`, `SetBalance` and `Transfer` are untouched. A transfer moves money that
  already exists; minting bonuses on it would let two players farm them by paying each other back and
  forth. A new account's starting balance does not earn one either.

## Adapters

- **Vault** (v2 sync + async, legacy v1): every deposit passes `bonus = null`, so `by-default` decides.
  Responses still describe the deposited currency only. `VaultSyncPath.deposit` also mirrors the bonus
  balance, or an online player's bonus would read stale until they relog.
- **`/eco give <player> <amount> [currency] [bonus|nobonus]`.** `/eco give Bob 100 bonus` lands the
  keyword in the currency slot (Brigadier matches the word argument first — M7's `/balance gems`
  lesson), so the handler decides: a word naming a currency is a currency, otherwise a keyword means the
  default currency. The reply gains `(+<bonus>)` via `admin.given-bonus` when one was credited.
- `/eco take`, `set`, `reset` never touch the bonus currency.
- New keys: `admin.given-bonus`, `error.no-bonus-currency` (`en.yml`, `ru.yml`).

## Acceptance / tests

- `BonusDepositTest`: default on/off, explicit override both ways, ratio rounding, round-to-zero,
  `NoBonusCurrency`, a failed bonus write rolls back the deposit, take/set/transfer never move bonuses.
- `ConfigLoaderTest`: the block and its defaults; every error and the keyword warning.
- `EcoGiveBonusTest`: `gems`, `gems nobonus`, keyword in the currency slot, take/set/reset untouched.
- `VaultSyncPathTest`: the bonus balance is mirrored after a Vault deposit.
- Live: a Vault plugin depositing gems raises bonuses and `/balance bonuses` shows it at once; `/pay`
  of gems mints none.
