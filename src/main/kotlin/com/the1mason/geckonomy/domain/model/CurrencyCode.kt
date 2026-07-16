package com.the1mason.geckonomy.domain.model

import com.the1mason.geckonomy.domain.InvalidCurrencyCode

/**
 * Stable machine identifier of a currency (`coins`, `gems`) — distinct from the display name, which
 * is localized and may change without breaking stored data.
 *
 * Case-insensitive: normalized to lowercase on construction so `/pay Bob 5 COINS` and a config entry
 * of `coins` resolve to the same currency, and so the code can be used as a storage key without the
 * database's collation deciding the answer for us.
 *
 * The constructor is private because a value class cannot normalize its own field in an `init` block
 * — [invoke] does the normalizing, and keeping the raw constructor out of reach means an unnormalized
 * code cannot exist.
 */
@JvmInline
value class CurrencyCode private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("[a-z0-9_-]+")

        /**
         * Reads [raw] as a currency code, lowercasing it first.
         *
         * Named `invoke` so call sites read as the plain construction they logically are:
         * `CurrencyCode("coins")`.
         *
         * @throws InvalidCurrencyCode if [raw] is empty or holds anything outside `[a-z0-9_-]`.
         *   Use [parseOrNull] for input you do not control.
         */
        operator fun invoke(raw: String): CurrencyCode =
            parseOrNull(raw) ?: throw InvalidCurrencyCode(raw)

        /**
         * Reads [raw] as a currency code, or returns `null` if it is malformed.
         *
         * For untrusted input — a config file or a command argument — where a bad value is a user
         * error to be reported, not a bug to be thrown.
         */
        fun parseOrNull(raw: String): CurrencyCode? =
            raw.lowercase().takeIf(PATTERN::matches)?.let(::CurrencyCode)
    }
}
