-- Geckonomy V001 — initial schema (MariaDB). See docs/DATA_MODEL.md §1.
--
-- Every statement is IF NOT EXISTS on purpose, and here it is load-bearing rather than tidy: MariaDB
-- implicitly commits each DDL statement, so MigrationRunner's transaction cannot roll this file back.
-- A failure halfway leaves the tables it already made; the version row is never recorded, so the next
-- start re-runs the file and IF NOT EXISTS carries it over what already exists.
--
-- Statements are separated by a semicolon at the end of a line; the runner splits on that, so do not
-- put a semicolon inside a string literal.

CREATE TABLE IF NOT EXISTS gk_account (
    id         BINARY(16)  NOT NULL PRIMARY KEY, -- 16 bytes, big-endian; see MariaDbDialect
    name       VARCHAR(64) NOT NULL,             -- display only; never identity (DATA_MODEL.md §8)
    type       VARCHAR(16) NOT NULL,             -- AccountType; SHARED reserved for post-v1
    created_at BIGINT      NOT NULL              -- epoch millis
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS gk_balance (
    account_id    BINARY(16)     NOT NULL,
    currency_code VARCHAR(32)    NOT NULL,
    scope_key     VARCHAR(64)    NOT NULL, -- '@global' or settings.server-id (DATA_MODEL.md §7)
    amount        DECIMAL(38, 4) NOT NULL, -- scale is SqlDialect.MONEY_SCALE
    PRIMARY KEY (account_id, currency_code, scope_key),
    FOREIGN KEY (account_id) REFERENCES gk_account (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Serves /baltop: ranks one currency within one scope.
CREATE INDEX IF NOT EXISTS idx_gk_balance_top ON gk_balance (currency_code, scope_key, amount);

-- No foreign key to gk_account: the ledger outlives the account it describes when
-- settings.keep-transaction-history is on (DATA_MODEL.md §6), which a foreign key would forbid.
CREATE TABLE IF NOT EXISTS gk_transaction (
    id                BINARY(16)     NOT NULL PRIMARY KEY,
    account_id        BINARY(16)     NOT NULL,
    currency_code     VARCHAR(32)    NOT NULL,
    scope_key         VARCHAR(64)    NOT NULL,
    delta             DECIMAL(38, 4) NOT NULL, -- signed
    resulting_balance DECIMAL(38, 4) NOT NULL,
    type              VARCHAR(16)    NOT NULL, -- TransactionType
    source_plugin     VARCHAR(64),             -- Vault caller's plugin name, or 'geckonomy'
    counterparty_id   BINARY(16),              -- transfers only
    created_at        BIGINT         NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX IF NOT EXISTS idx_gk_transaction_account ON gk_transaction (account_id, created_at);

-- RESERVED: created so shared/bank accounts need no schema break later. Empty in v1.
CREATE TABLE IF NOT EXISTS gk_account_member (
    account_id  BINARY(16) NOT NULL,
    member_id   BINARY(16) NOT NULL,
    is_owner    TINYINT(1) NOT NULL DEFAULT 0,
    permissions INT        NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS gk_schema_version (
    version    INT    NOT NULL PRIMARY KEY, -- one row per applied migration
    applied_at BIGINT NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
