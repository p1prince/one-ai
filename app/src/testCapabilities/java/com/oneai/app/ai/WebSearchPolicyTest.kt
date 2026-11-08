package com.oneai.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchPolicyTest {
    @Test fun trimsAndAcceptsValidQuery() {
        assertEquals("ONE AI Android assistant", WebSearchPolicy.normalizeQuery("  ONE AI Android assistant  "))
    }

    @Test fun rejectsShortLongAndControlCharacterQueries() {
        assertNull(WebSearchPolicy.normalizeQuery("x"))
        assertNull(WebSearchPolicy.normalizeQuery("a".repeat(301)))
        assertNull(WebSearchPolicy.normalizeQuery("safe\nquery"))
    }

    @Test fun onlyAllowsHttpAndHttpsResultLinksWithoutCredentialsOrFragments() {
        assertEquals("https://example.com/page", WebSearchPolicy.safeResultUrl("https://example.com/page"))
        assertNull(WebSearchPolicy.safeResultUrl("javascript:alert(1)"))
        assertNull(WebSearchPolicy.safeResultUrl("https://user:pass@example.com/"))
        assertNull(WebSearchPolicy.safeResultUrl("https://example.com/#private"))
    }

    @Test fun formatsResultsWithinConfiguredLimit() {
        val results = listOf(
            WebSearchResult("Example", "https://example.com", "Useful description")
        )
        val formatted = WebSearchPolicy.format(results, 200)
        assertTrue(formatted.contains("Example"))
        assertTrue(formatted.contains("https://example.com"))
        assertTrue(WebSearchPolicy.format(emptyList()).contains("No web results"))
    }
}
