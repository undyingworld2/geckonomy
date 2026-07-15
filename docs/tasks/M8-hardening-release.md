# Task M8 — Hardening & Release

**Goal:** Production-readiness: robust error handling, observability, packaging, and complete docs.

**Read first:** all of `docs/`, especially `SPEC.md §5,§8`.

## Do
- **Error paths:** audit every use case + adapter for typed failures; ensure no exception reaches Bukkit
  or Vault callers. Verify DB-down behavior degrades gracefully with clear logs.
- **Observability:** structured logging of failures; warn on slow DB ops (> threshold) and on
  offline-account sync fallbacks; optional bStats metrics.
- **Config resilience:** friendly disable on invalid config; helpful startup log summarizing storage
  backend, currency count, language.
- **Packaging:** finalize PluginLoader libraries or shade relocation (no classpath clashes with other
  plugins’ Hikari/coroutines/drivers). Slim jar. Correct `paper-plugin.yml` metadata + version filtering.
- **Docs:** finalize `README`, ensure `docs/` matches shipped behavior (esp. `VAULT_INTEGRATION.md`,
  `CONFIGURATION.md`). Add a short user-facing README with install/config/commands.
- **Release build:** versioned artifact; smoke-tested on a fresh Paper server with SQLite and with
  MariaDB.

## Acceptance (v1 done — mirrors `SPEC.md §8`)
- A Vault-aware plugin transacts in ≥2 currencies against Geckonomy.
- `/pay` is atomic; forced mid-transfer failure leaves balances unchanged.
- Swapping `storage.type` sqlite↔mariadb yields identical behavior.
- All player text localized; language switch changes output.
- No main-thread DB IO under normal operation.
- Release artifact builds cleanly; docs complete.
