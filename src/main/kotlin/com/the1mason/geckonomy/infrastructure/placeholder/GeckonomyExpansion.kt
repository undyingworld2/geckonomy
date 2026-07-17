package com.the1mason.geckonomy.infrastructure.placeholder

import com.the1mason.geckonomy.domain.model.AccountId
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

/**
 * The `%geckonomy_…%` expansion (SPEC.md FR-P1). A pass-through: every rule lives in
 * [PlaceholderResolver], which names no PlaceholderAPI type and is therefore testable.
 */
internal class GeckonomyExpansion(
    private val resolver: PlaceholderResolver,
    private val version: String,
    private val author: String,
) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "geckonomy"

    override fun getAuthor(): String = author

    override fun getVersion(): String = version

    /**
     * Survives `/papi reload`.
     *
     * The default is `false`, which means PlaceholderAPI *unregisters* an expansion its own reload
     * touches — and ours would then be gone until the next server restart, because nothing calls
     * `register()` again. An expansion a plugin registers itself must say so.
     */
    override fun persist(): Boolean = true

    /** `[_<currency>]` is optional everywhere — absent means the default currency. */
    override fun getPlaceholders(): List<String> = PlaceholderVariant.entries.map { variant ->
        val argument = variant.argumentName?.let { "_<$it>" }.orEmpty()
        "%geckonomy_${variant.keyword}$argument[_<currency>]%"
    }

    /**
     * `onRequest`, **not** `onPlaceholderRequest`.
     *
     * `PlaceholderHook.onRequest` hands the superclass method a `Player` — and passes `null` when the
     * player is offline, throwing away which player was asked about. Overriding that one instead
     * would make every offline placeholder unanswerable, which is most of a tab list.
     */
    override fun onRequest(player: OfflinePlayer?, params: String): String? =
        resolver.resolve(player?.uniqueId?.let(::AccountId), params)
}
