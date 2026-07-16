package com.the1mason.geckonomy.infrastructure.config

import com.the1mason.geckonomy.domain.model.Currency
import com.the1mason.geckonomy.domain.model.CurrencyCode
import com.the1mason.geckonomy.domain.port.CurrencyRegistry

/**
 * The [CurrencyRegistry] backed by `config.yml` (M2), rebuilt on `/geckonomy reload`.
 *
 * The registry object is stable and its *contents* are replaced, rather than the whole registry being
 * rebuilt: use cases receive this port once at wiring (ARCHITECTURE.md §7), so handing out a new
 * instance on reload would leave every one of them reading currencies that no longer exist. What
 * [replaceWith] swaps is an immutable [Snapshot], published through a `@Volatile` field — so a reader
 * sees either every currency from before the reload or every currency from after, never a half-built
 * set, and a `Currency` can never change shape underneath an operation already using it.
 *
 * @param currencies a *validated* set — exactly one default, unique codes. Building one from
 *   unvalidated input throws, which is intended: only [ConfigLoader] output belongs here.
 */
class ConfigCurrencyRegistry(currencies: List<Currency>) : CurrencyRegistry {

    @Volatile
    private var snapshot: Snapshot = Snapshot(currencies)

    override fun all(): Collection<Currency> = snapshot.all

    override fun default(): Currency = snapshot.default

    override fun byCode(code: CurrencyCode): Currency? = snapshot.byCode[code]

    /** Replaces every currency with [currencies], atomically for readers. */
    fun replaceWith(currencies: List<Currency>) {
        snapshot = Snapshot(currencies)
    }

    /**
     * One set of currencies with its lookups precomputed.
     *
     * The default is resolved eagerly so a set without one fails here, at the swap, rather than at
     * the first `default()` call in the middle of someone's transaction.
     */
    private class Snapshot(currencies: List<Currency>) {
        val all: List<Currency> = currencies.toList()
        val byCode: Map<CurrencyCode, Currency> = all.associateBy(Currency::code)
        val default: Currency = all.first(Currency::isDefault)
    }
}
