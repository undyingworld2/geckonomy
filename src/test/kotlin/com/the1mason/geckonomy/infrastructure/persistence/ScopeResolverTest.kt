package com.the1mason.geckonomy.infrastructure.persistence

import com.the1mason.geckonomy.domain.TestCurrencies
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** [ScopeResolver] — the whole of DATA_MODEL.md §7, which is small enough to state exhaustively. */
class ScopeResolverTest {

    @Test
    fun `a network currency resolves to the global key on every server`() {
        assertEquals(ScopeResolver.GLOBAL_SCOPE_KEY, ScopeResolver("server-a").keyFor(TestCurrencies.COINS))
        assertEquals(ScopeResolver.GLOBAL_SCOPE_KEY, ScopeResolver("server-b").keyFor(TestCurrencies.COINS))
    }

    @Test
    fun `a per-server currency resolves to this server's id`() {
        assertEquals("server-a", ScopeResolver("server-a").keyFor(TestCurrencies.GEMS))
        assertEquals("server-b", ScopeResolver("server-b").keyFor(TestCurrencies.GEMS))
    }

    @Test
    fun `refuses a blank server id`() {
        // A blank id would key every per-server balance under the same empty string, silently
        // merging the balances of every server sharing the database. Config validation rejects it
        // first; this is the backstop for a caller that built one by hand.
        assertThrows<IllegalArgumentException> { ScopeResolver(" ") }
    }
}
