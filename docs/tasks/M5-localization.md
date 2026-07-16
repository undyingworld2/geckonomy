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

## Decisions taken (and where they differ from the docs above)

- **`MessageService` is a class, not the interface LOCALIZATION.md §5 specified.** One implementation,
  and its tests are better run against the real `en.yml` than a fake. LOCALIZATION.md §5 updated.
- **The fallback chain is three deep**, not the documented two: active → disk `en` → **bundled `en`** →
  raw key. `en.yml` is written once and never overwritten, so an owner who edits it freezes it at that
  version, and a later Geckonomy's new keys would render raw at players forever. The bundled layer is
  complete by construction. LOCALIZATION.md §1 updated.
- **`MessageKey` ships the full set M6/M7 need**, not just the ~15 LOCALIZATION.md §2 lists — the keys
  implied by M7's command table and SPEC §7 (`/eco take|reset`, `/geckonomy version`, no-permission,
  player-not-found, usage), plus one per `EconomyError` variant so M7's mapping is total by
  construction. Some ship unused until M7; `MessageKeyCoverageTest` keeps the enum and `en.yml` in
  lockstep in both directions.
- **`FormatMoney`'s locale comes from `settings.language`** via `Locale.forLanguageTag`, replacing
  `Locale.getDefault()`. It is a supplier, like `RoundingPolicy` and `keepHistory` in the composition
  root, because `ConfigService.restartWarnings` says nothing about `settings.language` — and that
  silence is a promise that a reload applies it.
- **`Currency.nameFor(amount)` moved the singular/plural rule into the domain**, where `FormatMoney` and
  `Placeholders.currency` now share it rather than keeping a copy each.

## Not done

- **No live-server smoke test of the messages themselves.** Nothing renders to a player until M7 wires
  commands, so `/geckonomy reload` re-reading language files and `saveResource("lang/en.yml")` writing
  them are covered by unit tests and by enable/disable, not by anyone reading a message in chat. M7's
  acceptance is where that first becomes observable.
