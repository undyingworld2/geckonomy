package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyScope

/**
 * Turns a currency's scope into the `scope_key` stored on its balance and ledger rows
 * (DATA_MODEL.md §7).
 *
 * This is the whole reason domain and application never learn that server ids exist: a use case asks
 * for "Bob's gems" and this decides whether that means *this* server's gems or the network's. Keeping
 * the translation in one object means the rule is stated once, and a caller cannot accidentally read
 * one server's balance while writing another's.
 *
 * @param serverId `settings.server-id`, captured at startup. A change needs a restart — balances
 *   written under the old id are simply not found under the new one, which is why `ConfigService`
 *   warns rather than swapping it live.
 */
class ScopeResolver(private val serverId: String) {

    init {
        require(serverId.isNotBlank()) { "server-id must not be blank" }
    }

    /**
     * The `scope_key` rows for [currency] are stored under.
     *
     * A network currency answers [GLOBAL_SCOPE_KEY] regardless of which server asks, so every server
     * on the database reads and writes the same row; a per-server currency answers this server's id,
     * so servers sharing the database stay independent.
     */
    fun keyFor(currency: Currency): String = when (currency.scope) {
        CurrencyScope.NETWORK -> GLOBAL_SCOPE_KEY
        CurrencyScope.SERVER -> serverId
    }

    companion object {

        /**
         * The `scope_key` shared by every server for a network currency.
         *
         * A `server-id` of `@global` does not collide with it, despite looking like it should: a
         * balance row is keyed by `(account_id, currency_code, scope_key)`, and a currency is either
         * network-scoped or server-scoped, never both — so the two keys can never be resolved for
         * the same `currency_code` on one server. Servers sharing a database *must* agree on each
         * currency's scope for the same reason, which is a deployment precondition rather than
         * something this class can check (DATA_MODEL.md §7).
         */
        const val GLOBAL_SCOPE_KEY = "@global"
    }
}
