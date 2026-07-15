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
    format: "<symbol><amount>"     # display template (see LOCALIZATION.md)
  - code: "gems"
    singular: "Gem"
    plural: "Gems"
    symbol: "◆"
    fractional-digits: 0
    starting-balance: 0
    default: false
    format: "<amount> <currency>"

# ── Settings ────────────────────────────────────────────────────────────
settings:
  language: "en"          # language file under lang/, falls back to en
  allow-overdraft: false  # if true, balances may go negative
  rounding-mode: HALF_UP  # any java.math.RoundingMode
  keep-transaction-history: true
  baltop-size: 10
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
| Key | Type | Notes |
|---|---|---|
| `code` | string | Unique, lowercase, `[a-z0-9_-]`. |
| `singular` / `plural` | string | Display names. |
| `symbol` | string | Currency symbol. |
| `fractional-digits` | int ≥ 0 | Decimal places; `0` = whole units. |
| `starting-balance` | decimal | Seeded on account creation. |
| `default` | bool | Exactly one `true` across the list. |
| `format` | string | Display template; placeholders `<symbol>`, `<amount>`, `<currency>`. |

### `settings`
| Key | Type | Default | Notes |
|---|---|---|---|
| `language` | string | `en` | Language file name (without `.yml`). |
| `allow-overdraft` | bool | `false` | Permit negative balances. |
| `rounding-mode` | enum | `HALF_UP` | `java.math.RoundingMode` name. |
| `keep-transaction-history` | bool | `true` | Retain ledger on account delete. |
| `baltop-size` | int | 10 | `/baltop` row count. |

## 3. Validation rules (fail fast on load)

- `storage.type` ∈ {`sqlite`, `mariadb`}; required backend fields present.
- `currencies` non-empty; **exactly one** `default: true`.
- Currency `code`s unique and well-formed; `fractional-digits ≥ 0`.
- `rounding-mode` is a valid `RoundingMode`.
- On validation failure: log a clear error and **disable the plugin** rather than run misconfigured.

## 4. Reload semantics

`/geckonomy reload` re-reads `config.yml` and language files, rebuilds the `CurrencyRegistry` and
`MessageService`. **Storage connection changes** (type/credentials) require a server restart in v1 (log a
warning if they changed); currency and message changes apply live. Removing a currency that still has
balances is warned about, not auto-deleted.

## 5. Recommended library

Typed, comment-preserving config via **SpongePowered Configurate** (`configurate-yaml`) is recommended;
Bukkit `YamlConfiguration` is the lighter fallback. Either way, config is mapped to the typed objects
above so the rest of the code never touches raw YAML. (Final pick is an M2 decision — see plan §13.)
