package com.the1mason.geckonomy.infrastructure.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.MemoryConfiguration
import java.math.BigDecimal

/**
 * The errors and warnings found while reading one config file.
 *
 * Accumulated rather than thrown at the first bad key: a hand-edited `config.yml` tends to have
 * several mistakes at once, and finding them one server restart at a time is a miserable way to
 * configure a plugin. An error rejects the file; a warning lets it load.
 */
internal class ConfigProblems {

    private val collectedErrors = mutableListOf<String>()
    private val collectedWarnings = mutableListOf<String>()

    val errors: List<String> get() = collectedErrors
    val warnings: List<String> get() = collectedWarnings

    /** Records that [path] is wrong in a way that must stop the plugin. */
    fun error(path: String, message: String) {
        collectedErrors += "$path: $message"
    }

    /** Records that [path] is questionable but usable. */
    fun warn(path: String, message: String) {
        collectedWarnings += "$path: $message"
    }
}

/**
 * A section of the config, the dotted path it sits at, and the [problems] to report into.
 *
 * Every reader returns a usable value even when the key is missing or malformed, recording the
 * problem and handing back a filler. That keeps [ConfigLoader]'s mapping linear — no nullable
 * juggling, no early returns, every problem in the file found in one pass — and costs nothing:
 * [ConfigLoader] discards the whole object if any error was recorded, so a filler never reaches a
 * running plugin.
 *
 * Errors name the full path and the offending value, since the reader is a server owner looking at a
 * file, not a developer looking at a stack trace.
 *
 * @param section the backing section, or null if the file omitted it — in which case every key reads
 *   as absent, and the required ones report themselves.
 */
internal class ConfigNode(
    private val section: ConfigurationSection?,
    private val path: String,
    val problems: ConfigProblems,
) {

    fun error(key: String, message: String) = problems.error(pathOf(key), message)

    fun warn(key: String, message: String) = problems.warn(pathOf(key), message)

    /** The node at [key], which reads as all-absent if the file has no such section. */
    fun child(key: String): ConfigNode =
        ConfigNode(section?.getConfigurationSection(key), pathOf(key), problems)

    /** The raw entries of the list at [key]; empty if absent or not a list. */
    fun list(key: String): List<Any?> = section?.getList(key) ?: emptyList()

    /** [key]'s sub-keys as strings; empty if absent. Used for pass-through maps like JDBC props. */
    fun stringMap(key: String): Map<String, String> =
        section?.getConfigurationSection(key)
            ?.getValues(false)
            ?.mapValues { (_, value) -> value.toString() }
            ?: emptyMap()

    /** [key], or [default] if absent. Reports a blank value rather than passing it on. */
    fun string(key: String, default: String): String {
        val raw = raw(key) ?: return default
        val value = raw.toString()
        if (value.isBlank()) {
            error(key, "must not be blank")
            return default
        }
        return value
    }

    /** [key], or null if absent or blank. For fields that only matter to the other backend. */
    fun stringOrNull(key: String): String? = raw(key)?.toString()?.takeIf(String::isNotBlank)

    /** [key], reporting it if absent or blank. */
    fun requiredString(key: String): String {
        val raw = raw(key) ?: run {
            error(key, "is required")
            return ""
        }
        val value = raw.toString()
        if (value.isBlank()) {
            error(key, "must not be blank")
            return ""
        }
        return value
    }

    /** [key] as a whole number within [range], or [default] if absent. */
    fun int(key: String, default: Int, range: IntRange): Int {
        val raw = raw(key) ?: return default
        return int(key, raw, range) ?: default
    }

    /** [key] as a whole number within [range], or null if absent or malformed. */
    fun intOrNull(key: String, range: IntRange): Int? {
        val raw = raw(key) ?: return null
        return int(key, raw, range)
    }

    /** [key] as a whole number within [range], reporting it if absent. */
    fun requiredInt(key: String, range: IntRange): Int {
        val raw = raw(key) ?: run {
            error(key, "is required")
            return range.first
        }
        return int(key, raw, range) ?: range.first
    }

    /** [key] as a boolean, or [default] if absent. */
    fun bool(key: String, default: Boolean): Boolean {
        val raw = raw(key) ?: return default
        return raw as? Boolean ?: run {
            error(key, "expected true or false, got '$raw'")
            default
        }
    }

    /** [key] as a boolean, reporting it if absent. */
    fun requiredBool(key: String): Boolean {
        val raw = raw(key) ?: run {
            error(key, "is required")
            return false
        }
        return raw as? Boolean ?: run {
            error(key, "expected true or false, got '$raw'")
            false
        }
    }

    /**
     * [key] as an exact decimal, or [default] if absent.
     *
     * Goes through the scalar's text rather than [ConfigurationSection.getDouble] deliberately: money
     * is `BigDecimal` end to end (CODING_STANDARDS.md §1), and reading it as a double first would
     * quietly turn a configured `0.10` into `0.1000000000000000055511151231257827`. The YAML parser
     * still hands us a `Double` for `100.00`, but `Double.toString` gives the shortest text that
     * round-trips, so the number the owner wrote is the number we parse.
     */
    fun decimal(key: String, default: BigDecimal): BigDecimal {
        val raw = raw(key) ?: return default
        return try {
            BigDecimal(raw.toString().trim())
        } catch (_: NumberFormatException) {
            error(key, "expected a number, got '$raw'")
            default
        }
    }

    /**
     * [key] matched case-insensitively against [values], or [default] if absent.
     *
     * @param display how a value is spelled in the config file, for the error message — lowercase for
     *   our own enums (`sqlite`), as-written for borrowed ones (`HALF_UP`).
     */
    fun <E : Enum<E>> enum(
        key: String,
        default: E,
        values: Array<E>,
        display: (E) -> String = { it.name.lowercase() },
    ): E {
        val raw = raw(key) ?: return default
        val name = raw.toString().trim()
        return values.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: run {
            error(key, "expected one of ${values.joinToString(" | ", transform = display)}, got '$raw'")
            default
        }
    }

    private fun raw(key: String): Any? = section?.get(key)

    private fun pathOf(key: String): String = if (path.isEmpty()) key else "$path.$key"

    private fun int(key: String, raw: Any, range: IntRange): Int? {
        // Not `as? Number`: that would round a configured 3.5 down to 3 without a word.
        if (raw !is Int && raw !is Long) {
            error(key, "expected a whole number, got '$raw'")
            return null
        }
        val value = (raw as Number).toLong()
        if (value < range.first || value > range.last) {
            error(key, "must be between ${range.first} and ${range.last}, got $value")
            return null
        }
        return value.toInt()
    }
}

/**
 * This map as a config section, so a list entry can be read with the same helpers as a named section.
 *
 * The YAML parser hands back list entries as plain maps rather than sections; [MemoryConfiguration]
 * is the Bukkit-provided way to wrap one without a file or a server behind it.
 */
internal fun Map<*, *>.asSection(): ConfigurationSection {
    val section = MemoryConfiguration()
    for ((key, value) in this) {
        if (key != null) section.set(key.toString(), value)
    }
    return section
}
