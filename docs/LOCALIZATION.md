# Geckonomy — Localization & Formatting

All player-facing text is externalized to language files and rendered with **Adventure MiniMessage**
(bundled in Paper — no extra dependency). No user-visible string is hard-coded.

## 1. Files

- `plugins/Geckonomy/lang/en.yml` — the default language, written out on first enable and **never
  overwritten**, so an owner's edits survive an upgrade.
- `plugins/Geckonomy/lang/<code>.yml` — additional languages (e.g. `de.yml`, `ru.yml`).
- Active language: `settings.language` in `config.yml`.

**Fallback chain.** A key is resolved in order:

1. the active language file,
2. the server's own `en.yml`,
3. the `en.yml` **bundled in the jar**,
4. the raw key, with a one-time warning.

Layer 3 is the upgrade insurance, and it is why the chain is three deep rather than two. Because
`en.yml` is written once and never overwritten, the moment an owner edits it, it is frozen at the
version that created it — a later Geckonomy that adds a message would find that key in no file on disk
and render `error.not-transferable` at a player. The bundled copy is complete by construction
(`MessageKeyCoverageTest` asserts it against `MessageKey` in both directions), so layer 4 is
unreachable in a correct build and exists only to describe what a broken one does.

A language file that is missing or unparseable is warned about and skipped, not fatal: a broken
translation degrades to English rather than taking the server down.

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
  no-currency-permission: "<prefix><red>You can't use <white><currency></white> here.</red>"
  not-transferable: "<prefix><red><white><currency></white> can't be paid to other players.</red>"
  others-hidden: "<prefix><red>You can't view others' <white><currency></white> balance.</red>"
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
| `<version>` | Plugin version (`/geckonomy version`) |
| `<usage>` | Correct syntax, on a usage error |

Player-supplied strings are inserted as **unparsed** (`Placeholder.unparsed`) so players can't inject
MiniMessage tags; only the template author's markup is parsed.

**Everything** a resolver inserts is unparsed, not only player names — `infrastructure.i18n.Placeholders`
is the single place that builds them, so a caller cannot forget. `<symbol>` and `<formatted>` matter as
much as `<target>` here: a currency's `symbol` comes from `config.yml`, so a symbol of `<rainbow>` would
otherwise colour the rest of the message from inside what is meant to be a value.

A tag the template names but the sender did not supply renders as **literal text** rather than throwing:
language files are edited by server owners, and a typo should look wrong, not kill the command.

## 4. Money formatting

`FormatMoney` renders a `Money` using its `Currency.format` template:
- `<amount>` → amount at the currency's `fractional-digits`, grouped by locale.
- `<symbol>` → `currency.symbol`.
- `<currency>` → `singular` when amount == 1 else `plural` (`Currency.nameFor`, which owns that rule so
  the two places that render names cannot disagree about a balance of exactly one).

Examples: `format: "<symbol><amount>"` → `$100.00`; `format: "<amount> <currency>"` → `5 Gems`,
`1 Gem`.

**Locale.** Grouping comes from `Locale.forLanguageTag(settings.language)`, not the host JVM's default.
`settings.language` names a file rather than a locale, but deriving one from it is what keeps text and
numbers agreeing — a German server reads German messages *and* `1.000,00`, instead of one of each — and
it makes formatting deterministic wherever the server happens to run. The locale is supplied per call,
not captured, so `/geckonomy reload` applies a language change to money as well as to text.

## 5. `MessageService` contract

```kotlin
class MessageService(
    languages: LanguageRepository,
    language: () -> String,
    renderer: MiniMessageRenderer = MiniMessageRenderer(),
) {
    fun render(key: MessageKey, resolvers: TagResolver = TagResolver.empty(), locale: Locale? = null): Component
    fun send(audience: Audience, key: MessageKey, resolvers: TagResolver = TagResolver.empty(), locale: Locale? = null)
    fun reload()
}
```
- Returns Adventure `Component`s; `render` is pure and thread-safe, `send` is main-thread, `reload` does
  file IO and must not run on the main thread.
- A **class**, not an interface (this doc previously specified one): there is exactly one
  implementation, and what a test wants is not a fake but *this* service reading the real `en.yml` — an
  assertion against a stub proves a key was passed, where one against the real templates proves the
  message exists, carries the placeholders its caller fills, and reads correctly.
- `language` is a supplier, not a value: `settings.language` is reloadable, and `reload()` is what
  applies it.
- `locale` is accepted now (unused in v1) to keep per-player language additive.
- `<prefix>` is injected into every render. The prefix itself is rendered *without* that resolver, so a
  `prefix` value containing `<prefix>` renders the tag literally instead of recursing.

## 6. Adding a language

Copy `en.yml` to `<code>.yml`, translate values (keep keys + tags), set `settings.language: <code>`,
`/geckonomy reload`. Keep MiniMessage tags and placeholders intact.
