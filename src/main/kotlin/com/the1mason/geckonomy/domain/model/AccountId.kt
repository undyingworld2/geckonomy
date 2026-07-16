package com.the1mason.geckonomy.domain.model

import java.util.UUID

/**
 * Identity of an account.
 *
 * Wraps a [UUID] rather than passing raw UUIDs around, so an account id can never be confused with a
 * transaction id or any other UUID at a call site. Matches Vault's UUID keying (DOMAIN_MODEL.md §1),
 * which is why no other identity scheme is offered.
 *
 * A value class: zero allocation at runtime, full type safety at compile time. Equality is the
 * wrapped UUID's.
 */
@JvmInline
value class AccountId(val value: UUID) {
    override fun toString(): String = value.toString()
}
