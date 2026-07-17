package com.the1mason.geckonomy.infrastructure.config

import kotlin.time.Duration

/**
 * The `placeholders` section of `config.yml` (CONFIGURATION.md §2).
 *
 * Every field here is reloadable, which is why `ConfigService.restartWarnings` says nothing about
 * them — consumers read them through suppliers, per call.
 *
 * @property baltopRefresh floored at 5s by the loader: a `0` would spin the IO pool against the
 *   database forever.
 * @property offlineCacheTtl how long an offline player's balance is served before it is re-read
 *   behind the next render.
 * @property fallback rendered when the shape is understood but the value is not known yet — an
 *   offline player whose first read has not landed, or a rank beyond `baltop-size`.
 */
data class PlaceholderConfig(
    val baltopRefresh: Duration,
    val offlineCacheTtl: Duration,
    val fallback: String,
)
