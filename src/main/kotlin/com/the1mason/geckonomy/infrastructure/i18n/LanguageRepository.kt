package com.the1mason.geckonomy.infrastructure.i18n

import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import kotlin.io.path.exists
import kotlin.io.path.readText

/** The language shipped complete, and the one every other falls back to (LOCALIZATION.md §1). */
internal const val DEFAULT_LANGUAGE = "en"

/** The jar's own `en.yml`, as text, or null if this build shipped without one. */
private fun defaultBundledResource(): String? =
    LanguageRepository::class.java.getResourceAsStream("/lang/$DEFAULT_LANGUAGE.yml")
        ?.bufferedReader()
        ?.use { it.readText() }

/**
 * The raw message templates, resolved through the fallback chain (LOCALIZATION.md §1).
 *
 * A key is looked up in the active language, then the server's own `en.yml`, then the `en.yml`
 * **bundled in the jar**, and finally renders as the raw key with a warning.
 *
 * That third layer is not in LOCALIZATION.md's original two-step, and it earns its place at upgrade
 * time: `en.yml` is written to the data folder once and never overwritten, so the moment an owner
 * edits it, it is frozen at the version that created it. A later Geckonomy that adds a message would
 * find that key in no file on disk and render `error.not-transferable` at a player. The bundled copy
 * is complete by construction — `MessageKeyCoverageTest` sees to that — so it cannot happen.
 *
 * **Threading.** [reload] swaps the whole chain at once and [template] reads it through one volatile
 * read, so a render during a reload sees the old chain or the new one, never a half-built mix. This is
 * the same trick, and the same reason, as `ConfigService.current`.
 *
 * @param directory the plugin's `lang/` folder.
 * @param bundledResource the jar's own `en.yml`, as text. Injectable so the broken-build path below is
 *   reachable from a test without deleting a file out of the build output; the default is the real
 *   resource, so production has no seam to get wrong.
 */
class LanguageRepository(
    private val directory: Path,
    private val logger: Logger,
    private val bundledResource: () -> String? = ::defaultBundledResource,
) {

    /**
     * The fallback chain, most specific first. Replaced wholesale by [reload].
     *
     * Starts as the bundled language alone, so a repository that is asked for a message before its
     * first [reload] answers in English rather than in raw keys.
     */
    @Volatile
    private var layers: List<Map<String, String>> = listOf(bundled())

    /**
     * `currencies.<code>` → its keys, from the **active** language file only (SPEC.md FR-L5).
     *
     * Deliberately not part of [layers]: a currency name override's fallback is `config.yml`, not the
     * en.yml layer messages fall through to, so this never consults anything but [reload]'s own
     * `languageCode` file. Swapped wholesale alongside [layers], for the same reason.
     */
    @Volatile
    private var currencyOverrides: Map<String, Map<String, String>> = emptyMap()

    /**
     * Keys already complained about.
     *
     * Warn-once, not warn-per-render: a missing key on `/balance` is one line the first time and a
     * line per command forever after, which buries the very warning it is trying to deliver. Cleared
     * by [reload], so a fix — or a new hole — is reported again.
     */
    private val warned = ConcurrentHashMap.newKeySet<MessageKey>()

    /**
     * Re-reads the language files and swaps in the new chain.
     *
     * Blocking file IO — `MessageService.reload` is called off the main thread for this reason.
     */
    fun reload(languageCode: String) {
        layers = buildList {
            // Skipped when the active language *is* en: the next line already adds that same file, and
            // a chain that consulted it twice would be harmless but a lie about what the fallback is.
            if (languageCode != DEFAULT_LANGUAGE) {
                // Absence is diagnosed here rather than from a null out of fileLayer, because the two
                // ways to get null want opposite advice: a file that is not there means "settings.language
                // names a language you never wrote", while one that failed to parse is sitting right where
                // it belongs and needs fixing, not creating. fileLayer reports that case itself.
                if (!path(languageCode).exists()) {
                    logger.warning(
                        "Language '$languageCode' has no file at ${path(languageCode)}; using '$DEFAULT_LANGUAGE'. " +
                            "Check settings.language, or copy $DEFAULT_LANGUAGE.yml to $languageCode.yml and translate it.",
                    )
                } else {
                    fileLayer(languageCode)?.let(::add)
                }
            }
            fileLayer(DEFAULT_LANGUAGE)?.let(::add)
            add(bundled())
        }
        currencyOverrides = activeCurrencies(languageCode)
        warned.clear()
    }

    /**
     * The template for [key], or [MessageKey.path] itself when no layer has it.
     *
     * Returning the key rather than throwing or rendering nothing is deliberate: a player seeing
     * `balance.self` is a bug report that writes itself, where an empty message is a mystery and an
     * exception is a command that died over a typo in a text file.
     */
    fun template(key: MessageKey): String =
        layers.firstNotNullOfOrNull { it[key.path] } ?: missing(key)

    /**
     * `currencies.<code>.<key>` from the active language file, or null — meaning "fall back to
     * `config.yml`" (SPEC.md FR-L5), not "fall back to English". `key` is whatever a lang file wrote
     * under the currency (`singular`/`plural` today); consulted by `CurrencyNames`.
     */
    fun currencyOverride(code: String, key: String): String? = currencyOverrides[code]?.get(key)

    private fun missing(key: MessageKey): String {
        // add() answers false when the key was already there — so exactly one thread warns, once.
        if (warned.add(key)) {
            logger.warning("Message '${key.path}' is missing from every language file, including the bundled one; showing the key instead. This is a Geckonomy bug - please report it.")
        }
        return key.path
    }

    /** One language file's templates, or null if it is absent or unusable. */
    private fun fileLayer(code: String): Map<String, String>? {
        val file = path(code)
        if (!file.exists()) return null
        return runCatching { parse(file.readText()) }
            .getOrElse {
                // Not fatal, and not silent: the chain simply carries on to the next layer, which is
                // why a broken translation degrades to English instead of taking the server down.
                logger.warning("$file could not be read (${it.message ?: it}); falling back to the next language.")
                null
            }
    }

    /** The jar's own copy — the layer that makes the chain total. */
    private fun bundled(): Map<String, String> {
        val text = bundledResource()
        if (text == null) {
            // A jar without its own language file is a broken build. Raw keys everywhere is ugly but
            // survivable, and an economy that refused to start over its text would be worse.
            logger.severe("lang/$DEFAULT_LANGUAGE.yml is missing from the plugin jar; the build is broken. Messages will show as raw keys.")
            return emptyMap()
        }
        return runCatching { parse(text) }.getOrElse {
            logger.severe("The bundled lang/$DEFAULT_LANGUAGE.yml is not valid YAML (${it.message ?: it}); the build is broken.")
            emptyMap()
        }
    }

    /**
     * The active language's `currencies:` block, or empty if the file is absent or unusable.
     *
     * Silent on failure, unlike [fileLayer]: [reload] already reads and warns about this same file for
     * the message layer in the same call, so a second warning here would be about one problem, not two.
     */
    private fun activeCurrencies(languageCode: String): Map<String, Map<String, String>> {
        val file = path(languageCode)
        if (!file.exists()) return emptyMap()
        return runCatching { parseCurrencies(file.readText()) }.getOrElse { emptyMap() }
    }

    /**
     * One level of nesting preserved, unlike [parse]: whatever keys a translator wrote under each
     * currency code (`singular`/`plural` today — M11 adds `one`/`few`/`many`/`other` to the same
     * block, so this does not change again, only its caller).
     */
    private fun parseCurrencies(text: String): Map<String, Map<String, String>> {
        val yaml = YamlConfiguration()
        yaml.loadFromString(text)
        val section = yaml.getConfigurationSection(CURRENCIES_SECTION) ?: return emptyMap()
        return section.getKeys(false).associateWith { code ->
            section.getConfigurationSection(code)
                ?.let { child -> child.getKeys(false).mapNotNull { key -> child.getString(key)?.let { key to it } }.toMap() }
                ?: emptyMap()
        }
    }

    private fun path(code: String): Path = directory.resolve("$code.yml")

    /**
     * Flattens the file to `dotted.key` → template.
     *
     * `getKeys(true)` walks nested sections and hands back the dotted paths [MessageKey] is written
     * in, so the authoring format stays the readable nesting of LOCALIZATION.md §2 while lookup stays
     * a map read.
     *
     * The section filter is load-bearing, not tidiness: `getKeys(true)` also yields the intermediate
     * paths (`balance`, `error`), and Bukkit's `getString` answers for those with the *section's*
     * `toString()` — `MemorySection[path='balance'…]` — rather than null. Without the filter, every
     * section would become a bogus template that only surfaces if some key collides with it.
     *
     * `currencies.*` is excluded outright, not merely filtered as a section: unlike `balance`/`error`,
     * its *leaves* (`currencies.coins.singular`) are themselves plain strings, so the section filter
     * alone would let them through as bogus templates — [parseCurrencies] is what actually reads them.
     */
    private fun parse(text: String): Map<String, String> {
        val yaml = YamlConfiguration()
        try {
            yaml.loadFromString(text)
        } catch (e: InvalidConfigurationException) {
            throw InvalidConfigurationException(e.message?.lineSequence()?.firstOrNull()?.trim() ?: e.toString())
        }
        return yaml.getKeys(true)
            .filterNot(yaml::isConfigurationSection)
            .filterNot { it == CURRENCIES_SECTION || it.startsWith("$CURRENCIES_SECTION.") }
            .mapNotNull { path -> yaml.getString(path)?.let { path to it } }
            .toMap()
    }

    private companion object {
        const val CURRENCIES_SECTION = "currencies"
    }
}
