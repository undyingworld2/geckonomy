# Task M5 — Localization & Formatting

**Goal:** Externalized, MiniMessage-rendered messages and per-currency money formatting.

**Read first:** `../LOCALIZATION.md`, `../CONFIGURATION.md` (currency `format`).

## Create (`infrastructure/i18n`)
- `MessageKey` — typed keys (enum or constants) for every message in `lang/en.yml`.
- `LanguageRepository` — loads `resources/lang/<code>.yml`, exposes raw templates by key with fallback to
  `en`; warns on missing keys.
- `MiniMessageRenderer` — wraps Adventure `MiniMessage`; builds `TagResolver`s; inserts player-supplied
  strings as **unparsed**.
- `MessageService` (contract in `LOCALIZATION.md §5`): `render`, `send`, `reload`; accepts an optional
  `Locale` (unused in v1 but present).
- `resources/lang/en.yml` — complete default set matching keys used by commands (M7) and errors.

## Money formatting
- Implement/So finalize `FormatMoney` (from M4) to render `Money` via `Currency.format`:
  `<amount>` (scaled + locale-grouped), `<symbol>`, `<currency>` (singular vs plural by amount).

## Implementation notes
- Sending Components happens on the main thread; rendering is pure and thread-safe.
- Keep the `<prefix>` convenience: `MessageService` injects the `prefix` key resolver into every render.
- No hard-coded user-facing strings anywhere else in the codebase.

## Acceptance / tests
- Rendering a key with placeholders yields the expected `Component` (assert plain-text projection).
- Missing key in active language falls back to `en`; missing in `en` logs + renders raw key.
- Money formatting: `$100.00`, `5 Gems`, `1 Gem` (singular/plural), 0-digit currency renders no
  decimals.
- Player-supplied text containing MiniMessage tags is **not** parsed.
