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
- Register `/balance` with alias `/bal`. Declare permissions with defaults in `paper-plugin.yml`.

## Command framework: Paper Brigadier — **not** Cloud v2

This spec recommended Cloud v2. It was chosen at review, added, and reverted on evidence; the note stays
so nobody spends the afternoon again.

**Every Cloud command manager reflects into NMS/CraftBukkit in its constructor**, so none can be
instantiated under MockBukkit, which is API-only:

- `PaperCommandManager` → `ModernPaperBrigadier` → `ClassNotFoundException:
  net.minecraft.commands.synchronization.ArgumentTypeInfos`
- `LegacyPaperCommandManager` → `BukkitCommandPreprocessor` → `ClassNotFoundException:
  org.bukkit.craftbukkit.command.VanillaCommandWrapper`

The legacy manager makes Brigadier opt-in and looks like the escape hatch. It is not: the reflection
lives in the Bukkit base class, above that choice. Since the acceptance criteria below are MockBukkit
tests per command, Cloud would have meant no CI coverage of parsing, permissions, or the `/bal` alias.

Paper's Brigadier (`io.papermc.paper.command.brigadier`, in `paper-api` — no dependency, no beta) passes
all of it. Three rules it costs time to rediscover, all encoded in `CommandHarness`:

1. Register inside `onEnable`. `JavaPlugin.allowsLifecycleRegistration` flips false once enable returns.
2. Dispatch with `server.dispatchCommand`. `ServerMock.execute` reads the legacy command map and NPEs.
3. A test plugin must be `open` — MockBukkit subclasses it with ByteBuddy.

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
