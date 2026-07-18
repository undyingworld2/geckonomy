package com.the1mason.geckonomy.infrastructure.i18n

import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.NameRole
import java.math.BigDecimal

/**
 * A currency's effective singular/plural, with a per-language override (LOCALIZATION.md §2, SPEC.md
 * FR-L5). `Currency.nameFor`/`.roleFor` still decide *which* role an amount selects; this decides the
 * *string* for that role — a lang file's `currencies.<code>.singular|plural` if present, else
 * `config.yml`'s own value — so the name string stays single-sourced the way role selection already is.
 *
 * @param override `currencies.<code>.<key>` from the active language file. A supplier, not a captured
 *   map — wiring passes `LanguageRepository::currencyOverride`, which already reads through the
 *   repository's current reload, so this class needs no reload hook of its own.
 */
class CurrencyNames(private val override: (code: String, key: String) -> String?) {

    fun of(currency: Currency, role: NameRole): String {
        val key = when (role) {
            NameRole.SINGULAR -> "singular"
            NameRole.PLURAL -> "plural"
        }
        return override(currency.code.value, key) ?: when (role) {
            NameRole.SINGULAR -> currency.singular
            NameRole.PLURAL -> currency.plural
        }
    }

    fun of(currency: Currency, amount: BigDecimal): String = of(currency, currency.roleFor(amount))
}
