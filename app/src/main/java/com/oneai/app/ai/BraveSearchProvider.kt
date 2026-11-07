package com.oneai.app.ai

import com.oneai.app.security.SecretVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Brave Search API adapter. The subscription token is stored separately from the AI key. */
class BraveSearchProvider(private val vault: SecretVault) : WebSearchProvider {
    override suspend fun search(query: String, limit: Int): List<WebSearchResult> = withContext(Dispatchers.IO) {
        val normalized = WebSearchPolicy.normalizeQuery(query)
            ?: throw IllegalArgumentException("Enter a search query between 2 and 300 characters.")
        require(limit in 1..10) { "Search result limit must be between 1 and 10." }
        val token = vault.readNamed(SECRET_NAME)?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Add a Brave Search API key in ONE AI Settings before searching.")
        val url = URL("$ENDPOINT?q=${URLEncoder.encode(normalized, Charsets.UTF_8.name())}&count=$limit")
        val connection = url.openConnection() as? HttpURLConnection
            ?: throw IllegalStateException("Could not open the search provider connection.")
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Subscription-Token", token)
            connection.setRequestProperty("User-Agent", "ONE-AI-Android")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = if (stream == null) "" else stream.use {
                AiResponseBodyReader.readUtf8(it, MAX_RESPONSE_BYTES)
                    ?: throw IllegalStateException("The search provider response exceeded ONE AI's size limit.")
            }
            if (status !in 200..299) {
                throw IllegalStateException("The search provider returned HTTP $status. Check the API key and provider account.")
            }
            val json = runCatching { JSONObject(raw) }
                .getOrElse { throw IllegalStateException("The search provider returned an unreadable response.") }
            val results = json.optJSONObject("web")?.optJSONArray("results")
                ?: return@withContext emptyList()
            buildList {
                for (index in 0 until minOf(results.length(), limit)) {
                    val item = results.optJSONObject(index) ?: continue
                    val title = item.optString("title").trim().take(200)
                    val link = WebSearchPolicy.safeResultUrl(item.optString("url")) ?: continue
                    val description = item.optString("description").trim().take(1_000)
                    if (title.isNotBlank()) add(WebSearchResult(title, link, description))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw error
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val SECRET_NAME = "brave_search"
        private const val ENDPOINT = "https://api.search.brave.com/res/v1/web/search"
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
    }
}
