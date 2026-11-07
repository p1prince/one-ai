package com.oneai.app.ai

data class WebSearchResult(val title: String, val url: String, val description: String)

interface WebSearchProvider {
    suspend fun search(query: String, limit: Int = 5): List<WebSearchResult>
}
