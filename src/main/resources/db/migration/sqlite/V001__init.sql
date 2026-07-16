-- Geckonomy V001 — initial schema (SQLite). See docs/DATA_MODEL.md §1.
--
-- Every statement is IF NOT EXISTS on purpose. MigrationRunner wraps this file in a transaction, and
-- SQLite makes DDL transactional so a failure here rolls back cleanly — but the MariaDB copy of this
-- migration cannot have that (DDL implicitly commits there), so it re-runs after a partial failure.
-- Keeping both dialects re-runnable means the same recovery story applies to both.
--
-- Statements are separated by a semicolon at the end of a line; the runner splits on that, so do not
-- put a semicolon inside a string literal.

CREATE TABLE IF NOT EXISTS gk_account (
    id         TEXT    NOT NULL PRIMARY KEY, -- canonical UUID text; see SqliteDialect
    name       TEXT    NOT NULL,             -- display only; never identity (DATA_MODEL.md §8)
    type       TEXT    NOT NULL,             -- AccountType; SHARED reserved for post-v1
    created_at INTEGER NOT NULL              -- epoch millis
);

CREATE TABLE IF NOT EXISTS gk_balance (
    account_id    TEXT    NOT NULL,
    currency_code TEXT    NOT NULL,
    scope_key     TEXT    NOT NULL, -- '@global' or settings.server-id (DATA_MODEL.md §7)
    amount        INTEGER NOT NULL, -- minor units at SqlDialect.MONEY_SCALE
    PRIMARY KEY (account_id, currency_code, scope_key),
    FOREIGN KEY (account_id) REFERENCES gk_account (id) ON DELETE CASCADE
);

-- Serves /baltop: ranks one currency within one scope. Ordering by amount is correct here only
-- because amount is an INTEGER — a decimal string would sort lexically and mis-rank negatives.
CREATE INDEX IF NOT EXISTS idx_gk_balance_top ON gk_balance (currency_code, scope_key, amount);

-- No foreign key to gk_account: the ledger outlives the account it describes when
-- settings.keep-transaction-history is on (DATA_MODEL.md §6), which a foreign key would forbid.
CREATE TABLE IF NOT EXISTS gk_transaction (
    id                TEXT    NOT NULL PRIMARY KEY,
    account_id        TEXT    NOT NULL,
    currency_code     TEXT    NOT NULL,
    scope_key         TEXT    NOT NULL,
    delta             INTEGER NOT NULL, -- minor units, signed
    resulting_balance INTEGER NOT NULL, -- minor units
    type              TEXT    NOT NULL, -- TransactionType
    source_plugin     TEXT,             -- Vault caller's plugin name, or 'geckonomy'
    counterparty_id   TEXT,             -- transfers only
    created_at        INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_gk_transaction_account ON gk_transaction (account_id, created_at);

-- RESERVED: created so shared/bank accounts need no schema break later. Empty in v1.
CREATE TABLE IF NOT EXISTS gk_account_member (
    account_id  TEXT    NOT NULL,
    member_id   TEXT    NOT NULL,
    is_owner    INTEGER NOT NULL DEFAULT 0,
    permissions INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, member_id)
);

CREATE TABLE IF NOT EXISTS gk_schema_version (
    version    INTEGER NOT NULL PRIMARY KEY, -- one row per applied migration
    applied_at INTEGER NOT NULL
);
