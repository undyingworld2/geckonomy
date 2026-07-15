# Geckonomy — Documentation

Geckonomy is a multi-currency **economy provider** for modern Paper/Spigot servers, written in Kotlin.
It owns player accounts/balances and exposes them through the **VaultUnlocked v2 API**. Clean, layered
architecture (domain / application / infrastructure); storage on **SQLite** (local) or **MariaDB**
(remote); localized MiniMessage output.

## Read in this order
1. **[SPEC.md](SPEC.md)** — scope, requirements, capability matrix, commands & permissions.
2. **[ARCHITECTURE.md](ARCHITECTURE.md)** — layers, ports, threading model, key flows.
3. **[DOMAIN_MODEL.md](DOMAIN_MODEL.md)** — entities, value objects, invariants.
4. **[DATA_MODEL.md](DATA_MODEL.md)** — DB schema per dialect, migrations, money precision.
5. **[VAULT_INTEGRATION.md](VAULT_INTEGRATION.md)** — full v2 method-by-method mapping.
6. **[CONFIGURATION.md](CONFIGURATION.md)** — annotated `config.yml`.
7. **[LOCALIZATION.md](LOCALIZATION.md)** — message system, MiniMessage, placeholders.
8. **[ROADMAP.md](ROADMAP.md)** — milestones M0–M8 + future, acceptance criteria.
9. **[CODING_STANDARDS.md](CODING_STANDARDS.md)** — Kotlin style, layering, testing rules.

## For coding agents
Each milestone has a self-contained task spec in **[tasks/](tasks/)** (`M0`…`M8`). Take one milestone at
a time, in order; each lists files to create, implementation notes, and acceptance criteria.

## Locked v1 decisions
- Personal (owner-only) accounts; schema is **shared-account-ready** for later banks.
- **Global** balances per (account, currency); the Vault `world` param is accepted and ignored.
- **DB is the single source of truth**; all IO runs off the main thread. A minimal online-player mirror
  serves the synchronous Vault path.
- Currencies are **global, config-defined**, one default.

## Reference
Pin a local copy of VaultUnlockedAPI at `.reference/VaultUnlockedAPI/` (git-ignored) for the exact
interface source:
```
git clone --depth 1 https://github.com/TheNewEconomy/VaultUnlockedAPI.git .reference/VaultUnlockedAPI
```
