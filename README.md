# Geckonomy

A multi-currency economy for modern Paper servers. Geckonomy owns your players' accounts and balances
and exposes them to other plugins through **VaultUnlocked**, so shops, jobs and quests can spend them
without knowing anything about it.

- **Multiple currencies**, defined in `config.yml` — one default, as many others as you like.
- **SQLite** (a local file, zero setup) or **MariaDB** (shared between servers).
- Per-currency permissions and flags: a currency can be un-payable, private, or hidden from `/baltop`.
- Every player-facing message is yours to edit, in MiniMessage, in any language.
- **Both** Vault APIs: the modern multi-currency v2 **and** the legacy v1 that most plugins still use.

## Requirements

| | |
|---|---|
| Server | Paper 26.1.2+ (`paper-plugin.yml`, Brigadier — Spigot is not supported) |
| Java | 25+ |
| VaultUnlocked | Optional, but needed for any other plugin to see your economy |
| PlaceholderAPI | Optional; enables the `geckonomy` placeholders (see below) |

> **VaultUnlocked, not the original Vault.** VaultUnlocked installs under the plugin name `Vault` and is
> a drop-in replacement for it — that is expected. The original Vault has no v2 API; Geckonomy will
> start, log a warning, and simply not register with it.

## Install

1. Drop `geckonomy-1.0.0.jar` into `plugins/`.
2. Start the server. **The first start needs internet access** — Geckonomy downloads its own libraries
   (Kotlin, HikariCP, the database driver) instead of bundling them, which keeps the jar ~0.5 MB rather
   than ~20 MB. They are cached under the server's `libraries/` folder; later starts are offline.
3. Edit `plugins/Geckonomy/config.yml`, then `/geckonomy reload`.

On a bad config Geckonomy **refuses to start** and lists every problem at once, rather than running with
the wrong currencies. Fix what it printed and restart.

## Configuring

The shipped `config.yml` is commented and ships two example currencies (`coins`, `gems`). The full
reference — every key, its default, and the validation rules — is **[docs/CONFIGURATION.md](docs/CONFIGURATION.md)**.
The essentials:

```yaml
storage:
  type: sqlite            # sqlite = a local file, nothing to set up. mariadb = shared, see below.
  file: "plugins/Geckonomy/data.db"

currencies:
  - code: "coins"         # exactly one currency must have `default: true`
    singular: "Coin"
    plural: "Coins"
    symbol: "$"
    fractional-digits: 2  # 0 = whole units only
    starting-balance: 100.00
    default: true
    scope: network        # see "Currency scope"
    transferable: true    # may players /pay it?
    balance-check-others: true
    show-in-baltop: true
    format: "<symbol><amount>"   # -> $100.00

settings:
  server-id: "survival"   # identifies THIS server; only matters if several share a database
  language: "en"
  allow-overdraft: false
  baltop-size: 10
```

Currency and language changes apply on `/geckonomy reload`. **Storage settings, `server-id` and
`allow-overdraft` need a restart** — Geckonomy warns if you reload after changing one. An invalid file
changes nothing: your server keeps running on the config it already had.

### Currency scope

Each currency is one of two things, and it matters only when several servers share one MariaDB:

- **`network`** — one balance, shared by every server on that database. A player's coins follow them.
- **`server`** — the balance is private to this server, keyed by `settings.server-id`. Give each server
  a *unique* `server-id`; changing it later orphans that server's balances under the old key.

On SQLite the distinction is cosmetic (a file cannot be shared), but the schema is identical, so you can
move to a shared database later without a migration.

### Storage

SQLite needs nothing. For MariaDB, set `type: mariadb` and fill in `host`/`database`/`username`/`password`.
Behaviour is identical on both — the same test suite runs against each.

> **Network currencies need consistent UUIDs across your servers.** Online-mode servers get this from
> Mojang. Do not mix online- and offline-mode servers on one database: the same player gets a different
> UUID on each, and therefore a different account. Behind a proxy, make sure IP/modern forwarding is
> enabled consistently on every backend. See [docs/DATA_MODEL.md §8](docs/DATA_MODEL.md).

## Commands & permissions

| Command | What it does | Permission |
|---|---|---|
| `/balance` (`/bal`) `[player] [currency]` | Show a balance | `geckonomy.balance`, `geckonomy.balance.others` |
| `/pay <player> <amount> [currency]` | Pay another player | `geckonomy.pay` |
| `/baltop [currency]` | The richest accounts | `geckonomy.baltop` |
| `/eco give\|take\|set\|reset <player> [amount] [currency]` | Adjust balances | `geckonomy.admin` |
| `/geckonomy reload` | Reload config + languages | `geckonomy.admin` |
| `/geckonomy version` | Show the version | `geckonomy.admin` |

Player commands default to **everyone**; `/eco` and `/geckonomy` default to **op**.

**Per-currency permissions.** `balance`, `pay` and `baltop` each also have a node per currency —
`geckonomy.pay.coins`, `geckonomy.balance.others.gems`, and so on — registered automatically from your
config at startup and on every reload. They default to **true**, so the model is opt-*out*: deny
`geckonomy.pay.gems` to stop players trading gems. Wildcards (`geckonomy.pay.*`) work and default to op.

A currency is usable only when the config flag allows it **and** the player holds the node. The flags
are the hard gate: `transferable: false` disables `/pay` for that currency for everyone, permissions or
not.

## Languages

`plugins/Geckonomy/lang/en.yml` is written on first start and **never overwritten**, so your edits
survive updates. To translate: copy it to `de.yml`, translate the values (keep the keys and the
`<placeholders>`), set `settings.language: de`, and reload. A missing key falls back to English rather
than showing you a raw key. See [docs/LOCALIZATION.md](docs/LOCALIZATION.md).

## Placeholders

With [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) installed, Geckonomy
registers a `geckonomy` expansion for scoreboards, tab lists, holograms and chat. It is read-only —
nothing here can change a balance — and it never queries the database on the tick thread, so it is
safe to render every tick for every player. Without PlaceholderAPI, Geckonomy runs normally and says
so in the log.

A `[_<currency>]` suffix is optional on every placeholder; leave it off for the default currency.

| Placeholder | Shows |
|---|---|
| `%geckonomy_balance%` | the player's balance, plain (`1234.5`) |
| `%geckonomy_balance_formatted%` | formatted per the currency template (`$1,234.50`) |
| `%geckonomy_balance_commas%` / `_fixed%` | grouped digits / whole units only |
| `%geckonomy_balance_name%` | the currency name agreeing with the balance (`Coin` vs `Coins`) |
| `%geckonomy_symbol%` · `_name%` · `_name_plural%` · `_digits%` | currency metadata |
| `%geckonomy_format_<amount>%` | any amount through the same formatter (`%geckonomy_format_1000%` → `$1,000.00`) |
| `%geckonomy_baltop_player_<n>%` · `_baltop_balance_<n>%` | the name / balance at rank `<n>` |
| `%geckonomy_baltop_rank%` | the player's own rank (empty beyond `baltop-size`) |

Tuning lives under `placeholders:` in `config.yml` (refresh interval, offline-balance cache, the
fallback string) — see [docs/CONFIGURATION.md](docs/CONFIGURATION.md). Two things worth knowing: an
**offline** player's balance appears a moment after the first lookup (it is fetched in the
background, never on the tick), and `%geckonomy_baltop_rank%` only knows the top `baltop-size`
players — a true rank for everyone would mean a database query per player per tick, which is what
this expansion is built to avoid.

## For developers

Other plugins should talk to Geckonomy through Vault, not through Geckonomy — it registers both the
VaultUnlocked **v2** `Economy` (multi-currency, plus an async API) and the legacy **v1**
`net.milkbowl.vault.economy.Economy` (single-currency → your default currency) at `Highest` priority.
The method-by-method mapping is in [docs/VAULT_INTEGRATION.md](docs/VAULT_INTEGRATION.md).

Building it yourself needs JDK 25:

```bash
./mvnw clean package      # -> target/geckonomy-1.0.0.jar
```

The full test suite runs on every build. Two suites exercise MariaDB through Testcontainers and are
skipped automatically when Docker is not running.

The design docs live in **[docs/](docs/)** — start with [docs/README.md](docs/README.md), which orders
them. They are written for contributors, not operators; everything you need to *run* Geckonomy is above.
