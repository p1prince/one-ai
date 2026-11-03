package com.oneai.app.ai

import java.net.URI

/** Android-free validation and rendering rules for search requests and results. */
object WebSearchPolicy {
    fun normalizeQuery(query: String): String? {
        val value = query.trim()
        if (value.length !in 2..300 || value.any { it.isISOControl() }) return null
        return value
    }

    fun safeResultUrl(value: String): String? = runCatching {
        val uri = URI(value.trim())
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank() ||
            uri.userInfo != null || uri.fragment != null) null else uri.toASCIIString().takeIf { it.length <= 2_000 }
    }.getOrNull()

    fun format(results: List<WebSearchResult>, maxLength: Int = 18_000): String {
        require(maxLength in 1..20_000)
        if (results.isEmpty()) return "No web results were returned."
        val output = StringBuilder()
        results.take(10).forEachIndexed { index, result ->
            val block = buildString {
                append(index + 1).append(". ").append(result.title.take(200)).append('\n')
                append(result.url.take(2_000)).append('\n')
                if (result.description.isNotBlank()) append(result.description.take(1_000)).append('\n')
                append('\n')
            }
            if (output.length + block.length > maxLength) return@forEachIndexed
            output.append(block)
        }
        return output.toString().trim().ifBlank { "No usable web results were returned." }
    }
}
