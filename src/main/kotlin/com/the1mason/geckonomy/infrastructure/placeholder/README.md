# infrastructure.placeholder

`GeckonomyExpansion`, `PlaceholderRegistration`, `PlaceholderResolver`, `PlaceholderVariant`,
`BaltopSnapshot`. The `%geckonomy_...%` PlaceholderAPI expansion (SPEC.md §4.7).

Read-only: it exposes what the economy already knows and adds no way to change a balance.

**`GeckonomyExpansion` is the only class here that names a PlaceholderAPI type**, and it holds no
logic — every rule is in `PlaceholderResolver`, which names neither PAPI nor Bukkit and is tested
with plain JUnit. `PlaceholderRegistration` is the only class the composition root loads, and only
after checking PAPI is installed; the same shape as `VaultRegistration`, for the same reason.

**No database IO, ever** (FR-P7). Not even the bounded blocking read `VaultSyncPath` is allowed —
Vault is asked once per shop sale, a placeholder every tick for every viewer, including for offline
players. Online balances come from `OnlineBalanceMirror`, offline ones from `OfflineBalanceCache`
(answers from memory, fills behind the render), the leaderboard from `BaltopSnapshot`
(timer-rebuilt). All three live to make a render cost nothing.

**No flag or permission gating** (FR-P9). `transferable`, `checkableOthers` and `showInBaltop` judge
an actor or a viewer, and PAPI supplies neither — only the target. Reaching for `CurrencyAccess` here
would invent a subject to judge. So a `show-in-baltop: false` currency *is* reachable through
`%geckonomy_baltop_*%`: that flag hides a currency from `/baltop`, not from a hologram. Placeholders
are a raw data surface, not a privacy boundary.

**Parsing is the hard part.** A currency code may contain `_`, so `params` cannot be split on it.
Longest keyword wins, then exactly one token is the argument (arguments cannot contain `_`; codes
can), then the rest is the code. A currency coded as a keyword is shadowed but still reachable via
the explicit spelling — `%geckonomy_balance_raw_formatted%`. See `PlaceholderResolver`'s KDoc.
