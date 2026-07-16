# Task M7 — Commands & UX

**Goal:** Player and admin commands routed through `EconomyService`, async, localized, with permissions
and tab completion.

**Read first:** `../SPEC.md §7`, `../LOCALIZATION.md`, `../ARCHITECTURE.md §4`.

## Commands (`infrastructure/bukkit/command`)
| Command (aliases) | Base perm | Per-currency perm |
|---|---|---|
| `/balance` (`/bal`) `[player] [currency]` | `geckonomy.balance` (+`.others`) | `geckonomy.balance.<code>` (+`.others.<code>`) |
| `/pay <player> <amount> [currency]` | `geckonomy.pay` | `geckonomy.pay.<code>` |
| `/baltop [currency]` | `geckonomy.baltop` | `geckonomy.baltop.<code>` |
| `/eco give\|take\|set\|reset <player> <amount> [currency]` | `geckonomy.admin` | — |
| `/geckonomy reload\|version` | `geckonomy.admin` | — |

## Implementation notes
- Each handler: parse + validate args → launch coroutine → call `EconomyService` (off main thread) →
  hop to main thread → `MessageService.send`.
- Currency arg optional → default currency; unknown → localized error. Amount parsed as `BigDecimal`;
  invalid → error.
- **Per-currency gating** for `balance`/`pay`/`baltop`: require the base node **and**
  `geckonomy.<action>.<code>` (wildcards `.*`). Missing node → `error.no-currency-permission`.
- **Currency config flags** are hard gates independent of permissions: `transferable=false` →
  `error.not-transferable` on `/pay`; `balance-check-others=false` → `error.others-hidden` on
  `/balance <other>`; `show-in-baltop=false` → excluded from `/baltop`.
- Only currencies the player may use are offered in **tab completion** (per-currency perms + flags
  respected).
- `/pay` uses the atomic transfer; `/eco reset` sets to the currency's `starting-balance`. Admin `/eco`
  bypasses per-currency permission nodes.
- `/baltop` uses `BalanceRepository.top(currency, baltop-size)`; render names via account name map.
- Tab completion: online players, permitted currency codes, `eco` subcommands.
- Register `/balance` with alias `/bal`. Register commands (Cloud v2 recommended, else Bukkit). Declare
  permissions with defaults in `paper-plugin.yml`.

## Acceptance / tests
- MockBukkit tests: each command's happy path + base-permission denial + **per-currency** denial +
  bad-arg error.
- Flag tests: `/pay` on a `transferable:false` currency is refused; `/balance <other>` on a
  `balance-check-others:false` currency is refused; a `show-in-baltop:false` currency is absent from
  `/baltop`.
- `/bal` invokes the same handler as `/balance`.
- Live server: `/balance`, `/bal`, `/balance <other>`, `/pay`, `/baltop`, `/eco give|take|set|reset`,
  `/geckonomy reload|version` all behave and message correctly.
- No main-thread DB IO (commands await async results).
