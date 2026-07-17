package com.the1mason.geckonomy.infrastructure.vault

import com.the1mason.geckonomy.application.Attribution
import com.the1mason.geckonomy.application.result.Outcome
import com.the1mason.geckonomy.application.service.EconomyService
import com.the1mason.geckonomy.domain.model.AccountId
import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.model.Money
import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import net.milkbowl.vault2.economy.AccountPermission
import net.milkbowl.vault2.economy.AsyncEconomy
import net.milkbowl.vault2.economy.Economy
import net.milkbowl.vault2.economy.EconomyResponse
import net.milkbowl.vault2.economy.MultiEconomyResponse
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID
import java.util.logging.Logger

/**
 * Geckonomy as the VaultUnlocked v2 economy (VAULT_INTEGRATION.md §5).
 *
 * Every `world` parameter is accepted and ignored — balances are never per-world (SPEC.md §6). An
 * absent or unknown `currency` resolves to the default currency for reads and refuses for writes.
 *
 * Several interface methods ship `default` bodies that are wrong for us and are overridden here on
 * purpose; [transfer] is the one that matters most, because its default is a non-atomic
 * withdraw-then-deposit and FR-B5 requires atomicity. `VaultDefaultsTest` pins them.
 */
internal class VaultUnlockedEconomyProvider(
    private val enabled: () -> Boolean,
    private val economy: EconomyService,
    private val currencies: CurrencyRegistry,
    private val sync: VaultSyncPath,
    private val responses: ResponseMapper,
    private val asyncEconomy: GeckonomyAsyncEconomy,
    private val logger: Logger,
) : Economy {

    // ── Capabilities ────────────────────────────────────────────────────

    override fun isEnabled(): Boolean = enabled()

    override fun getName(): String = "Geckonomy"

    override fun hasMultiCurrencySupport(): Boolean = true

    /** Reserved: `gk_account_member` and `AccountType.SHARED` exist so this can flip without a migration. */
    override fun hasSharedAccountSupport(): Boolean = false

    override fun supportsAsync(): Boolean = true

    override fun async(): Optional<AsyncEconomy> = Optional.of(asyncEconomy)

    // ── Currency & formatting ───────────────────────────────────────────

    override fun fractionalDigits(pluginName: String): Int = currencies.default().fractionalDigits

    /** The default ignores `currency` and answers for the default one — wrong for a multi-currency economy. */
    override fun fractionalDigits(pluginName: String, currency: String): Int =
        (resolve(currency) ?: currencies.default()).fractionalDigits

    @Deprecated("Vault keeps the plugin-less overload for old callers", ReplaceWith("format(pluginName, amount)"))
    override fun format(amount: BigDecimal): String = format(amount, currencies.default())

    override fun format(pluginName: String, amount: BigDecimal): String = format(amount, currencies.default())

    @Deprecated("Vault keeps the plugin-less overload for old callers", ReplaceWith("format(pluginName, amount, currency)"))
    override fun format(amount: BigDecimal, currency: String): String =
        format(amount, resolve(currency) ?: currencies.default())

    override fun format(pluginName: String, amount: BigDecimal, currency: String): String =
        format(amount, resolve(currency) ?: currencies.default())

    override fun hasCurrency(currency: String): Boolean = resolve(currency) != null

    override fun getDefaultCurrency(pluginName: String): String = currencies.default().code.value

    override fun defaultCurrencyNamePlural(pluginName: String): String = currencies.default().plural

    override fun defaultCurrencyNameSingular(pluginName: String): String = currencies.default().singular

    override fun currencies(): Collection<String> = currencies.all().map { it.code.value }

    // ── Accounts ────────────────────────────────────────────────────────

    @Deprecated("Vault keeps the flag-less overload", ReplaceWith("createAccount(accountID, name, true)"))
    override fun createAccount(accountID: UUID, name: String): Boolean = createAccount(accountID, name, true)

    /**
     * [player] is ignored: v1 has only player accounts (SPEC.md §1), and a shared account asked for
     * here would be silently created as a personal one. Idempotent per FR-A1 — an account that already
     * exists is success, not failure.
     */
    override fun createAccount(accountID: UUID, name: String, player: Boolean): Boolean =
        sync.createAccount(AccountId(accountID), name) is Outcome.Success

    @Deprecated("Vault keeps the flag-less overload", ReplaceWith("createAccount(accountID, name, worldName, true)"))
    override fun createAccount(accountID: UUID, name: String, worldName: String): Boolean =
        createAccount(accountID, name, true)

    override fun createAccount(accountID: UUID, name: String, worldName: String, player: Boolean): Boolean =
        createAccount(accountID, name, player)

    override fun getUUIDNameMap(): Map<UUID, String> = sync.nameMap().mapKeys { it.key.value }

    override fun getAccountName(accountID: UUID): Optional<String> =
        Optional.ofNullable(sync.name(AccountId(accountID)))

    override fun hasAccount(accountID: UUID): Boolean = sync.exists(AccountId(accountID))

    override fun hasAccount(accountID: UUID, worldName: String): Boolean = hasAccount(accountID)

    override fun renameAccount(accountID: UUID, name: String): Boolean =
        sync.rename(AccountId(accountID), name)

    override fun renameAccount(pluginName: String, accountID: UUID, name: String): Boolean =
        renameAccount(accountID, name)

    override fun deleteAccount(pluginName: String, accountID: UUID): Boolean =
        sync.delete(AccountId(accountID))

    /** Every account holds every configured currency; a balance row is created on demand. */
    override fun accountSupportsCurrency(pluginName: String, accountID: UUID, currency: String): Boolean =
        hasCurrency(currency)

    override fun accountSupportsCurrency(pluginName: String, accountID: UUID, currency: String, world: String): Boolean =
        hasCurrency(currency)

    // ── Balance & checks ────────────────────────────────────────────────

    @Deprecated("superseded by balance()", ReplaceWith("balance(pluginName, accountID)"))
    override fun getBalance(pluginName: String, accountID: UUID): BigDecimal =
        balanceOf(accountID, currencies.default())

    @Deprecated("superseded by balance()", ReplaceWith("balance(pluginName, accountID, world)"))
    override fun getBalance(pluginName: String, accountID: UUID, world: String): BigDecimal =
        balanceOf(accountID, currencies.default())

    @Deprecated("superseded by balance()", ReplaceWith("balance(pluginName, accountID, world, currency)"))
    override fun getBalance(pluginName: String, accountID: UUID, world: String, currency: String): BigDecimal =
        balanceOf(accountID, resolve(currency) ?: return BigDecimal.ZERO)

    override fun has(pluginName: String, accountID: UUID, amount: BigDecimal): Boolean =
        sync.has(AccountId(accountID), amount, currencies.default())

    override fun has(pluginName: String, accountID: UUID, worldName: String, amount: BigDecimal): Boolean =
        has(pluginName, accountID, amount)

    override fun has(pluginName: String, accountID: UUID, worldName: String, currency: String, amount: BigDecimal): Boolean {
        val resolved = resolve(currency) ?: return false
        return sync.has(AccountId(accountID), amount, resolved)
    }

    /** The default reads the balance and withdraws or deposits the difference — two writes, and racy. */
    override fun set(pluginName: String, accountID: UUID, amount: BigDecimal): EconomyResponse =
        set(pluginName, accountID, IGNORED_WORLD, currencies.default().code.value, amount)

    override fun set(pluginName: String, accountID: UUID, worldName: String, amount: BigDecimal): EconomyResponse =
        set(pluginName, accountID, worldName, currencies.default().code.value, amount)

    override fun set(pluginName: String, accountID: UUID, worldName: String, currency: String, amount: BigDecimal): EconomyResponse {
        val resolved = resolve(currency) ?: return unknownCurrency(currency)
        return responses.response(sync.set(AccountId(accountID), amount, resolved, Attribution(pluginName)), amount)
    }

    /** The interface default answers NOT_IMPLEMENTED. FR-B4 says we answer for real. */
    override fun canWithdraw(pluginName: String, accountID: UUID, amount: BigDecimal): EconomyResponse =
        canWithdraw(pluginName, accountID, IGNORED_WORLD, currencies.default().code.value, amount)

    override fun canWithdraw(pluginName: String, accountID: UUID, worldName: String, amount: BigDecimal): EconomyResponse =
        canWithdraw(pluginName, accountID, worldName, currencies.default().code.value, amount)

    override fun canWithdraw(pluginName: String, accountID: UUID, worldName: String, currency: String, amount: BigDecimal): EconomyResponse {
        val resolved = resolve(currency) ?: return unknownCurrency(currency)
        val id = AccountId(accountID)
        return responses.booleanResponse(sync.canWithdraw(id, amount, resolved), amount, sync.balance(id, resolved))
    }

    override fun canDeposit(pluginName: String, accountID: UUID, amount: BigDecimal): EconomyResponse =
        canDeposit(pluginName, accountID, IGNORED_WORLD, currencies.default().code.value, amount)

    override fun canDeposit(pluginName: String, accountID: UUID, worldName: String, amount: BigDecimal): EconomyResponse =
        canDeposit(pluginName, accountID, worldName, currencies.default().code.value, amount)

    override fun canDeposit(pluginName: String, accountID: UUID, worldName: String, currency: String, amount: BigDecimal): EconomyResponse {
        val resolved = resolve(currency) ?: return unknownCurrency(currency)
        val id = AccountId(accountID)
        return responses.booleanResponse(sync.canDeposit(id, amount, resolved), amount, sync.balance(id, resolved))
    }

    // ── Withdraw / deposit ──────────────────────────────────────────────

    override fun withdraw(pluginName: String, accountID: UUID, amount: BigDecimal): EconomyResponse =
        withdraw(pluginName, accountID, IGNORED_WORLD, currencies.default().code.value, amount)

    override fun withdraw(pluginName: String, accountID: UUID, worldName: String, amount: BigDecimal): EconomyResponse =
        withdraw(pluginName, accountID, worldName, currencies.default().code.value, amount)

    override fun withdraw(pluginName: String, accountID: UUID, worldName: String, currency: String, amount: BigDecimal): EconomyResponse {
        val resolved = resolve(currency) ?: return unknownCurrency(currency)
        return responses.response(sync.withdraw(AccountId(accountID), amount, resolved, Attribution(pluginName)), amount)
    }

    override fun deposit(pluginName: String, accountID: UUID, amount: BigDecimal): EconomyResponse =
        deposit(pluginName, accountID, IGNORED_WORLD, currencies.default().code.value, amount)

    override fun deposit(pluginName: String, accountID: UUID, worldName: String, amount: BigDecimal): EconomyResponse =
        deposit(pluginName, accountID, worldName, currencies.default().code.value, amount)

    override fun deposit(pluginName: String, accountID: UUID, worldName: String, currency: String, amount: BigDecimal): EconomyResponse {
        val resolved = resolve(currency) ?: return unknownCurrency(currency)
        return responses.response(sync.deposit(AccountId(accountID), amount, resolved, Attribution(pluginName)), amount)
    }

    // ── Transfers ───────────────────────────────────────────────────────

    override fun transfer(pluginName: String, from: UUID, to: UUID, amount: BigDecimal): MultiEconomyResponse =
        transfer(pluginName, from, to, IGNORED_WORLD, currencies.default().code.value, amount)

    override fun transfer(pluginName: String, from: UUID, to: UUID, worldName: String, amount: BigDecimal): MultiEconomyResponse =
        transfer(pluginName, from, to, worldName, currencies.default().code.value, amount)

    /**
     * The interface default withdraws then deposits, refunding if the deposit fails. That is two
     * transactions and a compensating write, which is exactly what FR-B5 forbids: a crash between them
     * destroys money. Ours is one `UnitOfWork` transaction — commit both sides or neither.
     */
    override fun transfer(pluginName: String, from: UUID, to: UUID, worldName: String, currency: String, amount: BigDecimal): MultiEconomyResponse {
        val resolved = resolve(currency)
            ?: return MultiEconomyResponse(
                BigDecimal.ZERO,
                EconomyResponse.ResponseType.FAILURE,
                responses.unknownCurrencyMessage(currency),
            )
        val payer = AccountId(from)
        val payee = AccountId(to)
        return responses.transfer(sync.transfer(payer, payee, amount, resolved, Attribution(pluginName)), payer, payee, amount)
    }

    // ── Shared accounts — not in v1 ─────────────────────────────────────

    override fun createSharedAccount(pluginName: String, accountID: UUID, name: String, owner: UUID): Boolean =
        unsupported("createSharedAccount")

    override fun isAccountOwner(pluginName: String, accountID: UUID, uuid: UUID): Boolean =
        unsupported("isAccountOwner")

    override fun setOwner(pluginName: String, accountID: UUID, uuid: UUID): Boolean = unsupported("setOwner")

    override fun isAccountMember(pluginName: String, accountID: UUID, uuid: UUID): Boolean =
        unsupported("isAccountMember")

    override fun addAccountMember(pluginName: String, accountID: UUID, uuid: UUID): Boolean =
        unsupported("addAccountMember")

    override fun addAccountMember(pluginName: String, accountID: UUID, uuid: UUID, vararg initialPermissions: AccountPermission): Boolean =
        unsupported("addAccountMember")

    override fun removeAccountMember(pluginName: String, accountID: UUID, uuid: UUID): Boolean =
        unsupported("removeAccountMember")

    override fun hasAccountPermission(pluginName: String, accountID: UUID, uuid: UUID, permission: AccountPermission): Boolean =
        unsupported("hasAccountPermission")

    override fun updateAccountPermission(pluginName: String, accountID: UUID, uuid: UUID, permission: AccountPermission, value: Boolean): Boolean =
        unsupported("updateAccountPermission")

    @Deprecated("Vault's own deprecation", ReplaceWith("accountsWithAccessTo(pluginName, accountID, *permissions)"))
    override fun accountsAccessTo(pluginName: String, accountID: UUID, vararg permissions: AccountPermission): List<String> {
        unsupported("accountsAccessTo")
        return emptyList()
    }

    override fun accountsWithAccessTo(pluginName: String, accountID: UUID, vararg permissions: AccountPermission): List<UUID> {
        unsupported("accountsWithAccessTo")
        return emptyList()
    }

    // ── Internals ───────────────────────────────────────────────────────

    private fun balanceOf(accountID: UUID, currency: Currency): BigDecimal =
        sync.balance(AccountId(accountID), currency)

    private fun format(amount: BigDecimal, currency: Currency): String = economy.format(Money(amount, currency))

    /** Untrusted: a caller may pass anything, so a malformed code is a `null`, never an exception. */
    private fun resolve(currency: String): Currency? =
        CurrencyCode.parseOrNull(currency)?.let(currencies::byCode)

    private fun unknownCurrency(currency: String): EconomyResponse = responses.unknownCurrency(currency)

    private fun unsupported(method: String): Boolean {
        logger.fine { "Geckonomy: $method was called, but shared accounts are not supported in v1" }
        return false
    }

    private companion object {
        /** Vault requires a world argument on some overloads; we accept it and ignore it. */
        const val IGNORED_WORLD = ""
    }
}
