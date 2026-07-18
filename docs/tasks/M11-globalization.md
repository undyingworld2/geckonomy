# Task M11 — Globalization

**Goal:** Currency names that decline correctly in every language (Russian: *1 рубль / 2 рубля /
5 рублей*), and messages + numbers shown in each **player's own** locale.

**Read first:** `../SPEC.md §4.6 (FR-L6, FR-L7)`, `../LOCALIZATION.md`, the M10 output
(`M10-currency-display.md` — this builds on its `CurrencyNames` resolver and lang `currencies:` block).

> This is a requirements sketch, not a finished design. A spike is expected before the implementation
> settles (repo culture — see M7's Cloud/Brigadier spike and M9's). The one library choice is decided:
> **ICU4J `PluralRules`** (confirmed with the owner over hand-rolled per-language rules).

## Plural categories (FR-L6)

- Select the CLDR category with
  `com.ibm.icu.text.PluralRules.forLocale(locale).select(amount)` → one of
  `zero|one|two|few|many|other`. English collapses to `one`/`other`; Russian uses `one`/`few`/`many`.
- Generalize name selection from binary to category-based: either widen `Currency.nameFor` to take a
  locale and return a category, or add `CurrencyNames.forAmount(currency, amount, locale)` (preferred —
  M10 already routes names through `CurrencyNames`, and the locale lives in i18n, not the domain).
  Keep the domain free of ICU4J: category selection is an i18n concern, the domain still owns only the
  amount.
- **Back-compat:** M10's `singular`/`plural` remain valid and map to `one`/`other`. A currency that
  supplies neither categories nor singular/plural keeps rendering from `config.yml`.

## Lang schema

`currencies.<code>` accepts per-category forms alongside M10's keys:
```yaml
currencies:
  rubles:
    one:  "рубль"
    few:  "рубля"
    many: "рублей"
    other: "рубля"
```
Fallback chain for a form: requested category → `other` → M10 `plural`/`singular` → `config.yml`. A
lang file that only has `singular`/`plural` still works everywhere (they seed `one`/`other`).

## Per-player language (FR-L7)

- `EffectiveLocale` resolver: player's client locale (`Player.locale()`) → server `settings.language`
  → `en`. One place decides, so every rendered surface agrees.
- Thread it through `MessageService.send`'s **existing** `locale` param (reserved since M5) and through
  `FormatMoney`'s locale supplier, so message text, currency names, **and** number grouping all follow
  the same effective locale.
- Surfaces with no viewer: PAPI and offline paths use the **target** player's stored/last-known locale
  where available, else the server default — a scoreboard has no "current viewer" the way a chat
  message does. Decide and document per surface during the spike.

## Library

- Add ICU4J to `pom.xml` and `geckonomy-libraries.txt` (a GeckonomyLoader entry — CODING_STANDARDS §8).
- **Verify it loads under the plugin's isolated classloader.** ICU4J ships locale data as classpath
  resources; confirm they resolve from GeckonomyLoader's classloader, not the system one. This is the
  M8 lesson (DriverManager found no driver on the isolated loader) applied preemptively: a unit test is
  structurally blind to the plugin classloader, so a live enable is the only proof.
- Record in the retrospective: the jar-size cost and the confirmed choice of ICU4J over hand-rolled
  rules.

## Acceptance / tests

- Russian renders `one/few/many/other` correctly across representative amounts including a fractional
  one (`1 → рубль`, `2 → рубля`, `5 → рублей`, `1.5 → рубля`).
- English is unaffected — `1 Coin` / `5 Coins` via `one`/`other`, M10 files unchanged.
- A player with a Russian client sees Russian names/messages/grouping while another player on the same
  server sees the server default — observed on a live server.
- Number grouping follows the effective locale (`1.000,00` vs `1,000.00`) per player.
- Plugin enables and disables cleanly with ICU4J on the isolated classloader; locale data resolves.
