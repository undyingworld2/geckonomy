package com.the1mason.geckonomy.infrastructure.bukkit

import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.service.EconomyService
import com.the1mason.geckonomy.domain.model.AccountId
import org.bukkit.OfflinePlayer
import org.bukkit.Server

/**
 * The player sources that cost nothing and never leave the server.
 *
 * **Never `Server.getOfflinePlayer(String)`.** Its own javadoc says it "may involve a blocking web
 * request to get the UUID" — a Mojang round trip, on the main thread. It also never fails, inventing an
 * [OfflinePlayer] for a name that was never real, so it cannot even report a typo.
 * `getOfflinePlayerIfCached` is the same lookup against the local usercache with neither problem.
 *
 * Shared by the command path and the Vault path, which agree on this rule and on nothing else about
 * resolution: they differ in threading and in what they may cache. See [PlayerTargets] and
 * `vault.PlayerResolver`.
 *
 * Main thread only — both calls read live server state.
 */
internal fun serverLookup(server: Server, name: String): AccountId? =
    server.getPlayerExact(name)?.let { AccountId(it.uniqueId) }
        ?: server.getOfflinePlayerIfCached(name)?.let { AccountId(it.uniqueId) }

/**
 * Resolves the name a player typed to an account (SPEC.md §7).
 *
 * Split in two because the halves have different costs and different threads. [fromServer] is a
 * main-thread memory read and answers for anyone online or seen before; [resolve] adds the database
 * and must be awaited. A command asks the first in its Brigadier handler — which already runs on the
 * main thread — and only pays for the second when it misses.
 *
 * No TTL cache here, unlike the Vault resolver: that one exists because a shop plugin can ask every
 * tick. A human typing `/pay` cannot.
 */
internal class PlayerTargets(
    private val server: Server,
    private val economy: EconomyService,
) {

    /** The identifier a caller already holds. No lookup, no failure mode. */
    fun of(player: OfflinePlayer): AccountId = AccountId(player.uniqueId)

    /** Online players and Paper's usercache. Main thread; no IO. */
    fun fromServer(name: String): AccountId? = serverLookup(server, name)

    /**
     * [fromServer], then our own accounts.
     *
     * The last source is a genuine rarity — an account exists only because the player joined, and
     * joining is what puts them in the usercache — so it is reached only when the cheap sources miss.
     *
     * `null` means nobody of that name, which is `error.player-not-found`.
     */
    suspend fun resolve(name: String): AccountId? =
        fromServer(name) ?: fromAccounts(name)

    /**
     * Our account table, matched case-insensitively.
     *
     * A storage failure reads as "no such player" here. That is a deliberate flattening: the caller
     * has already been told by [StorageGuard][com.the1mason.geckonomy.application.usecase.StorageGuard]'s
     * log, and to a player typing a name, an unreachable database and an unknown name are the same
     * refusal. The mutating paths do not flatten anything — they report `StorageFailure` properly.
     */
    private suspend fun fromAccounts(name: String): AccountId? {
        val names = economy.nameMap()
        if (names !is Outcome.Success) return null
        return names.value.entries.firstOrNull { (_, stored) -> stored.equals(name, ignoreCase = true) }?.key
    }
}
