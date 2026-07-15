# Geckonomy — Localization & Formatting

All player-facing text is externalized to language files and rendered with **Adventure MiniMessage**
(bundled in Paper — no extra dependency). No user-visible string is hard-coded.

## 1. Files

- `resources/lang/en.yml` — the default/fallback language, shipped complete.
- `resources/lang/<code>.yml` — additional languages (e.g. `de.yml`, `ru.yml`).
- Active language: `settings.language` in `config.yml`. Missing keys fall back to `en`; a missing key in
  `en` logs a warning and renders the raw key.

v1 uses a **single server-wide language**. Per-player language is reserved (the `MessageService` API is
shaped to accept an optional locale so it can be added without churn).

## 2. Language file format

Flat, dotted keys → MiniMessage strings.

```yaml
# lang/en.yml
prefix: "<gray>[<aqua>Geckonomy</aqua>]</gray> "

balance:
  self: "<prefix><green>Balance:</green> <white><formatted></white>"
  other: "<prefix><white><target></white>'s balance: <white><formatted></white>"
pay:
  sent: "<prefix><green>Sent <white><formatted></white> to <white><target></white>.</green>"
  received: "<prefix><green>Received <white><formatted></white> from <white><sender></white>.</green>"
  insufficient: "<prefix><red>You don't have <white><formatted></white>.</red>"
error:
  unknown-currency: "<prefix><red>Unknown currency: <white><currency></white>.</red>"
  invalid-amount: "<prefix><red>Invalid amount.</red>"
  account-not-found: "<prefix><red>No account for <white><target></white>.</red>"
admin:
  given: "<prefix><green>Gave <white><formatted></white> to <white><target></white>.</green>"
  set: "<prefix><green>Set <white><target></white>'s balance to <white><formatted></white>.</green>"
  reloaded: "<prefix><green>Configuration reloaded.</green>"
baltop:
  header: "<prefix><gold>Top balances (<currency>)</gold>"
  entry: "<gray><rank>.</gray> <white><name></white> — <formatted>"
```

`<prefix>` is a convenience the `MessageService` injects (the `prefix` key) so every message can lead
with it.

## 3. Placeholders

Resolved via MiniMessage `TagResolver`s built per message from context. Standard tags:

| Tag | Meaning |
|---|---|
| `<amount>` | Raw numeric amount (scaled to currency digits) |
| `<formatted>` | Fully formatted money (symbol + amount per currency `format`) |
| `<symbol>` | Currency symbol |
| `<currency>` | Currency display name (singular/plural chosen by amount) |
| `<balance>` | Resulting balance, formatted |
| `<player>` / `<sender>` / `<target>` | Player names |
| `<rank>` / `<name>` | Baltop row |

Player-supplied strings are inserted as **unparsed** (`Placeholder.unparsed`) so players can't inject
MiniMessage tags; only the template author's markup is parsed.

## 4. Money formatting

`FormatMoney` renders a `Money` using its `Currency.format` template:
- `<amount>` → amount at the currency's `fractional-digits`, grouped by locale.
- `<symbol>` → `currency.symbol`.
- `<currency>` → `singular` when amount == 1 else `plural`.

Examples: `format: "<symbol><amount>"` → `$100.00`; `format: "<amount> <currency>"` → `5 Gems`,
`1 Gem`.

## 5. `MessageService` contract

```kotlin
interface MessageService {
    fun render(key: MessageKey, resolvers: TagResolver, locale: Locale? = null): Component
    fun send(audience: Audience, key: MessageKey, resolvers: TagResolver, locale: Locale? = null)
    fun reload()
}
```
- Returns Adventure `Component`s; sending happens on the main thread.
- `locale` is accepted now (unused in v1) to keep per-player language additive.

## 6. Adding a language

Copy `en.yml` to `<code>.yml`, translate values (keep keys + tags), set `settings.language: <code>`,
`/geckonomy reload`. Keep MiniMessage tags and placeholders intact.
