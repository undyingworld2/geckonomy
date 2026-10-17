package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.domain.model.AccountId
import kotlinx.coroutines.sync.Mutex

/**
 * One writer per account at a time, within this server.
 *
 * A burst of deliveries to one player — a webstore paying out several purchases at once — would
 * otherwise put that many transactions on one `gk_balance` row together, each holding a pooled
 * connection and an IO thread while it waits for the row lock. Queued here instead, they wait as
 * suspended coroutines that hold nothing, and reach the database one at a time. The row lock still
 * decides between *servers*; this only keeps our own writers from fighting over it.
 *
 * **Striped, not keyed.** A map of one mutex per account would grow with whoever callers name — a
 * plugin looping over every offline player would fill it — and need eviction that races its own
 * users. [STRIPES] fixed mutexes cost a few kilobytes forever. Two accounts sharing a stripe merely
 * queue behind each other, which is a little latency and never a wrong answer.
 *
 * Several accounts (a transfer) are locked in ascending stripe order, de-duplicated, so two
 * transfers between the same pair in opposite directions cannot each hold one stripe and wait on the
 * other. Kotlin's [Mutex] is not reentrant, which is fine: nothing opens a transaction inside one.
 */
internal class AccountWriteGate {

    private val stripes = Array(STRIPES) { Mutex() }

    /** Runs [block] holding the stripe of every account in [accounts]. */
    suspend fun <T> withAccounts(accounts: Set<AccountId>, block: suspend () -> T): T {
        val held = ArrayList<Mutex>(accounts.size)
        try {
            // Added only once acquired: a cancellation while waiting for the second stripe must
            // release the first, and must not unlock the second, which it never got.
            for (stripe in accounts.map(::stripeOf).distinct().sorted()) {
                stripes[stripe].lock()
                held += stripes[stripe]
            }
            return block()
        } finally {
            held.asReversed().forEach { it.unlock() }
        }
    }

    private fun stripeOf(id: AccountId): Int = Math.floorMod(id.value.hashCode(), STRIPES)

    private companion object {
        const val STRIPES = 256
    }
}
