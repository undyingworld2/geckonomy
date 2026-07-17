package com.the1mason.geckonomy.infrastructure.bukkit

import org.bukkit.plugin.Plugin

/**
 * The way back to the main thread (ARCHITECTURE.md §4).
 *
 * A command awaits its use case off-thread and then has to touch Bukkit again to reply — and the
 * Bukkit API may only be touched from the main thread (CODING_STANDARDS.md §3).
 *
 * A `fun interface` so a test can supply `MainThread { it() }` and stay synchronous, rather than
 * driving a scheduler to see whether a message was sent.
 */
internal fun interface MainThread {

    /** Runs [block] on the main thread, now or at the next tick. */
    fun execute(block: () -> Unit)
}

/**
 * [MainThread] over Bukkit's scheduler.
 *
 * Runs [block] inline when the caller is *already* on the main thread. Not an optimisation: a
 * scheduled task cannot run until the current tick finishes, so a handler that resolved without ever
 * suspending — a permission refusal, a bad amount — would otherwise have its reply deferred a tick
 * behind the command that asked for it.
 */
internal class BukkitMainThread(private val plugin: Plugin) : MainThread {

    override fun execute(block: () -> Unit) {
        if (plugin.server.isPrimaryThread) block()
        else plugin.server.scheduler.runTask(plugin, Runnable { block() })
    }
}
