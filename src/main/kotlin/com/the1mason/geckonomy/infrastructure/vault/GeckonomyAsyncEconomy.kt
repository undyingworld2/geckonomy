package com.the1mason.geckonomy.infrastructure.vault

import com.the1mason.geckonomy.application.Attribution
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.service.EconomyService
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import net.milkbowl.vault2.economy.AccountPermission
import net.milkbowl.vault2.economy.AsyncEconomy
import net.milkbowl.vault2.economy.EconomyResponse
import net.milkbowl.vault2.economy.MultiEconomyResponse
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException

/**
 * The async half of the v2 API (VAULT_INTEGRATION.md §6).
 *
 * Talks to [EconomyService] directly and **never reads the mirror**: an integrator that went looking
 * for `async()` asked for exact values, and the mirror trades exactness for not blocking — a trade
 * this path does not need to make.
 */
internal class GeckonomyAsyncEconomy(
    private val economy: EconomyService,
    private val currencies: CurrencyRegistry,
    private val scope: CoroutineScope,
    private val responses: ResponseMapper,
    private val logger: Logger,
) : AsyncEconomy {

    // ── Accounts ────────────────────────────────────────────────────────

    override fun createAccount(accountID: UUID, name: String, player: Boolean): CompletableFuture<Boolean> =
        promise { economy.createAccount(AccountId(accountID), name) is Outcome.Success }

    override fun createAccount(accountID: UUID, name: String, worldName: String, player: Boolean): CompletableFuture<Boolean> =
        createAccount(accountID, name, player)

    override fun getUUIDNameMap(): CompletableFuture<Map<UUID, String>> =
        promise { economy.nameMap().orElse(emptyMap()).mapKeys { it.key.value } }

    override fun getAccountName(accountID: UUID): CompletableFuture<Optional<String>> =
        promise { Optional.ofNullable(economy.name(AccountId(accountID)).orElse(null)) }

    override fun hasAccount(accountID: UUID): CompletableFuture<Boolean> =
        promise { economy.exists(AccountId(accountID)).orElse(false) }

    override fun hasAccount(accountID: UUID, worldName: String): CompletableFuture<Boolean> = hasAccount(accountID)

    override fun renameAccount(pluginName: String, accountID: UUID, name: String): CompletableFuture<Boolean> =
        promise { economy.rename(AccountId(accountID), name) is Outcome.Success }

    override fun deleteAccount(pluginName: String, accountID: UUID): CompletableFuture<Boolean> =
        promise { economy.delete(AccountId(accountID)) is Outcome.Success }

    override fun accountSupportsCurrency(pluginName: String, accountID: UUID, currency: String): CompletableFuture<Boolean> =
        promise { resolve(currency) != null }

    override fun accountSupportsCurrency(pluginName: String, accountID: UUID, currency: String, world: String): CompletableFuture<Boolean> =
        accountSupportsCurrency(pluginName, accountID, currency)

    // ── Balance ─────────────────────────────────────────────────────────

    override fun balance(pluginName: String, accountID: UUID): CompletableFuture<BigDecimal> =
        balance(pluginName, accountID, IGNORED_WORLD, defaultCode())

    override fun balance(pluginName: String, accountID: UUID, world: String): CompletableFuture<BigDecimal> =
        balance(pluginName, accountID, world, defaultCode())

    override fun balance(pluginName: String, accountID: UUID, world: String, currency: String): CompletableFuture<BigDecimal> =
        promise {
            val resolved = resolve(currency) ?: return@promise BigDecimal.ZERO
            economy.balance(AccountId(accountID), resolved.code).let {
                if (it is Outcome.Success) it.value.amount else BigDecimal.ZERO
            }
        }

    override fun has(pluginName: String, accountID: UUID, amount: BigDecimal): CompletableFuture<Boolean> =
        has(pluginName, accountID, IGNORED_WORLD, defaultCode(), amount)

    override fun has(pluginName: String, accountID: UUID, world: String, amount: BigDecimal): CompletableFuture<Boolean> =
        has(pluginName, accountID, world, defaultCode(), amount)

    override fun has(pluginName: String, accountID: UUID, world: String, currency: String, amount: BigDecimal): CompletableFuture<Boolean> =
        promise {
            val resolved = resolve(currency) ?: return@promise false
            economy.has(AccountId(accountID), amount, resolved.code).orElse(false)
        }

    // ── Transactions ────────────────────────────────────────────────────

    override fun set(pluginName: String, accountID: UUID, amount: BigDecimal): CompletableFuture<EconomyResponse> =
        set(pluginName, accountID, IGNORED_WORLD, defaultCode(), amount)

    override fun set(pluginName: String, accountID: UUID, world: String, amount: BigDecimal): CompletableFuture<EconomyResponse> =
        set(pluginName, accountID, world, defaultCode(), amount)

    override fun set(pluginName: String, accountID: UUID, world: String, currency: String, amount: BigDecimal): CompletableFuture<EconomyResponse> =
        promise {
            val resolved = resolve(currency) ?: return@promise responses.unknownCurrency(currency)
            responses.response(economy.set(AccountId(accountID), amount, resolved.code, Attribution(pluginName)), amount)
        }

    override fun withdraw(pluginName: String, accountID: UUID, amount: BigDecimal): CompletableFuture<EconomyResponse> =
        withdraw(pluginName, accountID, IGNORED_WORLD, defaultCode(), amount)

    override fun withdraw(pluginName: String, accountID: UUID, world: String, amount: BigDecimal): CompletableFuture<EconomyResponse> =
        withdraw(pluginName, accountID, world, defaultCode(), amount)

    override fun withdraw(pluginName: String, accountID: UUID, world: String, currency: String, amount: BigDecimal): CompletableFuture<EconomyResponse> =
        promise {
            val resolved = resolve(currency) ?: return@promise responses.unknownCurrency(currency)
            responses.response(economy.withdraw(AccountId(accountID), amount, resolved.code, Attribution(pluginName)), amount)
        }

    override fun deposit(pluginName: String, accountID: UUID, amount: BigDecimal): CompletableFuture<EconomyResponse> =
        deposit(pluginName, accountID, IGNORED_WORLD, defaultCode(), amount)

    override fun deposit(pluginName: String, accountID: UUID, world: String, amount: BigDecimal): CompletableFuture<EconomyResponse> =
        deposit(pluginName, accountID, world, defaultCode(), amount)

    override fun deposit(pluginName: String, accountID: UUID, world: String, currency: String, amount: BigDecimal): CompletableFuture<EconomyResponse> =
        promise {
            val resolved = resolve(currency) ?: return@promise responses.unknownCurrency(currency)
            responses.response(economy.deposit(AccountId(accountID), amount, resolved.code, Attribution(pluginName)), amount)
        }

    override fun canWithdraw(pluginName: String, accountID: UUID, amount: BigDecimal): CompletableFuture<EconomyResponse> =
        canWithdraw(pluginName, accountID, IGNORED_WORLD, defaultCode(), amount)

    override fun canWithdraw(pluginName: String, accountID: UUID, world: String, amount: BigDecimal): CompletableFuture<EconomyResponse> =
        canWithdraw(pluginName, accountID, world, defaultCode(), amount)

    override fun canWithdraw(pluginName: String, accountID: UUID, world: String, currency: String, amount: BigDecimal): CompletableFuture<EconomyResponse> =
        promise { check(accountID, currency, amount) { id, code -> economy.canWithdraw(id, amount, code) } }

    override fun canDeposit(pluginName: String, accountID: UUID, amount: BigDecimal): CompletableFuture<EconomyResponse> =
        canDeposit(pluginName, accountID, IGNORED_WORLD, defaultCode(), amount)

    override fun canDeposit(pluginName: String, accountID: UUID, world: String, amount: BigDecimal): CompletableFuture<EconomyResponse> =
        canDeposit(pluginName, accountID, world, defaultCode(), amount)

    override fun canDeposit(pluginName: String, accountID: UUID, world: String, currency: String, amount: BigDecimal): CompletableFuture<EconomyResponse> =
        promise { check(accountID, currency, amount) { id, code -> economy.canDeposit(id, amount, code) } }

    override fun transfer(pluginName: String, from: UUID, to: UUID, amount: BigDecimal): CompletableFuture<MultiEconomyResponse> =
        transfer(pluginName, from, to, IGNORED_WORLD, defaultCode(), amount)

    override fun transfer(pluginName: String, from: UUID, to: UUID, worldName: String, amount: BigDecimal): CompletableFuture<MultiEconomyResponse> =
        transfer(pluginName, from, to, worldName, defaultCode(), amount)

    override fun transfer(pluginName: String, from: UUID, to: UUID, worldName: String, currency: String, amount: BigDecimal): CompletableFuture<MultiEconomyResponse> =
        promise {
            val resolved = resolve(currency) ?: return@promise MultiEconomyResponse(
                BigDecimal.ZERO,
                EconomyResponse.ResponseType.FAILURE,
                responses.unknownCurrencyMessage(currency),
            )
            val payer = AccountId(from)
            val payee = AccountId(to)
            responses.transfer(economy.transfer(payer, payee, amount, resolved.code, Attribution(pluginName)), payer, payee, amount)
        }

    // ── Shared accounts — not in v1 ─────────────────────────────────────

    override fun createSharedAccount(pluginName: String, accountID: UUID, name: String, owner: UUID): CompletableFuture<Boolean> = no()

    override fun accountsWithOwnerOf(pluginName: String, accountID: UUID): CompletableFuture<List<UUID>> = none()

    override fun accountsWithMembershipTo(pluginName: String, accountID: UUID): CompletableFuture<List<UUID>> = none()

    override fun accountsWithAccessTo(pluginName: String, accountID: UUID, vararg permissions: AccountPermission): CompletableFuture<List<UUID>> = none()

    override fun isAccountOwner(pluginName: String, accountID: UUID, uuid: UUID): CompletableFuture<Boolean> = no()

    override fun setOwner(pluginName: String, accountID: UUID, uuid: UUID): CompletableFuture<Boolean> = no()

    override fun isAccountMember(pluginName: String, accountID: UUID, uuid: UUID): CompletableFuture<Boolean> = no()

    override fun addAccountMember(pluginName: String, accountID: UUID, uuid: UUID): CompletableFuture<Boolean> = no()

    override fun addAccountMember(pluginName: String, accountID: UUID, uuid: UUID, vararg initialPermissions: AccountPermission): CompletableFuture<Boolean> = no()

    override fun removeAccountMember(pluginName: String, accountID: UUID, uuid: UUID): CompletableFuture<Boolean> = no()

    override fun hasAccountPermission(pluginName: String, accountID: UUID, uuid: UUID, permission: AccountPermission): CompletableFuture<Boolean> = no()

    override fun updateAccountPermission(pluginName: String, accountID: UUID, uuid: UUID, permission: AccountPermission, value: Boolean): CompletableFuture<Boolean> = no()

    // ── Internals ───────────────────────────────────────────────────────

    private suspend fun check(
        accountID: UUID,
        currency: String,
        amount: BigDecimal,
        ask: suspend (AccountId, CurrencyCode) -> Outcome<Boolean>,
    ): EconomyResponse {
        val resolved = resolve(currency) ?: return responses.unknownCurrency(currency)
        val id = AccountId(accountID)
        val balance = economy.balance(id, resolved.code).let { if (it is Outcome.Success) it.value.amount else BigDecimal.ZERO }
        return responses.booleanResponse(ask(id, resolved.code), amount, balance)
    }

    /**
     * Runs [block] on the plugin scope as a [CompletableFuture].
     *
     * The scope is cancelled on disable, so a future outstanding at shutdown completes exceptionally
     * rather than running against a closed connection pool.
     *
     * Completing exceptionally is the contract and stays the contract — an integrator who asked for a
     * future gets to decide what a failure means. But `future {}` also swallows it: nothing reaches the
     * scope's handler, so a caller who never inspects the result would leave no trace anywhere. Logged
     * here, and only here, so a failure is visible without changing what the future does.
     */
    private fun <T> promise(block: suspend () -> T): CompletableFuture<T> =
        scope.future { block() }.also { future ->
            // `also`, not `whenComplete`'s return: that is a *new* dependent stage, and handing the
            // caller a different future than the one the scope cancels is a behaviour change for the
            // sake of a log line. This only observes.
            future.whenComplete { _, e ->
                if (e != null && e !is CancellationException) {
                    logger.log(Level.WARNING, "Geckonomy: an async economy call failed", e)
                }
            }
        }

    private fun no(): CompletableFuture<Boolean> = CompletableFuture.completedFuture(false)

    private fun none(): CompletableFuture<List<UUID>> = CompletableFuture.completedFuture(emptyList())

    private fun resolve(currency: String): Currency? = CurrencyCode.parseOrNull(currency)?.let(currencies::byCode)

    private fun defaultCode(): String = currencies.default().code.value

    private fun <T> Outcome<T>.orElse(fallback: T): T = if (this is Outcome.Success) value else fallback

    private companion object {
        const val IGNORED_WORLD = ""
    }
}
