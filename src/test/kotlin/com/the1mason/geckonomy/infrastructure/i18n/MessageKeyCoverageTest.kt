package com.the1mason.geckonomy.infrastructure.i18n

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [MessageKey] and the bundled `lang/en.yml` must describe the same set of messages, in both
 * directions.
 *
 * This is the test that makes the whole fallback chain trustworthy. The bundled `en.yml` is the last
 * layer, the one that catches every key an owner's edited file has grown out of, and it can only do
 * that job if it is *complete*. "Complete" is not a thing anyone can eyeball across two files that
 * drift independently — so it is asserted, and adding a key to either file without the other fails
 * the build rather than a player's `/pay`.
 */
class MessageKeyCoverageTest {

    private val bundled: Map<String, String> = run {
        val text = checkNotNull(javaClass.getResourceAsStream("/lang/en.yml")) {
            "lang/en.yml is not on the classpath; the build is broken"
        }.bufferedReader().use { it.readText() }
        val yaml = YamlConfiguration().apply { loadFromString(text) }
        // Sections dropped, and not only because they are not messages: Bukkit answers getString on a
        // section with its toString(), so leaving them in would compare MessageKey against entries
        // like "MemorySection[path='balance']".
        yaml.getKeys(true)
            .filterNot(yaml::isConfigurationSection)
            .mapNotNull { path -> yaml.getString(path)?.let { path to it } }
            .toMap()
    }

    @Test
    fun `every key has a message`() {
        val missing = MessageKey.entries.map(MessageKey::path).filterNot(bundled::containsKey)

        assertEquals(emptyList<String>(), missing, "these MessageKeys have no entry in lang/en.yml")
    }

    @Test
    fun `every message has a key`() {
        val paths = MessageKey.entries.map(MessageKey::path).toSet()
        val orphans = bundled.keys - paths

        // An orphan is either a typo'd key nobody will ever render, or a leftover from a message that
        // was removed. Both are dead text, and both look identical to a translator.
        assertEquals(emptySet<String>(), orphans, "these lang/en.yml entries match no MessageKey")
    }

    @Test
    fun `no message is blank`() {
        val blank = bundled.filterValues(String::isBlank).keys

        assertEquals(emptySet<String>(), blank, "a blank message renders as nothing, which reads as a broken command")
    }

    @Test
    fun `every key is unique`() {
        val paths = MessageKey.entries.map(MessageKey::path)

        // Two constants sharing a path would silently render the same message, and the coverage tests
        // above would both still pass.
        assertEquals(paths.size, paths.toSet().size, "two MessageKeys share a path")
    }

    @Test
    fun `the prefix key exists, since every message leans on it`() {
        assertTrue(bundled.containsKey(MessageKey.PREFIX.path))
    }
}
