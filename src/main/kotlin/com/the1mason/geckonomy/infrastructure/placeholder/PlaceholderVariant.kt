package com.the1mason.geckonomy.infrastructure.placeholder

/**
 * The placeholder vocabulary: every keyword `%geckonomy_…%` understands.
 *
 * A closed table rather than a `when` over strings, because two things outside this file need to
 * *enumerate* it — the expansion advertises it to `/papi info`, and config load warns when a currency
 * code collides with a keyword (see [PlaceholderResolver] §shadowing).
 *
 * @property keyword the literal text after `geckonomy_`.
 * @property argumentName names the one `_`-token between keyword and currency code, for the table
 *   the expansion advertises; `null` means the keyword is followed straight by the code.
 */
internal enum class PlaceholderVariant(val keyword: String, val argumentName: String? = null) {
    BALANCE_FORMATTED("balance_formatted"),
    BALANCE_COMMAS("balance_commas"),
    BALANCE_FIXED("balance_fixed"),
    BALANCE_NAME("balance_name"),
    BALANCE_RAW("balance_raw"),
    BALANCE("balance"),

    NAME_SINGULAR("name_singular"),
    NAME_PLURAL("name_plural"),
    NAME("name"),

    SYMBOL("symbol"),
    DIGITS("digits"),

    FORMAT("format", argumentName = "amount"),

    BALTOP_BALANCE_FORMATTED("baltop_balance_formatted", argumentName = "n"),
    BALTOP_BALANCE("baltop_balance", argumentName = "n"),
    BALTOP_PLAYER("baltop_player", argumentName = "n"),
    BALTOP_RANK("baltop_rank"),
    ;

    val takesArgument: Boolean get() = argumentName != null

    companion object {
        /**
         * Longest keyword first — the whole reason this is ordered rather than a set.
         *
         * `balance_formatted` must be tried before `balance`, or every formatted balance would parse
         * as a raw balance of a currency called `formatted`. Declaration order above already reads
         * longest-first per family, but sorting makes that a property rather than a promise: adding
         * `balance_x` at the bottom of the enum must not silently break `balance`.
         */
        val LONGEST_FIRST: List<PlaceholderVariant> = entries.sortedByDescending { it.keyword.length }

        /** The keywords a currency code can shadow. See [PlaceholderResolver]'s §shadowing note. */
        val KEYWORDS: Set<String> = entries.map { it.keyword }.toSet()
    }
}
