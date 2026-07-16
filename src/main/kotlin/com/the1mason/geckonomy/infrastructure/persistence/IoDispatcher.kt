package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.infrastructure.config.StorageConfig
import com.the1mason.geckonomy.infrastructure.config.StorageType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * The threads every database operation runs on — and the only ones that may touch JDBC
 * (CODING_STANDARDS.md §3).
 *
 * Bounded rather than elastic: the pool it feeds is bounded too, so unbounded threads would only
 * queue deeper on a connection that does not exist. Named rather than anonymous because a stuck
 * economy query should be identifiable in a thread dump by someone who has never read this code.
 *
 * Closed in `onDisable` so no thread outlives the plugin's classloader — a leaked thread holding a
 * classloader is the classic `/reload` memory leak.
 */
class IoDispatcher private constructor(private val delegate: ExecutorCoroutineDispatcher) : AutoCloseable {

    /** The dispatcher itself. Every repository body runs inside `withContext(io.dispatcher)`. */
    val dispatcher: CoroutineDispatcher get() = delegate

    /** Stops accepting work and lets the threads die. Idempotent. */
    override fun close() = delegate.close()

    companion object {

        /** Thread-pool name; shows up in thread dumps and profilers. */
        private const val THREAD_NAME_PREFIX = "geckonomy-io-"

        /**
         * A dispatcher sized for [config]'s backend.
         *
         * **SQLite gets exactly one thread.** SQLite allows one writer at a time, and a second
         * concurrent writer does not go faster — it collects `SQLITE_BUSY` and retries. One thread
         * makes serialized access a structural fact rather than something `busy_timeout` has to
         * paper over, which is why the timeout is a safety net here and not the mechanism
         * (DATA_MODEL.md §4).
         *
         * **MariaDB gets one thread per connection.** More threads than connections would leave
         * callers blocked inside `getConnection` holding a thread for nothing; fewer would leave
         * connections idle. Matching the two makes the pool the only queue.
         */
        fun forStorage(config: StorageConfig): IoDispatcher = sized(
            when (config.type) {
                StorageType.SQLITE -> 1
                StorageType.MARIADB -> config.pool.maximumPoolSize
            },
        )

        /** A dispatcher with exactly [threads] threads. */
        fun sized(threads: Int): IoDispatcher {
            require(threads >= 1) { "IoDispatcher needs at least one thread, got $threads" }
            return IoDispatcher(Executors.newFixedThreadPool(threads, NamedThreads()).asCoroutineDispatcher())
        }
    }

    /**
     * Names the pool's threads `geckonomy-io-1`, `-2`, … and marks them daemon.
     *
     * Daemon so a JVM shutdown that skips `onDisable` — a crash, a kill — is not held open by an
     * idle database thread.
     */
    private class NamedThreads : ThreadFactory {
        private val next = AtomicInteger(1)

        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, THREAD_NAME_PREFIX + next.getAndIncrement()).apply { isDaemon = true }
    }
}
