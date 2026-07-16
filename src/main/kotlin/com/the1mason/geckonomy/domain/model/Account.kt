package com.the1mason.geckonomy.domain.model

import java.time.Instant

/**
 * A holder of balances. In v1 always a player (SPEC.md §2).
 *
 * @property id identity; for a player account, their Minecraft UUID.
 * @property name display name. A cached convenience for `/baltop` and admin commands — the [id] is
 *   the identity, so a rename never moves an account's money.
 * @property type reserved discriminator; see [AccountType].
 * @property createdAt when the account was first created.
 */
data class Account(
    val id: AccountId,
    val name: String,
    val type: AccountType,
    val createdAt: Instant,
)

/**
 * What kind of holder an [Account] is.
 *
 * [SHARED] is unused in v1 and exists so that shared/bank accounts can land later without reshaping
 * [Account] or migrating existing rows (DOMAIN_MODEL.md §6).
 */
enum class AccountType {
    /** Owned by exactly one player. The only type v1 creates. */
    PLAYER,

    /** Reserved for shared/bank accounts. Not created in v1. */
    SHARED,
}
