package com.the1mason.geckonomy.infrastructure.i18n

/**
 * Every message Geckonomy can say, as a typed key into the language files (LOCALIZATION.md §2).
 *
 * An enum rather than string constants, for the reason FR-L1 exists: a `when` over these is
 * exhaustive, an unused one is visible to the compiler, and `MessageKeyCoverageTest` holds this list
 * and `lang/en.yml` to each other in both directions. A misspelled string key would instead surface as
 * a raw key in front of a player.
 *
 * The set deliberately runs ahead of what is wired: M5 ships the vocabulary, and M6/M7 spend it. A key
 * with no caller yet is not dead code, it is the message its command will need — and having it here
 * means M7 writes handlers without reopening this file and `en.yml` for each one.
 *
 * @property path dotted path into the YAML. The only place a message's string identity is written.
 */
enum class MessageKey(val path: String) {

    /**
     * The lead-in every other message can open with, via `<prefix>`.
     *
     * A key like any other, but `MessageService` also injects it as a tag into every render, which is
     * why templates say `<prefix>` rather than repeating the markup (LOCALIZATION.md §2).
     */
    PREFIX("prefix"),

    // ── /balance ────────────────────────────────────────────────────────
    BALANCE_SELF("balance.self"),
    BALANCE_OTHER("balance.other"),

    // ── /pay ────────────────────────────────────────────────────────────
    PAY_SENT("pay.sent"),
    PAY_RECEIVED("pay.received"),
    PAY_INSUFFICIENT("pay.insufficient"),
    PAY_SELF("pay.self"),

    // ── /baltop ─────────────────────────────────────────────────────────
    BALTOP_HEADER("baltop.header"),
    BALTOP_ENTRY("baltop.entry"),
    BALTOP_EMPTY("baltop.empty"),

    // ── /eco, /geckonomy ────────────────────────────────────────────────
    ADMIN_GIVEN("admin.given"),
    ADMIN_TAKEN("admin.taken"),
    ADMIN_SET("admin.set"),
    ADMIN_RESET("admin.reset"),
    ADMIN_RELOADED("admin.reloaded"),
    ADMIN_RELOAD_FAILED("admin.reload-failed"),
    ADMIN_VERSION("admin.version"),

    // ── Errors ──────────────────────────────────────────────────────────
    // The first five are one-to-one with EconomyError's variants, so M7's mapping is total by
    // construction; the rest are refusals the command layer makes before the economy is ever asked.
    ERROR_UNKNOWN_CURRENCY("error.unknown-currency"),
    ERROR_ACCOUNT_NOT_FOUND("error.account-not-found"),
    ERROR_INSUFFICIENT_FUNDS("error.insufficient-funds"),
    ERROR_INVALID_AMOUNT("error.invalid-amount"),
    ERROR_STORAGE("error.storage"),
    ERROR_NO_PERMISSION("error.no-permission"),
    ERROR_NO_CURRENCY_PERMISSION("error.no-currency-permission"),
    ERROR_NOT_TRANSFERABLE("error.not-transferable"),
    ERROR_OTHERS_HIDDEN("error.others-hidden"),
    ERROR_PLAYER_NOT_FOUND("error.player-not-found"),
    ERROR_PLAYERS_ONLY("error.players-only"),
    ERROR_USAGE("error.usage"),
    ;

    override fun toString(): String = path
}
