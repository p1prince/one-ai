package com.oneai.app.plugins

import com.oneai.app.ai.WebSearchPolicy
import com.oneai.app.ai.WebSearchProvider

/** Trusted optional adapter for user-approved web research requests. */
class WebResearchAdapter(private val provider: WebSearchProvider) : PluginAdapter {
    override val manifest = PluginManifest(
        id = PLUGIN_ID,
        name = "Web Research",
        version = "1.0.0",
        description = "A connector slot for approved web search providers.",
        capabilities = listOf("internet")
    )

    override suspend fun execute(input: PluginActionInput): PluginActionOutput {
        require(input.actionId == SEARCH_ACTION && input.capability == "internet") {
            "Unsupported Web Research action."
        }
        val query = WebSearchPolicy.normalizeQuery(input.values["query"].orEmpty())
            ?: throw IllegalArgumentException("Enter a search query between 2 and 300 characters.")
        val results = provider.search(query, 5)
        return PluginActionOutput("Web search results", WebSearchPolicy.format(results))
    }

    companion object {
        const val PLUGIN_ID = "one.web.research"
        const val SEARCH_ACTION = "search_web"
    }
}
