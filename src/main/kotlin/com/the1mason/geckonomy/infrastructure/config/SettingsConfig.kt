package com.the1mason.geckonomy.infrastructure.config

import java.math.RoundingMode

/**
 * Server-wide behavior — the `settings` section of `config.yml` (CONFIGURATION.md §2).
 *
 * @property serverId identifies this server instance; the scope key for per-server currencies
 *   (DATA_MODEL.md §7). Must be unique among servers sharing a database — two servers claiming the
 *   same id silently share balances that were meant to be private. Read at startup by M3's
 *   `ScopeResolver`; a change needs a restart.
 * @property language language file under `lang/`, without the extension.
 * @property allowOverdraft whether balances may go below zero.
 * @property roundingMode how amounts are rounded to a currency's fractional digits.
 * @property keepTransactionHistory whether the ledger survives account deletion.
 * @property baltopSize rows `/baltop` shows.
 */
data class SettingsConfig(
    val serverId: String,
    val language: String,
    val allowOverdraft: Boolean,
    val roundingMode: RoundingMode,
    val keepTransactionHistory: Boolean,
    val baltopSize: Int,
)
