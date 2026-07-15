# Task M7 — Commands & UX

**Goal:** Player and admin commands routed through `EconomyService`, async, localized, with permissions
and tab completion.

**Read first:** `../SPEC.md §7`, `../LOCALIZATION.md`, `../ARCHITECTURE.md §4`.

## Commands (`infrastructure/bukkit/command`)
| Command | Perm |
|---|---|
| `/balance [player] [currency]` | `geckonomy.balance` (+`.others`) |
| `/pay <player> <amount> [currency]` | `geckonomy.pay` |
| `/baltop [currency]` | `geckonomy.baltop` |
| `/eco give\|take\|set\|reset <player> <amount> [currency]` | `geckonomy.admin` |
| `/geckonomy reload\|version` | `geckonomy.admin` |

## Implementation notes
- Each handler: parse + validate args → launch coroutine → call `EconomyService` (off main thread) →
  hop to main thread → `MessageService.send`.
- Currency arg optional → default currency; unknown → localized error. Amount parsed as `BigDecimal`;
  invalid → error.
- `/pay` uses the atomic transfer; `/eco reset` sets to the currency's `starting-balance`.
- `/baltop` uses `BalanceRepository.top(currency, baltop-size)`; render names via account name map.
- Tab completion: online players, currency codes, `eco` subcommands.
- Register commands (Cloud v2 recommended, else Bukkit). Declare permissions with defaults in
  `paper-plugin.yml`.

## Acceptance / tests
- MockBukkit tests: each command's happy path + permission denial + bad-arg error.
- Live server: `/balance`, `/balance <other>`, `/pay`, `/baltop`, `/eco give|take|set|reset`,
  `/geckonomy reload|version` all behave and message correctly.
- No main-thread DB IO (commands await async results).
