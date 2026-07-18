# application.service

`EconomyService` — the facade of `suspend` functions that every adapter (commands, Vault providers)
calls. Delegates to the use cases; holds no business logic itself.

What it adds is the two conveniences that every adapter would otherwise repeat: defaulting the
currency to the configured default (SPEC FR-B1), and attributing a change to `geckonomy` unless the
caller names itself. Callers pass a `CurrencyCode` and a `BigDecimal`, never a resolved `Currency` —
resolving is the use case's job, so the unknown-currency rule lives in one place and adapters stay
dumb. `Money` comes back out, because it carries the `Currency` `infrastructure.i18n.FormatMoney` needs
to render it — that renderer is handed to callers directly rather than through this facade (M10; see
`application/README.md`), so `EconomyService` itself has no formatting method.

`currencies()` and `defaultCurrency()` are deliberately **not** `suspend` and return no `Outcome`: the
registry is an in-memory map. Dressing a map lookup as an async fallible call would tell every caller
to treat it like a database round trip.

Arrived with **M4**.
