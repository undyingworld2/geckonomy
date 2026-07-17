package com.the1mason.geckonomy.infrastructure.bukkit.command

import com.the1mason.geckonomy.domain.port.CurrencyRegistry
import com.the1mason.geckonomy.infrastructure.bukkit.CurrencyAccess.Action
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import org.bukkit.plugin.PluginManager

/**
 * Registers the per-currency permission nodes (SPEC.md §7).
 *
 * The base nodes are declared statically in `paper-plugin.yml`. These cannot be: they are named after
 * currencies, which come from `config.yml` and change on reload.
 *
 * **An unregistered node is op-only.** `hasPermission` on a node Bukkit has never heard of falls back
 * to [PermissionDefault.OP], so without this every per-currency node would deny every non-op player —
 * the exact inverse of the spec's "`.<code>` per-currency nodes default `true` (opt-out model)".
 * `/balance` would then work for admins and silently refuse everyone else, which is precisely the bug
 * a live smoke run as an op cannot see.
 *
 * **A wildcard needs its children.** `geckonomy.pay.*` grants `geckonomy.pay.coins` only because the
 * wildcard is registered as a parent holding it — Bukkit does not expand the `*` itself. Permission
 * plugins commonly resolve wildcards themselves, but a server running vanilla permissions has only
 * this.
 */
internal class GeckonomyPermissions(
    private val plugins: PluginManager,
    private val currencies: CurrencyRegistry,
) {

    /**
     * What [register] last added.
     *
     * Tracked rather than recomputed, because by the time a reload calls [register] again,
     * `ConfigService` has already swapped the registry — so asking [currencies] what to remove would
     * name the *new* currencies and strand the nodes of any currency that had just been removed.
     */
    private var registered: List<String> = emptyList()

    /**
     * (Re-)registers a node per currency per action, plus the wildcard parents.
     *
     * Idempotent twice over, because `addPermission` throws on a duplicate and a *throw here fails the
     * enable*. [unregister] first drops what we last added — including nodes of a currency since
     * removed from the config, which is the case bookkeeping alone can answer. Each node is then
     * replaced rather than added, which covers the case bookkeeping cannot: a node left behind by a
     * previous instance whose `onDisable` never ran. Both are safe because every node here is under
     * our own `geckonomy.` namespace.
     */
    fun register() {
        unregister()
        registered = Action.entries.flatMap { action ->
            val children = currencies.all().associate { currency ->
                val node = action.node(currency)
                plugins.replacePermission(
                    Permission(node, "Use ${currency.code.value} with /${action.base}", DEFAULT),
                )
                node to true
            }
            // Op-only by default: the wildcard means "every currency, including ones added later",
            // which is a grant an operator makes deliberately, not one everyone starts with.
            plugins.replacePermission(
                Permission(
                    action.wildcard,
                    "Use every currency with /${action.base}",
                    PermissionDefault.OP,
                    children,
                ),
            )
            children.keys + action.wildcard
        }
    }

    private fun PluginManager.replacePermission(permission: Permission) {
        removePermission(permission.name)
        addPermission(permission)
    }

    /** Drops every node [register] added. Called on disable, and before a re-register. */
    fun unregister() {
        registered.forEach(plugins::removePermission)
        registered = emptyList()
    }

    private companion object {
        /**
         * Opt-out, per SPEC.md §7: a currency a player may not use is a decision a server makes, not
         * the state everyone starts in. The base node (`geckonomy.pay`) is what gates the command
         * itself.
         */
        val DEFAULT = PermissionDefault.TRUE
    }
}
