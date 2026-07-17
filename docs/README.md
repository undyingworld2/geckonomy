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
8. **[ROADMAP.md](ROADMAP.md)** — milestones M0–M9 + future, acceptance criteria.
9. **[CODING_STANDARDS.md](CODING_STANDARDS.md)** — Kotlin style, layering, testing rules.

## For coding agents
Each milestone has a self-contained task spec in **[tasks/](tasks/)** (`M0`…`M9`). Take one milestone at
a time, in order; each lists files to create, implementation notes, and acceptance criteria.

## Locked v1 decisions
- Personal (owner-only) accounts; schema is **shared-account-ready** for later banks.
- Currencies are **config-defined**, one default, and each has a **scope**: `network` (balance shared
  across servers on one DB) or `server` (balance private to a server instance). Never per-world; the
  Vault `world` param is accepted and ignored.
- Per-currency **command permissions** and hard config flags (`transferable`, `balance-check-others`,
  `show-in-baltop`) gate `balance`/`pay`/`baltop`.
- **DB is the single source of truth**; all IO runs off the main thread. A minimal online-player mirror
  serves the synchronous Vault path; a network currency on MariaDB refreshes behind the read until
  Redis sync ships.
- Register **both** the VaultUnlocked **v2** `Economy` (multi-currency) **and** the legacy **v1**
  `net.milkbowl.vault.economy.Economy` (single-currency → default) from the first release — both ship in
  the existing VaultUnlockedAPI dependency.

## Reference
Pin a local copy of VaultUnlockedAPI at `.reference/VaultUnlockedAPI/` (git-ignored) for the exact
interface source:
```
git clone --depth 1 https://github.com/TheNewEconomy/VaultUnlockedAPI.git .reference/VaultUnlockedAPI
```
