# Task M9 — PlaceholderAPI Expansion

**Goal:** A read-only PlaceholderAPI expansion under the identifier `geckonomy`, exposing currency
metadata, player balances and the leaderboard to scoreboard/tab/hologram/chat plugins — without ever
touching the database from the main thread.

**Read first:** `../SPEC.md §4.7` (the requirements this milestone answers, `FR-P1`–`FR-P9`),
`../ARCHITECTURE.md §4` (the threading model and why the mirror exists), `M6-vault-provider.md` (the
soft-dependency pattern this copies wholesale).

## The shape of the problem

PlaceholderAPI's `onRequest(OfflinePlayer, String)` is **synchronous, on the main thread**, and a
scoreboard plugin re-renders it **every tick, for every viewer**. Tab-list and leaderboard plugins ask
for **offline** players. That is the same traffic shape as Vault's sync API, only far worse: Vault is
asked once per shop sale.

So the rule M6 could afford — mirror first, bounded blocking read for an un-mirrored account (~99µs) —
**does not survive here**. `VaultSyncPath`'s fallback is defensible because it is rare; a tab list of
100 offline players would make it the common path and spend a tick every ~500 lookups. Hence `FR-P7`:
placeholders do **zero** database IO. Balances come from the mirror or not at all; the leaderboard
comes from a snapshot refreshed on a timer.

**Do not reuse `VaultSyncPath` for balances.** It is tempting — it names no Vault types and already
holds the rules — but its rules are the wrong ones here, and its "a failed read answers zero, because
zero fails closed" reasoning is specific to a Vault caller that might hand over goods. A placeholder
has no such stake, and rendering `0` for a player who merely logged out is a lie a scoreboard will
show for hours. Read `OnlineBalanceMirror` directly and render the configured fallback on a miss.

## Create (`infrastructure/placeholder`)

- **`PlaceholderResolver`** — all the parse-and-render logic. Takes `CurrencyRegistry`,
  `OnlineBalanceMirror`, `FormatMoney`, `BaltopSnapshot`, and a `() -> String` fallback supplier.
  **Names no PlaceholderAPI type**, so it is testable with plain JUnit — no MockBukkit, no PAPI. This
  split is not stylistic; see §Spike.
- **`BaltopSnapshot`** — `@Volatile` `Map<CurrencyCode, List<TopBalance>>` plus a derived
  `Map<CurrencyCode, Map<AccountId, Int>>` for own-rank lookups. A coroutine on the plugin scope
  refreshes it every `placeholders.baltop-refresh-seconds` via `EconomyService.top(code, baltopSize())`,
  one call per currency per interval. Reads are a plain field read — free, and safe on the main thread.
  Before the first refresh completes it serves an empty snapshot, which renders the fallback.
- **`GeckonomyExpansion : PlaceholderExpansion`** — `getIdentifier()` = `geckonomy`, `getAuthor()`,
  `getVersion()` from the plugin, **`persist()` = true**, `getPlaceholders()` advertising the table.
  `onRequest` delegates straight to `PlaceholderResolver` and holds **no logic of its own**.
- **`PlaceholderRegistration : AutoCloseable`** — **the only class naming a PlaceholderAPI type at
  wiring time**, mirroring `VaultRegistration`. `register()` on enable; `close()` unregisters.

## Placeholders

`<code>` is optional everywhere — absent means the **default currency**. `<n>` is a 1-based rank.

| Placeholder | Renders |
|---|---|
| `%geckonomy_balance[_<code>]%` | balance, `toPlainString()` — alias of `_raw` |
| `%geckonomy_balance_raw[_<code>]%` | same, spelled explicitly (see §Shadowing) |
| `%geckonomy_balance_formatted[_<code>]%` | `FormatMoney` — the currency's `format` template |
| `%geckonomy_balance_commas[_<code>]%` | grouped digits only, no template |
| `%geckonomy_balance_fixed[_<code>]%` | `setScale(0, RoundingMode.DOWN)` — truncated, as Vault's `_fixed` |
| `%geckonomy_balance_name[_<code>]%` | `Currency.nameFor(balance)` — the name **agreeing with the balance** |
| `%geckonomy_symbol[_<code>]%` | `Currency.symbol` |
| `%geckonomy_name[_<code>]%` | `singular` — alias of `_name_singular` |
| `%geckonomy_name_singular[_<code>]%` | `singular`, spelled explicitly |
| `%geckonomy_name_plural[_<code>]%` | `plural` |
| `%geckonomy_digits[_<code>]%` | `fractionalDigits` |
| `%geckonomy_format_<amount>[_<code>]%` | an arbitrary amount through `FormatMoney` |
| `%geckonomy_baltop_player_<n>[_<code>]%` | account name at rank `<n>` |
| `%geckonomy_baltop_balance_<n>[_<code>]%` | balance at rank `<n>`, `toPlainString()` |
| `%geckonomy_baltop_balance_formatted_<n>[_<code>]%` | balance at rank `<n>`, formatted |
| `%geckonomy_baltop_rank[_<code>]%` | the requesting player's own rank |

`baltop_`, not Vault's `top_`: the command is `/baltop`, and one name for one concept beats matching a
different plugin's spelling. Worth knowing that `%vault_eco_top_balance_1%` is the convention being
deviated from, so the deviation is a choice and not an oversight.

**`FormatMoney` is the only number formatter.** `_commas` needs the grouped amount without the
template, which `FormatMoney` currently builds internally — extract it as `FormatMoney.amount(money)`
and have `invoke` call it. Do **not** write a second `NumberFormat` here: it would drift from the
currency's `fractionalDigits` and the reloadable locale, and the two would disagree about `1,000.00`
in ways nobody would trace back. Note `toPlainString()`, never `toString()`, or a large balance
renders in scientific notation.

## Parsing

`params` is a flat `_`-joined string, and **currency codes may contain `_`** — `CurrencyCode`'s pattern
is `[a-z0-9_-]+` (`CurrencyCode.kt:23`). So `balance_formatted_my_currency` cannot be split naively; a
greedy split is wrong in both directions. Lowercase `params` once at entry, then:

1. Match the **longest** variant keyword as a prefix, from a fixed table, longest-first —
   `balance_formatted` before `balance`, `baltop_balance_formatted` before `baltop_balance`.
2. For the variants taking a **positional argument** (`format_<amount>`, `baltop_player_<n>`,
   `baltop_balance[_formatted]_<n>`), consume **exactly one `_`-token** as that argument; the
   remainder is the code. This is sound because the arguments **cannot contain `_`** — a `BigDecimal`
   literal is digits, `.`, `-`, `+`, `E`, and a rank is digits — while a code can. The argument is
   therefore always the first token and the code is unambiguously everything after it.
3. The remainder → `CurrencyCode.parseOrNull` — never `invoke`, this is untrusted text and a bad value
   is a user error, not a bug (`CurrencyCode.kt:37-42`) — then `CurrencyRegistry.byCode`. Empty
   remainder → default currency.

Anything unresolvable — unknown variant, unknown currency, unparseable amount or rank, a rank outside
the snapshot — returns **`null`** (`FR-P8`). PlaceholderAPI then leaves the text as it found it, which
is the honest answer. Never fabricate a `0`: a scoreboard reading `$0` is indistinguishable from a real
broke player.

Keep these as test cases verbatim:

| `params` | variant | arg | code |
|---|---|---|---|
| `balance` | `balance` | — | default |
| `balance_formatted_my_currency` | `balance_formatted` | — | `my_currency` |
| `format_1234.56_my_currency` | `format` | `1234.56` | `my_currency` |
| `baltop_player_3_my_currency` | `baltop_player` | `3` | `my_currency` |
| `baltop_player_3` | `baltop_player` | `3` | default |
| `baltop_balance_formatted_2` | `baltop_balance_formatted` | `2` | default |

### Shadowing

A currency whose code *is* a variant keyword shadows that variant, because step 1 wins: with a currency
coded `formatted`, `%geckonomy_balance_formatted%` means "formatted balance of the default currency",
not "raw balance of `formatted`". This is why `_raw` and `_name_singular` exist as explicit spellings —
`%geckonomy_balance_raw_formatted%` reaches the shadowed currency, so **nothing is unreachable**, and
the cost is two entries in the keyword table. The shadowing only bites where a keyword is directly
followed by the code slot; the `baltop_*` family is immune because the rank sits between them.

Emit a **warning at config load**, not a rejection, when a currency code collides with a keyword: the
currency works perfectly everywhere else, and refusing to start a server over a placeholder spelling
would be wildly out of proportion.

This is M7's `/balance <word>` ambiguity again — text alone cannot disambiguate, so the handler decides
by a stated rule. Stating it here is cheaper than rediscovering it.

## No flag gating, no permission checks

`FR-P9`: **every currency answers every placeholder.** Do not check `transferable`,
`balance-check-others` or `show-in-baltop`, and do not reach for a permission node. `/baltop` filters
on `showInBaltop` and `/balance <other>` on `checkableOthers`, so adding the same checks here looks
like consistency — it is the reflex this section exists to stop. Those flags are rules about a
*command*: an actor doing something, or a viewer looking at someone else. PlaceholderAPI hands over
neither an actor nor a viewer, only the target `OfflinePlayer`, so there is nothing to judge them
against and any check would be inventing a subject.

The consequence, stated so it is a decision rather than a discovery: a `show-in-baltop: false`
currency **is** reachable through `%geckonomy_baltop_*%`. That flag hides a currency from `/baltop`,
not from a hologram. Placeholders are a raw data surface and not a privacy boundary — a server that
needs one must not print the placeholder.

## Wire (composition root)

Copy M6's four-part pattern exactly; each part is load-bearing on its own:

- The presence check stays in `Geckonomy.kt`, **outside** any method naming a PAPI type — the JVM
  resolves a class the moment a method naming one runs (`Geckonomy.kt:148-155`).
  `server.pluginManager.getPlugin("PlaceholderAPI") != null` is **sufficient and conclusive**: unlike
  VaultUnlocked, PAPI does not squat another plugin's name, so no `Class.forName` second step is needed.
  Do not copy that dance from `vaultUnlockedInstalled()`; it exists only because `Vault` is ambiguous.
- The field is typed `AutoCloseable`, never `PlaceholderRegistration`, and the registering method's
  **return type** is `AutoCloseable` too — naming the class in a signature loads it
  (`Geckonomy.kt:121-127`).
- Closed in `onDisable` next to `vault?.close()`, before the scope is cancelled (`Geckonomy.kt:262-281`).
- Absent PAPI is **not** an error: log that placeholders are unavailable and enable normally.

`paper-plugin.yml` gains a second soft dependency:

```yaml
    PlaceholderAPI:
      load: BEFORE
      required: false
      join-classpath: true
```

## Dependency & config

`me.clip:placeholderapi` at **`provided`** scope, in the *"Provided by the server"* group beside
`paper-api` and `VaultUnlockedAPI` — **not** a `geckonomy-libraries.txt` entry. The pom's own comment
says a runtime library means editing both files (`pom.xml:15-23`), and the reflex is to obey it; PAPI is
a **server plugin**, not a Maven runtime library, so `GeckonomyLoader` must not fetch it. Needs a third
repository, `https://repo.helpch.at/releases/` — the official one per PAPI's wiki, **not**
`repo.extendedclip.com`, which hosts the eCloud expansions and is the natural wrong guess. Add a
`papi.version` property beside `paper.version` and `vault.version`.

New `config.yml` block, documented in `CONFIGURATION.md` §1/§2 as part of this milestone:

```yaml
placeholders:
  baltop-refresh-seconds: 60   # how often the leaderboard snapshot is rebuilt
  fallback: "0"                # rendered when a value is unknown (offline player, rank beyond baltop-size)
```

Both are **reloadable**, so inject them as `() -> T` suppliers read per call, not captured values — the
idiom at `Geckonomy.kt:187-188`. `ConfigService.restartWarnings` staying silent about a key is a promise
that `/geckonomy reload` applies it; capturing would make the reload report success and change nothing.
Clamp `baltop-refresh-seconds` to a floor (≥ 5) — a `0` would spin the IO pool against the database
forever.

## Own rank is bounded, and that is not a bug

`%geckonomy_baltop_rank%` is answered from the snapshot, so it is bounded by `baltop-size`: a player
outside the top N has **no rank** and renders the fallback. A true rank needs a
`COUNT(*) WHERE balance > ?` per player per tick — precisely the IO `FR-P7` forbids. Say so in the
config comment, or the first bug report will be "rank is empty for most players".

## Acceptance / tests

- `PlaceholderResolver` tested with plain JUnit over a **real** `EconomyService` on in-memory ports
  (the `EconomyFixture` pattern — a real service, not a mocked one): every row of the table; default
  vs named currency; a balance of exactly `1` renders "1 Coin" and not "1.00 Coins"; unknown variant,
  unknown currency, unparseable amount and out-of-range rank each → `null`.
- **The parsing tests are the point of this milestone.** A currency coded `my_currency` resolves
  through every variant; `balance_formatted_my_currency` is not read as currency `formatted_my_currency`;
  a currency coded `formatted` is shadowed but reachable via `balance_raw_formatted`.
- An un-mirrored (offline) player renders the fallback, and the test asserts **no port call was made** —
  that is `FR-P7`, and it is the one thing a later refactor could silently break.
- `BaltopSnapshot` tested on a `TestCoroutineScheduler` — no real sleeps (`CODING_STANDARDS §6`).
  Assert one `top()` per currency per interval regardless of read count, and an empty snapshot before
  the first refresh.
- **Live smoke on Paper** — M6 and M8 each shipped green test suites over a feature that did not work at
  all on a real server, both times for something no unit test can see: `/papi parse me
  %geckonomy_balance%` and the whole table; placeholders render in a real scoreboard plugin; the
  expansion **survives `/papi reload`** (the `persist()` check — this is the one that will be wrong);
  the plugin enables cleanly with **PlaceholderAPI absent**; an offline player in a tab list renders the
  fallback without stalling the tick.
- **No main-thread DB IO**: profile with `spark` while a scoreboard renders leaderboard placeholders for
  online *and* offline players; assert no JDBC frames on the main thread. The standing invariant that
  closes M6, M7 and M8.

## Spike — before any code

**Settle whether `PlaceholderExpansion` can be constructed under MockBukkit with no PAPI running.** Its
constructor looks trivial, and that is exactly what M7 assumed about Cloud v2: every Cloud command
manager reflects into NMS *in its constructor*, so none can exist under MockBukkit, and the spike that
proved it ran before a line of command code was written. `register()` calls
`PlaceholderAPIPlugin.getInstance()` and is certainly untestable.

Thirty minutes: subclass `PlaceholderExpansion`, call `onRequest(null, "x")`, under MockBukkit and under
plain JUnit. If the constructor touches PAPI statics, `GeckonomyExpansion` stays a zero-logic
pass-through that no test covers, and everything of value lives in `PlaceholderResolver` — which is why
the resolver is split out **either way**. The spike decides how much is left untested, not the design.
