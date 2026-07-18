# Task M10 — Styled & Localizable Currency Display

**Goal:** Let owners **style** a currency's symbol and names with MiniMessage, and let translators
**localize** the names in language files — without ever letting player input inject markup.

**Read first:** `../LOCALIZATION.md §2–4`, `../CONFIGURATION.md §2`, `../SPEC.md §4.6 (FR-L4, FR-L5)`.

## Background — what changes and why

Today `symbol`, `singular`, `plural` are per-currency strings in `config.yml`, and everything a
resolver inserts is **unparsed** (`LOCALIZATION.md §3`) — including `<symbol>` and `<currency>`. That
was the safe default (a `symbol: "<rainbow>"` must not colour the rest of the message), but it also
means an owner cannot *deliberately* style a symbol, and the names live only in `config.yml` so a
translator cannot localize them. M10 keeps the safety property while lifting both limits: currency-owned
values become **owner-authored MiniMessage rendered as self-contained components**, and names become
overridable per language.

## Change (`infrastructure/i18n`)

- **`Placeholders`** — insert the currency-owned tags (`<symbol>`, `<currency>`, `<formatted>`) via
  `Placeholder.component(...)` from a pre-rendered, self-contained `Component`, **not**
  `Placeholder.unparsed`. Player-owned tags (`<target>`, `<player>`, `<sender>`, raw amounts) **stay**
  unparsed. This reverses only the currency-value half of `LOCALIZATION.md §3`; FR-L2's player-input
  invariant is untouched. A self-contained component is why a `<gradient>` symbol styles only itself
  and cannot bleed — it is already a finished `Component`, not markup spliced into the message string.
- **`FormatMoney`** — render the `format` template as MiniMessage into a `Component`, with `<symbol>`
  and `<currency>` supplied as their own rendered components. Expose a **plain/legacy-text projection**
  (`PlainTextComponentSerializer` / `LegacyComponentSerializer`) for callers that need a `String`
  (PAPI, logs). One formatter still, so a command and a placeholder cannot disagree (FR-P5).
- **`CurrencyNames`** (new, or fold into the message layer) — resolves a currency's singular/plural
  from the active language's `currencies.<code>` block, falling back to `Currency.singular/plural`.
  `FormatMoney`'s `<currency>` and `Placeholders.currency` both consult it, so the name string stays
  **single-sourced** the way `Currency.nameFor` kept the selection rule single-sourced (LOCALIZATION.md
  §4). Rebuilt on reload alongside `MessageService`.

## Change (`domain` / `infrastructure/config`)

- `Currency.nameFor(amount)` still owns *which* form (1 vs not) for M10 — M11 generalizes it. The name
  *string* now flows through `CurrencyNames`, so `nameFor` selects `singular`-vs-`plural` **role**, and
  the resolver turns the role into the effective string (lang override → config).
- `Currency.symbol/singular/plural` stay raw MiniMessage **source**; nothing pre-renders them at load.
- No config-schema change: `symbol`/`singular`/`plural`/`format` keys are unchanged, only reinterpreted
  as MiniMessage.

## Change (PAPI, `infrastructure/placeholder`)

- `PlaceholderResolver` returns the styled name/symbol via `LegacyComponentSerializer.legacySection()`
  (PlaceholderAPI returns `String`). Document that a scoreboard therefore shows section-code colours,
  and that a symbol using gradients/hex degrades to the nearest legacy colour.

## Lang schema (`LOCALIZATION.md §2`)

Optional `currencies:` map, code → name overrides:
```yaml
currencies:
  coins:
    singular: "Coin"
    plural: "Coins"
  gems:
    singular: "Gem"
    plural: "Gems"
```
The block is optional and partial: a missing currency, or a missing `singular`/`plural` within one,
falls back to `config.yml`. Update the bundled `lang/en.yml` with an English block and `lang/ru.yml`
with Russian singular/plural (an approximation until M11's plural categories land). `MessageKey` is
unaffected — these are not message keys; keep them out of `MessageKeyCoverageTest`.

## Docs to update as part of this milestone

- `LOCALIZATION.md §3` — the unparsed rule now applies to **player input**; currency tags are rendered
  self-contained components. `§4` — `FormatMoney` returns a `Component` (+ legacy projection for PAPI).
  `§2` — document the `currencies:` override block.
- `CONFIGURATION.md §2` — `singular`/`plural`/`symbol`/`format` accept MiniMessage; names overridable
  per language.
- Shipped `config.yml` — comment that these fields accept MiniMessage.

## Acceptance / tests

- A styled symbol (`symbol: "<gradient:#f00:#00f>◆</gradient>"`) renders styled **and does not bleed**:
  assert the message run *after* the symbol carries none of its style.
- Player-supplied text containing MiniMessage (`<red>evil`, a player named `<rainbow>`) renders as
  literal text, unchanged from v1 behavior.
- A `currencies.<code>` lang override wins over `config.yml`; a partial block falls back per key; a
  currency absent from the block uses config; `/geckonomy reload` applies a name change live.
- PAPI name/symbol placeholders return correct legacy-serialized text.
- The existing suite (770 at M9) stays green; no message key added.
