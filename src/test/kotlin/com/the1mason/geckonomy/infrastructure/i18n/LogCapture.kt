package com.the1mason.geckonomy.infrastructure.i18n

import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * A logger that remembers instead of printing, for the tests that treat a warning as the behaviour
 * under test — a missing message key is *supposed* to tell somebody.
 */
class LogCapture {

    val records = mutableListOf<LogRecord>()

    val logger: Logger = Logger.getAnonymousLogger().apply {
        useParentHandlers = false
        addHandler(object : Handler() {
            override fun publish(record: LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        })
    }

    fun messages(level: Level): List<String> = records.filter { it.level == level }.map(LogRecord::getMessage)

    fun warnings(): List<String> = messages(Level.WARNING)
}
