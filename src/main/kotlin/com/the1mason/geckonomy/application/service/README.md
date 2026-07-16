# application.service

`EconomyService` — the facade of `suspend` functions that every adapter (commands, Vault providers)
calls. Delegates to the use cases; holds no business logic itself.

What it adds is the two conveniences that every adapter would otherwise repeat: defaulting the
currency to the configured default (SPEC FR-B1), and attributing a change to `geckonomy` unless the
caller names itself. Callers pass a `CurrencyCode` and a `BigDecimal`, never a resolved `Currency` —
resolving is the use case's job, so the unknown-currency rule lives in one place and adapters stay
dumb. `Money` comes back out, because it carries the `Currency` the M5 renderer needs to format it.

`currencies()`, `defaultCurrency()`, and `format()` are deliberately **not** `suspend` and return no
`Outcome`: the registry is an in-memory map and formatting is a string operation. Dressing them as
async fallible calls would tell every caller to treat a map lookup like a database round trip.

Arrived with **M4**.
