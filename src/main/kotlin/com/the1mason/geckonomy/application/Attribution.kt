package com.the1mason.geckonomy.application

/**
 * Who asked for a balance change — a Vault caller's plugin name, or Geckonomy itself.
 *
 * Recorded on every ledger row ([com.the1mason.geckonomy.domain.model.Transaction.sourcePlugin]) so
 * an admin reading the history can tell a shop plugin's withdrawal from an admin's `/eco take`.
 *
 * A type rather than a bare `String` because it travels beside other strings — `createAccount(id,
 * name)`, `rename(id, name)` — and a value class turns an argument swap into a compile error rather
 * than a ledger quietly attributing every row to a player's display name. It also gives
 * [GECKONOMY] one home instead of a `"geckonomy"` literal per use case.
 */
@JvmInline
value class Attribution(val plugin: String) {

    override fun toString(): String = plugin

    companion object {
        /** Our own commands and listeners — anything not driven by a third-party plugin. */
        val GECKONOMY = Attribution("geckonomy")
    }
}
