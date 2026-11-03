package com.oneai.app.ai

import com.oneai.app.core.OneAiStore
import com.oneai.app.security.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AiProviderClient(private val store: OneAiStore, private val vault: SecretVault) {
    suspend fun ask(prompt: String): String = withContext(Dispatchers.IO) {
        val key = vault.read() ?: throw IllegalStateException("Add an API key in Settings before using the AI provider.")
        val endpoint = store.aiEndpoint()
        require(endpoint.startsWith("https://")) { "Provider endpoint must use HTTPS." }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20000; readTimeout = 60000; doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val body = JSONObject().put("model", store.aiModel())
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                .put("temperature", 0.4)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("Provider returned HTTP $code: ${response.take(400)}")
            JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } finally { connection.disconnect() }
    }
}
