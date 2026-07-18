# infrastructure.i18n

`MessageService`, `MiniMessageRenderer`, `LanguageRepository`, `MessageKey`, `FormatMoney`,
`CurrencyNames`.

MiniMessage rendering via Paper's bundled Adventure — no new dependency. Player-supplied text is
inserted **unparsed**, so a player cannot smuggle tags into a rendered message. Currency-owned values
(`<symbol>`, `<currency>`, `<formatted>`) are owner-authored MiniMessage instead, rendered by
`FormatMoney` to self-contained components (M10). Missing keys fall back rather than throw.
`MessageService` already takes a locale, leaving room for per-player language later.

Details in `docs/LOCALIZATION.md`. Arrives with **M5**; `FormatMoney`/`CurrencyNames` with **M10**.
