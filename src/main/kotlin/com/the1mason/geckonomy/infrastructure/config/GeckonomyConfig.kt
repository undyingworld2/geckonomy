package com.the1mason.geckonomy.infrastructure.config

import com.the1mason.geckonomy.domain.model.Currency

/**
 * One validated `config.yml`, as typed objects. Produced by [ConfigLoader]; nothing outside
 * `infrastructure.config` ever sees the raw YAML (CONFIGURATION.md §5).
 *
 * Its existence is a promise that validation passed: exactly one currency is the default, codes are
 * unique and well-formed, the fields [StorageConfig.type] needs are present. Consumers may rely on
 * those without re-checking.
 *
 * @property currencies in the order the file declared them, which is the order `/balance` and
 *   `/baltop` will list them in.
 */
data class GeckonomyConfig(
    val storage: StorageConfig,
    val currencies: List<Currency>,
    val settings: SettingsConfig,
)
