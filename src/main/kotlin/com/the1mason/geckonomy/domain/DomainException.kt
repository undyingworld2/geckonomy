package com.the1mason.geckonomy.domain

import com.the1mason.geckonomy.domain.model.CurrencyCode

/**
 * Base of the domain's exception hierarchy.
 *
 * These signal a **broken invariant** — a caller did something the model forbids — not an expected
 * failure. Expected failures (unknown currency, insufficient funds) are typed results instead
 * (CODING_STANDARDS.md §4), because they are part of normal operation and every caller must handle
 * them. A `DomainException` means the code above the domain has a bug.
 *
 * Sealed so the application layer can map the hierarchy exhaustively at its boundary. No
 * `DomainException` may reach a Bukkit or Vault caller (ARCHITECTURE.md §6).
 */
sealed class DomainException(message: String) : RuntimeException(message)

/**
 * Arithmetic was attempted between two [com.the1mason.geckonomy.domain.model.Money] values of
 * different currencies — adding gems to coins is meaningless, so the model refuses rather than
 * guessing an exchange rate (DOMAIN_MODEL.md §1).
 */
class CurrencyMismatch(
    val left: CurrencyCode,
    val right: CurrencyCode,
) : DomainException("Cannot combine money of different currencies: '${left.value}' and '${right.value}'")

/**
 * A string could not be read as a [CurrencyCode].
 *
 * Thrown by `CurrencyCode(raw)`. Code paths handling untrusted input — notably M2's config loader,
 * where a typo is a user error rather than a bug — should call `CurrencyCode.parseOrNull` instead and
 * report the failure in their own terms.
 */
class InvalidCurrencyCode(
    val raw: String,
) : DomainException("Invalid currency code '$raw': expected one or more of [a-z0-9_-]")
