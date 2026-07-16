package com.the1mason.geckonomy.infrastructure.i18n

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import java.util.Locale

/**
 * The one place player-facing text is produced (LOCALIZATION.md §5, SPEC.md FR-L1).
 *
 * A class, not an interface: there is exactly one implementation, and the thing a test wants is not a
 * fake but *this* service reading the real `en.yml` — an assertion against a stub proves the caller
 * passed a key, whereas an assertion against the real templates proves the message exists, has the
 * placeholders the caller filled, and reads correctly (CODING_STANDARDS.md §5).
 *
 * **Threading.** [render] is pure and safe from any thread — a command formats its reply on whatever
 * thread the use case returned on. [send] touches an [Audience] and belongs on the main thread
 * (CODING_STANDARDS.md §3). [reload] does file IO and must not run on the main thread.
 *
 * @param languages the template source and its fallback chain.
 * @param language the active language code, read per call rather than captured — `settings.language`
 *   is reloadable, and [reload] is what applies it.
 * @param renderer injectable for tests; renders templates to components.
 */
class MessageService(
    private val languages: LanguageRepository,
    private val language: () -> String,
    private val renderer: MiniMessageRenderer = MiniMessageRenderer(),
) {

    /**
     * The rendered `prefix`, cached because it is prepended to nearly every message and re-parsing it
     * per render would be pure waste. Recomputed by [reload]; volatile so a reload publishes it whole.
     */
    @Volatile
    private var prefix: Component = renderPrefix()

    /**
     * [key]'s message, with [resolvers] and `<prefix>` applied.
     *
     * @param locale reserved for per-player language and ignored in v1 — the parameter exists now so
     *   that adding it later is additive rather than a signature change at every call site
     *   (LOCALIZATION.md §1).
     */
    fun render(key: MessageKey, resolvers: TagResolver = TagResolver.empty(), locale: Locale? = null): Component =
        renderer.render(languages.template(key), TagResolver.resolver(prefixResolver(), resolvers))

    /** [key]'s message, sent to [audience]. Main thread only — it touches Bukkit through the audience. */
    fun send(audience: Audience, key: MessageKey, resolvers: TagResolver = TagResolver.empty(), locale: Locale? = null) {
        audience.sendMessage(render(key, resolvers, locale))
    }

    /**
     * Re-reads the language files and re-renders the prefix (CONFIGURATION.md §4).
     *
     * Blocking file IO; `/geckonomy reload` calls it off the main thread.
     */
    fun reload() {
        languages.reload(language())
        prefix = renderPrefix()
    }

    /** Injected into every render, which is what lets any template open with `<prefix>`. */
    private fun prefixResolver(): TagResolver = Placeholder.component("prefix", prefix)

    /**
     * The prefix template, rendered with no prefix resolver of its own.
     *
     * That omission is the point: a `prefix` value that itself contained `<prefix>` would otherwise
     * recurse. With no resolver for it, MiniMessage leaves the tag as literal text — visibly odd in
     * the one message that is misconfigured, rather than a stack overflow at boot.
     */
    private fun renderPrefix(): Component =
        renderer.render(languages.template(MessageKey.PREFIX), TagResolver.empty())
}
