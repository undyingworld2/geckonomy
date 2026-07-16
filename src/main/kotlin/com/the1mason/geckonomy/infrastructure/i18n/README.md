# infrastructure.i18n

`MessageService`, `MiniMessageRenderer`, `LanguageRepository`, `MessageKey`.

MiniMessage rendering via Paper's bundled Adventure — no new dependency. Player-supplied text is
inserted **unparsed**, so a player cannot smuggle tags into a rendered message. Missing keys fall back
rather than throw. `MessageService` already takes a locale, leaving room for per-player language later.

Details in `docs/LOCALIZATION.md`. Arrives with **M5**.
