# Geckonomy — Configuration Reference

All runtime behavior is config-driven. Config is loaded on enable and on `/geckonomy reload`, validated,
and mapped to typed objects (`StorageConfig`, `CurrencyConfig`, `SettingsConfig`).

## 1. `config.yml`

```yaml
# ── Storage ─────────────────────────────────────────────────────────────
storage:
  type: sqlite            # sqlite | mariadb
  # SQLite (local):
  file: "plugins/Geckonomy/data.db"
  # MariaDB (remote):
  host: "127.0.0.1"
  port: 3306
  database: "geckonomy"
  username: "geckonomy"
  password: "change-me"
  properties:             # optional extra JDBC properties
    useSSL: "false"
  pool:
    maximum-pool-size: 10
    minimum-idle: 2
    connection-timeout-ms: 10000

# ── Currencies ──────────────────────────────────────────────────────────
# Exactly one must have default: true.
currencies:
  - code: "coins"         # machine id, lowercase [a-z0-9_-]
    singular: "Coin"
    plural: "Coins"
    symbol: "$"
    fractional-digits: 2
    starting-balance: 100.00
    default: true
    scope: network        # network (shared across servers) | server (per this server)
    transferable: true    # may players /pay this currency?
    balance-check-others: true   # may players check others' balance in this currency?
    show-in-baltop: true  # appears in /baltop?
    format: "<symbol><amount>"     # display template (see LOCALIZATION.md)
  - code: "gems"
    singular: "Gem"
    plural: "Gems"
    symbol: "◆"
    fractional-digits: 0
    starting-balance: 0
    default: false
    scope: server         # per-server currency: balance is local to this server instance
    transferable: false   # cannot be paid between players
    balance-check-others: false
    show-in-baltop: true
    format: "<amount> <currency>"

# ── Settings ────────────────────────────────────────────────────────────
settings:
  server-id: "survival"   # identifies THIS server instance; scope key for per-server currencies.
                          # Must be unique per server sharing a database. Changing it orphans
                          # this server's per-server balances (they get a new scope key).
  language: "en"          # language file under lang/, falls back to en
  allow-overdraft: false  # if true, balances may go negative
  rounding-mode: HALF_UP  # any java.math.RoundingMode
  keep-transaction-history: true
  baltop-size: 10

# ── Placeholders ────────────────────────────────────────────────────────
# The PlaceholderAPI expansion (%geckonomy_...%). Only used when PlaceholderAPI is
# installed; without it Geckonomy runs normally and says so at startup.
placeholders:
  baltop-refresh-seconds: 60   # how often the leaderboard snapshot is rebuilt (min 5)
  offline-cache-seconds: 60    # how long an offline player's balance is reused before a re-read
  fallback: "0"                # shown while a value is not known yet

# ── Cross-server sync (RESERVED — not active in v1) ──────────────────────
# Live propagation of `network` currencies between servers. Until this ships, a
# network currency on a shared MariaDB is refreshed just after it is read, so a
# balance another server changed shows up on the next read rather than at once.
# redis:
#   enabled: false
#   host: "127.0.0.1"
#   port: 6379
#   password: ""
```

## 2. Field reference

### `storage`
| Key | Type | Default | Notes |
|---|---|---|---|
| `type` | enum | `sqlite` | `sqlite` (local file) or `mariadb` (remote). |
| `file` | string | `plugins/Geckonomy/data.db` | SQLite only. |
| `host`/`port`/`database`/`username`/`password` | | | MariaDB only. |
| `properties` | map | `{}` | Extra JDBC connection props. |
| `pool.maximum-pool-size` | int | 10 | HikariCP. SQLite effectively small. |
| `pool.minimum-idle` | int | 2 | |
| `pool.connection-timeout-ms` | int | 10000 | |

### `currencies[]`
| Key | Type | Default | Notes |
|---|---|---|---|
| `code` | string | — | Unique, lowercase, `[a-z0-9_-]`. |
| `singular` / `plural` | string | — | Display names. **MiniMessage** (M10); rendered as a self-contained component, so styling never bleeds into the rest of a message. Overridable per language via `currencies.<code>` in a lang file — see `LOCALIZATION.md §2/§4`. |
| `symbol` | string | — | Currency symbol. **MiniMessage** (M10), same self-contained rendering as `singular`/`plural`. |
| `fractional-digits` | int 0–4 | — | Decimal places; `0` = whole units. Capped at the stored scale of 4 (DATA_MODEL.md §3). |
| `starting-balance` | decimal | 0 | Seeded on account creation. Rounded to `fractional-digits` at load. |
| `default` | bool | — | Exactly one `true` across the list. |
| `scope` | enum | `server` | `network` = balance shared across servers on the same DB; `server` = balance local to this server instance (keyed by `server-id`). |
| `transferable` | bool | `true` | If `false`, `/pay` is refused for this currency regardless of permissions. |
| `balance-check-others` | bool | `true` | If `false`, players can't view others' balance in this currency (own balance still visible). |
| `show-in-baltop` | bool | `true` | If `false`, excluded from `/baltop`. |
| `format` | string | `<symbol><amount>` | Display template; placeholders `<symbol>`, `<amount>`, `<currency>`. **MiniMessage** (M10): the template itself, plus `<symbol>`/`<currency>`, are rendered as MiniMessage — see `LOCALIZATION.md §4`. |

### `settings`
| Key | Type | Default | Notes |
|---|---|---|---|
| `server-id` | string | `default` | Identifies this server instance; scope key for per-server currencies. Unique per server sharing a DB. |
| `language` | string | `en` | Language file name (without `.yml`). |
| `allow-overdraft` | bool | `false` | Permit negative balances. |
| `rounding-mode` | enum | `HALF_UP` | `java.math.RoundingMode` name. |
| `keep-transaction-history` | bool | `true` | Retain ledger on account delete. |
| `baltop-size` | int | 10 | `/baltop` row count. Also bounds `%geckonomy_baltop_*%`. |
| `claim-vault-economy` | bool | `true` | Make Geckonomy the **only** economy Vault answers with. When true, it unregisters any other Vault economy provider — at startup and if one registers later — so shops and similar plugins can never bind to the wrong one. This is the switch for **EssentialsX**, whose economy does not stand down on its own and has no setting to make it. Set `false` only if you deliberately run another economy plugin alongside Geckonomy. Reloadable. |

### `placeholders`
Only used when PlaceholderAPI is installed. See `SPEC.md §4.7` for the full table.

| Key | Type | Default | Notes |
|---|---|---|---|
| `baltop-refresh-seconds` | int 5–86400 | 60 | How often the leaderboard snapshot rebuilds. Placeholders never query the database, so the leaderboard is at most this far behind. Floored at 5: a `0` would spin the IO pool against the database forever. |
| `offline-cache-seconds` | int 5–86400 | 60 | How long an **offline** player's balance is reused before it is re-read behind the next render. Online players read from the mirror and ignore this. An `/eco give` to an offline player can take this long to show in a placeholder. |
| `fallback` | string | `"0"` | Rendered when the placeholder is understood but the value is not known **yet**: the first render for an offline player (the real balance follows a tick or two later), or a rank nobody holds. An unrecognized placeholder renders as itself instead, never as this. |

**`%geckonomy_baltop_rank%` is bounded by `baltop-size`.** It is answered from the leaderboard
snapshot, so a player outside the top N has *no rank* and renders `fallback`. This is not a bug: a
true rank is a `COUNT(*) WHERE balance > ?` per player per tick, which is exactly the database IO
placeholders exist not to do.

**A currency code that collides with a placeholder keyword is shadowed**, and startup warns about it.
With a currency coded `formatted`, `%geckonomy_balance_formatted%` means "the formatted balance of
the default currency" — the longer keyword wins. The currency is still reachable through the explicit
spelling, `%geckonomy_balance_raw_formatted%`, so nothing is unreachable and the warning is a warning
rather than a refusal to start.

**Per-currency permissions** (see `SPEC.md §7`): beyond the flags above, each currency has permission
nodes `geckonomy.balance.<code>`, `geckonomy.balance.others.<code>`, `geckonomy.pay.<code>`,
`geckonomy.baltop.<code>` (wildcards `.*`). A currency action is allowed only when **both** the config
flag permits it **and** the player holds the node.

These nodes are named after your currencies, so they cannot be listed in `paper-plugin.yml` like the
base nodes are. Geckonomy registers them **at startup and on every `/geckonomy reload`**, reading them
straight from `currencies[]` — add a currency, reload, and its four nodes exist. Each defaults to
**true** (the opt-out model: deny the ones you want to sell). The `.*` wildcards default to **op**,
because they also grant currencies added later, and are registered holding every current currency as a
child — which is what makes granting `geckonomy.pay.*` work on a server with no permissions plugin.

## 3. Validation rules (fail fast on load)

- `storage.type` ∈ {`sqlite`, `mariadb`}; required backend fields present (`file` for SQLite;
  `host`/`database`/`username` for MariaDB — an empty `password` is legitimate). `port` ∈ 1–65535.
- `pool.minimum-idle ≤ pool.maximum-pool-size`; `connection-timeout-ms ≥ 250` (Hikari's own floor).
- `currencies` non-empty; **exactly one** `default: true`.
- Currency `code`s unique (case-insensitively) and well-formed; `0 ≤ fractional-digits ≤ 4` — the
  upper bound is the stored scale (DATA_MODEL.md §3); a fifth decimal place would be truncated on
  write.
- `starting-balance` may not be negative unless `allow-overdraft` is true. One finer than the
  currency's `fractional-digits` is rounded (per `rounding-mode`) with a warning, not rejected.
- `scope` ∈ {`network`, `server`}.
- `settings.server-id` non-empty (warn if left at `default` while any `network` currency exists on a
  shared DB, since collisions across servers must be avoided).
- `rounding-mode` is a valid `RoundingMode`; `baltop-size ≥ 1`.
- `placeholders.baltop-refresh-seconds` and `placeholders.offline-cache-seconds` ∈ 5–86400. The floor
  is the point: `0` would rebuild the snapshot in a tight loop against the database forever.
- A currency `code` that collides with a placeholder keyword **warns**, and does not fail: it is
  shadowed in one spelling and reachable in another (§2), which is not worth refusing to start over.
- **Every** problem is reported at once, not just the first, so one round of edits can fix them all.
- On validation failure: log a clear error and **disable the plugin** rather than run misconfigured.

## 4. Reload semantics

`/geckonomy reload` re-reads `config.yml` and language files, rebuilds the `CurrencyRegistry` and
`MessageService`. **Storage connection changes** (type/credentials) require a server restart in v1 (log a
warning if they changed); currency and message changes apply live. Removing a currency that still has
balances is warned about, not auto-deleted.

An **invalid** file changes nothing: the errors are reported and the server keeps running on the config
it already had. **`settings.server-id`** also needs a restart — it is the scope key the persistence layer
resolved at startup (DATA_MODEL.md §7) — and a change is warned about, since per-server balances stored
under the old id are not visible under the new one. **`settings.allow-overdraft`** likewise needs a
restart and is warned about: the rule is compiled into the balance repository's SQL guard at startup,
because the check has to be atomic with the update it guards (DATA_MODEL.md §4).

The whole **`placeholders`** block applies live, and nothing warns about it — that silence is the
promise. Each key is read through a supplier on every use rather than captured at wiring time, so a
reload genuinely changes the next render. A new `baltop-refresh-seconds` takes effect after the
in-flight sleep ends, not mid-sleep.

## 5. Parsing library (decided at M2)

Bukkit **`YamlConfiguration`**, read via `YamlConfiguration().loadFromString(text)`.

Configurate was the earlier recommendation; `YamlConfiguration` won on the two things that turned out
to matter. It is server-provided, so it adds no runtime library and no `GeckonomyLoader` entry
(CODING_STANDARDS.md §8) — Configurate would have cost four artifacts. And Configurate's draws do not
apply here: its comment-preserving writes are moot because Geckonomy never writes the config back, and
its `ObjectMapper` typing is unused because the validation rules in §3 are richer than annotations can
express, so the mapping is hand-written either way.

`loadFromString` touches no Bukkit statics and throws on malformed YAML, so `ConfigLoader` takes the
file's *text* and stays testable with plain JUnit — no server, no MockBukkit. Config is mapped to the
typed objects above, so nothing outside `infrastructure.config` ever sees raw YAML.
