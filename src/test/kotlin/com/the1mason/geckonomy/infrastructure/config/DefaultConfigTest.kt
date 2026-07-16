package com.the1mason.geckonomy.infrastructure.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * The `config.yml` shipped in the jar is the first thing every server runs on, and it is the one
 * config no test would otherwise touch — a rule tightened in [ConfigLoader] without a matching edit
 * to the default file would leave every fresh install refusing to start. This is the guard.
 */
class DefaultConfigTest {

    private val default: String = javaClass.getResourceAsStream("/config.yml")
        ?.bufferedReader()
        ?.readText()
        ?: fail("config.yml is missing from the jar; the build is broken")

    @Test
    fun `the shipped config loads, with no errors and nothing to warn about`() {
        when (val load = ConfigLoader().load(default)) {
            is ConfigLoad.Loaded -> assertEquals(emptyList<String>(), load.warnings)
            is ConfigLoad.Invalid -> fail("the shipped config.yml is invalid: ${load.errors}")
        }
    }

    @Test
    fun `the shipped config is the one documented in CONFIGURATION dot md`() {
        val config = (ConfigLoader().load(default) as ConfigLoad.Loaded).config

        assertEquals(listOf("coins", "gems"), config.currencies.map { it.code.value })
        assertEquals(StorageType.SQLITE, config.storage.type)
    }

    /** Owners edit this file by hand; the comments are how they learn what the keys mean. */
    @Test
    fun `the shipped config keeps its explanatory comments`() {
        assertTrue("# Exactly one must have default: true." in default, "the currencies comment is gone")
        assertTrue("sqlite | mariadb" in default, "the storage.type comment is gone")
    }
}
