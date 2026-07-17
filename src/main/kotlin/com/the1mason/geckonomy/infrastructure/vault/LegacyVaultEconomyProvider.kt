package com.the1mason.geckonomy.infrastructure.vault

import com.the1mason.geckonomy.application.Attribution
import com.the1mason.geckonomy.application.result.OperationResult
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.service.EconomyService
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.Money
import com.the1mason.geckonomy.domain.policy.RoundingPolicy
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.OfflinePlayer
import java.math.BigDecimal

/**
 * Geckonomy as the **legacy** Vault v1 economy (VAULT_INTEGRATION.md §8).
 *
 * Ships in v1 despite the whole interface being deprecated, because most published plugins still bind
 * to it (SPEC.md FR-V6). Single-currency — always the default currency, since v1 has no currency
 * parameter — `double` amounts, worlds ignored.
 *
 * Not built on Vault's own `AbstractEconomy`: that implements the `OfflinePlayer` overloads by
 * delegating to the `String playerName` ones, throwing away the UUID the caller already handed us and
 * forcing a name lookup to get it back. The dependency runs the wrong way; [PlayerResolver] only
 * exists for callers that never had a UUID to begin with.
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class LegacyVaultEconomyProvider(
    private val enabled: () -> Boolean,
    private val economy: EconomyService,
    private val currencies: CurrencyRegistry,
    private val sync: VaultSyncPath,
    private val responses: LegacyResponseMapper,
    private val players: PlayerResolver,
    private val rounding: () -> RoundingPolicy,
) : Economy {

    // ── Capabilities ────────────────────────────────────────────────────

    override fun isEnabled(): Boolean = enabled()

    override fun getName(): String = "Geckonomy"

    /** Distinct from VaultUnlocked's shared accounts, and deferred with them. */
    override fun hasBankSupport(): Boolean = false

    override fun fractionalDigits(): Int = currencies.default().fractionalDigits

    override fun format(amount: Double): String = economy.format(Money(amount.toAmount(), currencies.default()))

    override fun currencyNamePlural(): String = currencies.default().plural

    override fun currencyNameSingular(): String = currencies.default().singular

    // ── Accounts ────────────────────────────────────────────────────────

    override fun hasAccount(player: OfflinePlayer): Boolean = sync.exists(players.resolve(player))

    override fun hasAccount(player: OfflinePlayer, worldName: String): Boolean = hasAccount(player)

    override fun hasAccount(playerName: String): Boolean =
        players.resolve(playerName)?.let(sync::exists) ?: false

    override fun hasAccount(playerName: String, worldName: String): Boolean = hasAccount(playerName)

    override fun createPlayerAccount(player: OfflinePlayer): Boolean =
        sync.createAccount(players.resolve(player), player.name ?: player.uniqueId.toString()) is Outcome.Success

    override fun createPlayerAccount(player: OfflinePlayer, worldName: String): Boolean = createPlayerAccount(player)

    /**
     * `false` for a name we cannot resolve, rather than inventing a UUID for it.
     *
     * `getOfflinePlayer(name)` would happily manufacture one, and the account would belong to nobody.
     */
    override fun createPlayerAccount(playerName: String): Boolean {
        val id = players.resolve(playerName) ?: return false
        return sync.createAccount(id, playerName) is Outcome.Success
    }

    override fun createPlayerAccount(playerName: String, worldName: String): Boolean = createPlayerAccount(playerName)

    // ── Balances ────────────────────────────────────────────────────────

    override fun getBalance(player: OfflinePlayer): Double = balanceOf(players.resolve(player))

    override fun getBalance(player: OfflinePlayer, world: String): Double = getBalance(player)

    override fun getBalance(playerName: String): Double =
        players.resolve(playerName)?.let(::balanceOf) ?: 0.0

    override fun getBalance(playerName: String, world: String): Double = getBalance(playerName)

    override fun has(player: OfflinePlayer, amount: Double): Boolean =
        sync.has(players.resolve(player), amount.toAmount(), currencies.default())

    override fun has(player: OfflinePlayer, worldName: String, amount: Double): Boolean = has(player, amount)

    override fun has(playerName: String, amount: Double): Boolean {
        val id = players.resolve(playerName) ?: return false
        return sync.has(id, amount.toAmount(), currencies.default())
    }

    override fun has(playerName: String, worldName: String, amount: Double): Boolean = has(playerName, amount)

    // ── Withdraw / deposit ──────────────────────────────────────────────

    override fun withdrawPlayer(player: OfflinePlayer, amount: Double): EconomyResponse =
        withdraw(players.resolve(player), amount)

    override fun withdrawPlayer(player: OfflinePlayer, worldName: String, amount: Double): EconomyResponse =
        withdrawPlayer(player, amount)

    override fun withdrawPlayer(playerName: String, amount: Double): EconomyResponse =
        withdraw(players.resolve(playerName) ?: return unknownPlayer(playerName), amount)

    override fun withdrawPlayer(playerName: String, worldName: String, amount: Double): EconomyResponse =
        withdrawPlayer(playerName, amount)

    override fun depositPlayer(player: OfflinePlayer, amount: Double): EconomyResponse =
        deposit(players.resolve(player), amount)

    override fun depositPlayer(player: OfflinePlayer, worldName: String, amount: Double): EconomyResponse =
        depositPlayer(player, amount)

    override fun depositPlayer(playerName: String, amount: Double): EconomyResponse =
        deposit(players.resolve(playerName) ?: return unknownPlayer(playerName), amount)

    override fun depositPlayer(playerName: String, worldName: String, amount: Double): EconomyResponse =
        depositPlayer(playerName, amount)

    // ── Banks — deferred with shared accounts ───────────────────────────

    override fun createBank(name: String, player: String): EconomyResponse = noBanks()

    override fun createBank(name: String, player: OfflinePlayer): EconomyResponse = noBanks()

    override fun deleteBank(name: String): EconomyResponse = noBanks()

    override fun bankBalance(name: String): EconomyResponse = noBanks()

    override fun bankHas(name: String, amount: Double): EconomyResponse = noBanks()

    override fun bankWithdraw(name: String, amount: Double): EconomyResponse = noBanks()

    override fun bankDeposit(name: String, amount: Double): EconomyResponse = noBanks()

    override fun isBankOwner(name: String, playerName: String): EconomyResponse = noBanks()

    override fun isBankOwner(name: String, player: OfflinePlayer): EconomyResponse = noBanks()

    override fun isBankMember(name: String, playerName: String): EconomyResponse = noBanks()

    override fun isBankMember(name: String, player: OfflinePlayer): EconomyResponse = noBanks()

    override fun getBanks(): List<String> = emptyList()

    // ── Internals ───────────────────────────────────────────────────────

    private fun balanceOf(id: AccountId): Double = sync.balance(id, currencies.default()).toDouble()

    private fun withdraw(id: AccountId, amount: Double): EconomyResponse =
        respond(sync.withdraw(id, amount.toAmount(), currencies.default(), VAULT), amount)

    private fun deposit(id: AccountId, amount: Double): EconomyResponse =
        respond(sync.deposit(id, amount.toAmount(), currencies.default(), VAULT), amount)

    private fun respond(result: OperationResult, amount: Double): EconomyResponse =
        responses.response(result, amount.toAmount())

    /** A name that resolved to nobody. There is no account, so there is no id to name in the message. */
    private fun unknownPlayer(playerName: String): EconomyResponse =
        responses.playerNotFound(playerName)

    private fun noBanks(): EconomyResponse = responses.notImplemented("Banks not supported")

    /** Rounded here so the ledger never records a `double`'s binary noise (see [toMoney]). */
    private fun Double.toAmount(): BigDecimal = toMoney(currencies.default(), rounding())

    private companion object {
        /**
         * The legacy interface carries no plugin name on any method, so the ledger cannot say which
         * plugin moved the money — only that it came through the v1 bridge.
         */
        val VAULT = Attribution("vault")
    }
}
