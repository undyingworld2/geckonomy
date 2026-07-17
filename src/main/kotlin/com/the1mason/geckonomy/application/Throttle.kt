package com.the1mason.geckonomy.application

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * "At most one of these per [every]" — for a warning on a path that a third party controls the rate of.
 *
 * The warnings this guards are worth printing once and worthless printed a thousand times: a shop
 * plugin looping over offline players would turn a useful hint into the reason nobody reads the
 * console. The same footgun `VaultSyncPath.refreshBehind` already defends against with its dedup set.
 *
 * Deliberately **not keyed** by operation. The obvious refinement — one budget per account, per
 * currency, per context string — is what makes a throttle leak: the keys come from callers, so the map
 * grows with them, and the flood it is meant to survive is exactly when it grows fastest. One budget
 * for the whole class of warning cannot, and "your database is slow" does not need to be said per
 * account to be understood.
 *
 * @param nanos injected so tests can move time without sleeping (CODING_STANDARDS.md §6).
 */
internal class Throttle(
    private val every: Duration,
    private val nanos: () -> Long = System::nanoTime,
) {

    private val last = AtomicLong(Long.MIN_VALUE)
    private val suppressed = AtomicInteger()

    /**
     * `null` when the caller should stay quiet; otherwise how many were suppressed since the last one
     * got through — a number worth saying out loud, because one slow query and ten thousand are the
     * same line otherwise.
     *
     * Races resolve toward quiet: two threads arriving together, only one claims, the other counts
     * itself suppressed. Losing a warning to a race costs nothing when the next one is due in [every].
     */
    fun claim(): Int? {
        val now = nanos()
        val previous = last.get()
        val due = previous == Long.MIN_VALUE || now - previous >= every.inWholeNanoseconds
        if (!due || !last.compareAndSet(previous, now)) {
            suppressed.incrementAndGet()
            return null
        }
        return suppressed.getAndSet(0)
    }
}

/** `" (N more suppressed)"`, or nothing at all when this is the only one. */
internal fun suppressedSuffix(count: Int): String = if (count > 0) " ($count more suppressed)" else ""
