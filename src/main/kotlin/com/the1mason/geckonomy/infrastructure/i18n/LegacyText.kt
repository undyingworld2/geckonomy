package com.the1mason.geckonomy.infrastructure.i18n

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

/**
 * Projects a rendered [Component] to legacy section-sign text, for callers that can only return a
 * `String` — PlaceholderAPI and Vault. A gradient or hex symbol degrades to the nearest legacy color;
 * that loss is those APIs' own limit, not a bug here.
 */
fun Component.toLegacyText(): String = LEGACY.serialize(this)

private val LEGACY: LegacyComponentSerializer = LegacyComponentSerializer.legacySection()
