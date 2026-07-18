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

# Optional (M10, SPEC.md FR-L5): per-language currency name overrides, keyed by currency code.
# Partial and optional — a missing currency, or a missing key within one, falls back to config.yml.
currencies:
  coins:
    singular: "Coin"
    plural: "Coins"
  gems:
    singular: "Gem"
    plural: "Gems"
```

`<prefix>` is a convenience the `MessageService` injects (the `prefix` key) so every message can lead
with it.

The `currencies:` block is not a message — it holds no `<placeholder>`s and is not tracked by
`MessageKey`/`MessageKeyCoverageTest`. It exists solely so a translator can give a currency a
localized name without touching `config.yml`; see §4.

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
MiniMessage tags; only the template author's markup is parsed. `infrastructure.i18n.Placeholders` is
the single place that builds these resolvers, so a caller cannot get either rule below wrong by
forgetting.

**Currency-owned values are different, as of M10 (SPEC.md FR-L4).** `<symbol>`, `<currency>` and
`<formatted>` come from `config.yml`/a lang file — owner-authored MiniMessage, not player input — and
`FormatMoney` renders each to a **self-contained** `Component` before it is inserted
(`Placeholder.component`, not `Placeholder.unparsed`). A `symbol: "<gradient:#f00:#00f>◆</gradient>"`
therefore renders styled, and because it is already a finished component tree by the time it is
spliced in, its style cannot bleed into the rest of the message — the same reason `<prefix>` has always
worked this way. This reverses only the currency-value half of the original "everything is unparsed"
rule; player-supplied text (`<target>`, `<sender>`, `<name>`, raw amounts) is untouched and still always
unparsed.

A tag the template names but the sender did not supply renders as **literal text** rather than throwing:
language files are edited by server owners, and a typo should look wrong, not kill the command.

## 4. Money formatting

`FormatMoney` (`infrastructure.i18n`) renders a `Money` into a `Component`, using its `Currency.format`
template:
- `<amount>` → amount at the currency's `fractional-digits`, grouped by locale (unparsed — a formatted
  number cannot carry markup, so parsed-vs-unparsed makes no difference here, but the rule stays
  uniform).
- `<symbol>` → `currency.symbol`, MiniMessage-rendered to a self-contained component (§3).
- `<currency>` → the name for `Currency.roleFor(amount)` (singular for exactly one, else plural),
  resolved through `CurrencyNames` — a lang file's `currencies.<code>.singular|plural` override if
  present, else `config.yml`'s own value (SPEC.md FR-L5) — then MiniMessage-rendered the same way as
  `<symbol>`.

`Currency.roleFor`/`.nameFor` still own *which* role an amount selects, so the two places that render
names (this and `Placeholders.currency`) cannot disagree about a balance of exactly one; `CurrencyNames`
owns only the *string* for that role.

Examples: `format: "<symbol><amount>"` → `$100.00`; `format: "<amount> <currency>"` → `5 Gems`,
`1 Gem`.

**In `infrastructure`, not `application`.** A `Component` is a framework type, and `application` may
import only `domain` (CODING_STANDARDS.md §2) — so `FormatMoney` lives in `infrastructure.i18n`, and
`EconomyService` no longer holds a reference to it. Every consumer (commands, PlaceholderAPI, both
Vault providers) is handed the **same** instance directly by the composition root, so a command and a
placeholder cannot disagree (SPEC.md FR-P5).

**Plain/legacy projections.** Callers that can only return a `String` — PlaceholderAPI and Vault's
`format(double)`/`currencyNameSingular()` family — call `Component.toLegacyText()`
(`LegacyComponentSerializer.legacySection()`), so a scoreboard or a shop sign shows section-code colours
approximating whatever MiniMessage the symbol/name used; a gradient or hex colour degrades to the
nearest legacy colour. That loss is those APIs' own limit, not a bug in `FormatMoney`.

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
