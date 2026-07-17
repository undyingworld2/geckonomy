package com.the1mason.geckonomy.infrastructure.vault

import com.the1mason.geckonomy.domain.model.AccountId
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Resolves the legacy API's `String playerName` to an [AccountId] (VAULT_INTEGRATION.md §8).
 *
 * **Never calls `Server.getOfflinePlayer(String)`.** Its own javadoc says it "may involve a blocking
 * web request to get the UUID" — a Mojang round trip on the main thread, on a path a shop plugin can
 * hit every tick. It also never fails, inventing an [OfflinePlayer] for names that were never real,
 * so it cannot even tell us the name was bogus. `getOfflinePlayerIfCached` is the same lookup against
 * the local usercache with neither problem.
 *
 * Sources, in order: online players, Paper's usercache, then our own account name map. The last is a
 * genuine rarity — an account exists only because the player joined, and joining is what puts them in
 * the usercache — so it is read behind a [ttl] cache rather than per call.
 */
internal class PlayerResolver(
    private val server: Server,
    private val sync: VaultSyncPath,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofMinutes(1),
) {

    @Volatile
    private var index: Map<String, AccountId> = emptyMap()

    @Volatile
    private var loadedAt: Instant? = null

    /** The identifier every modern caller already has. No lookup, no failure mode. */
    fun resolve(player: OfflinePlayer): AccountId = AccountId(player.uniqueId)

    /** `null` when [name] matches nobody we know — the legacy API's `false`/`FAILURE`. */
    fun resolve(name: String): AccountId? =
        server.getPlayerExact(name)?.let { AccountId(it.uniqueId) }
            ?: server.getOfflinePlayerIfCached(name)?.let { AccountId(it.uniqueId) }
            ?: fromAccountNames(name)

    private fun fromAccountNames(name: String): AccountId? {
        if (isStale()) reload()
        return index[name.lowercase()]
    }

    private fun isStale(): Boolean =
        loadedAt?.let { Duration.between(it, clock.instant()) >= ttl } ?: true

    /**
     * Rebuilt whole, and only after a miss.
     *
     * A name absent from a fresh index stays absent until the ttl expires, which sounds like a bug and
     * is not: the only way to gain an account is to join, and a player who just joined is online, so
     * the first source answered before this one was consulted.
     */
    private fun reload() {
        index = sync.nameMap().entries.associate { (id, name) -> name.lowercase() to id }
        loadedAt = clock.instant()
    }
}
