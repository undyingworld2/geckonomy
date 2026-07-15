# Geckonomy — Coding Standards

Applies to all coding agents. Goal: a codebase that reads as one hand, respects the layering, and is
testable without a server.

## 1. Language & style
- Kotlin, targeting the JVM version in `pom.xml` (Java 25). Idiomatic Kotlin: immutability by default
  (`val`), data/value classes, sealed hierarchies for closed result sets, no nullable-abuse.
- Follow the official Kotlin coding conventions. Keep functions small and single-purpose.
- No wildcard imports. Explicit visibility for public API (`internal` for cross-package-but-not-API).
- Money is **always** `BigDecimal`. Never `Double`/`Float` for amounts.
- Prefer expression bodies and immutable collections where natural. Avoid premature abstraction.

## 2. Layering rules (enforced)
- **domain** imports nothing from `application`/`infrastructure`, and no Bukkit/JDBC/Vault/config libs.
- **application** imports only `domain`.
- **infrastructure** implements `domain.port` and may use frameworks; it never leaks framework types
  into method signatures that domain/application see.
- Wiring only in the composition root (`Geckonomy.kt`). No service locators / global singletons.
- Ports are defined where they are **used** (domain), implemented where the tech lives (infrastructure).

## 3. Async & threading
- All DB IO on the `IoDispatcher`. Never touch JDBC on the Bukkit main thread.
- Never touch the Bukkit API off the main thread; hop back via `BukkitMainThread` before world/player
  interaction.
- Application/domain expose `suspend` functions; the Vault sync adapter is the only place bridging to
  blocking behavior, and only for offline accounts (bounded + logged).
- Use structured concurrency: one plugin `CoroutineScope`, cancelled on disable.

## 4. Error handling
- Cross-layer failures are typed (`EconomyError` sealed class), not exceptions. Exceptions are for
  truly exceptional/programmer errors and are caught at the adapter boundary.
- No exception may propagate into a Bukkit or Vault caller; map to result/response types.
- Log storage failures with context; never swallow silently.

## 5. Naming
- Ports: `XxxRepository`, `XxxRegistry`, `XxxLog`. Implementations: `SqlXxxRepository`, etc.
- Use cases: verb-first (`Deposit`, `TransferFunds`); facade: `EconomyService`.
- No Hungarian notation; no `Impl` suffix except where an interface has exactly one obvious impl and a
  clearer name doesn't exist (prefer descriptive names, e.g. `SqlAccountRepository`).

## 6. Testing
- Every domain type and policy has unit tests. Every use case has tests against fake ports.
- Persistence tested against both dialects (SQLite in-memory + MariaDB Testcontainers), same suite.
- Prefer deterministic tests; inject a `Clock` for time, no real sleeps.
- MockBukkit for command/listener tests; no real server needed in CI.

## 7. Formatting & docs
- KDoc on public types and non-obvious functions. Explain *why*, not *what*.
- Keep the docs in `docs/` in sync when contracts change (especially `VAULT_INTEGRATION.md` and
  `DATA_MODEL.md`).

## 8. Dependencies
- Add a dependency only when it earns its place; prefer Paper-bundled (Adventure/MiniMessage) over new
  deps. Shade + relocate anything bundled to avoid classpath clashes, or load via Paper PluginLoader.
