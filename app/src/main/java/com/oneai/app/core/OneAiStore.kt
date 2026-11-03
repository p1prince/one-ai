package com.oneai.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.oneai.app.ai.AiActivityLogger
import com.oneai.app.commands.CommandRepository

data class PluginRecord(
    val id: String, val name: String, val version: String, val description: String,
    val capabilities: List<String>, val enabled: Boolean, val developerCreated: Boolean = false
)

data class CommandRecord(val id: String, val title: String, val prompt: String, val enabled: Boolean = false)

class OneAiStore(context: Context) : AiActivityLogger, CommandRepository {
    private val prefs = context.getSharedPreferences("one_ai_store", Context.MODE_PRIVATE)

    fun plugins(): List<PluginRecord> {
        val saved = readArray("plugins")
        return (0 until saved.length()).mapNotNull { index ->
        val it = saved.optJSONObject(index) ?: return@mapNotNull null
        runCatching {
            PluginRecord(it.getString("id"), it.getString("name"), it.optString("version", "1.0"),
                it.optString("description", ""), it.getJSONArray("capabilities").toStringList(),
                it.optBoolean("enabled", false), it.optBoolean("developerCreated", false))
        }.getOrNull()
        }
    }

    fun savePlugin(plugin: PluginRecord) {
        val all = plugins().filterNot { it.id == plugin.id } + plugin
        writeArray("plugins", JSONArray().apply { all.forEach { put(JSONObject().apply {
            put("id", it.id); put("name", it.name); put("version", it.version)
            put("description", it.description); put("capabilities", JSONArray(it.capabilities))
            put("enabled", it.enabled); put("developerCreated", it.developerCreated)
        }) } })
    }

    fun removePlugin(id: String) {
        writeArray("plugins", JSONArray().apply { plugins().filterNot { it.id == id }.forEach { p ->
            put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("version", p.version)
                put("description", p.description); put("capabilities", JSONArray(p.capabilities))
                put("enabled", p.enabled); put("developerCreated", p.developerCreated)
            })
        } })
    }

    override fun commands(): List<CommandRecord> {
        val saved = readArray("commands")
        return (0 until saved.length()).mapNotNull { index ->
        val it = saved.optJSONObject(index) ?: return@mapNotNull null
        runCatching { CommandRecord(it.getString("id"), it.getString("title"), it.getString("prompt"), it.optBoolean("enabled", true)) }.getOrNull()
        }
    }

    override fun saveCommand(command: CommandRecord) {
        val all = commands().filterNot { it.id == command.id } + command
        writeArray("commands", JSONArray().apply { all.forEach { put(JSONObject().apply {
            put("id", it.id); put("title", it.title); put("prompt", it.prompt); put("enabled", it.enabled)
        }) } })
    }

    override fun removeCommand(id: String) {
        writeArray("commands", JSONArray().apply { commands().filterNot { it.id == id }.forEach {
            put(JSONObject().put("id", it.id).put("title", it.title).put("prompt", it.prompt).put("enabled", it.enabled))
        } })
    }

    override fun log(message: String) {
        val logs = readArray("activity")
        logs.put(0, JSONObject().put("time", System.currentTimeMillis()).put("message", message))
        val trimmed = JSONArray()
        for (i in 0 until minOf(logs.length(), 150)) trimmed.put(logs.getJSONObject(i))
        writeArray("activity", trimmed)
    }

    fun activity(): List<String> = readArray("activity").let { a ->
        (0 until a.length()).map { i -> a.getJSONObject(i).optString("message") }
    }

    fun setBubbleEnabled(enabled: Boolean) { prefs.edit().putBoolean("bubble_enabled", enabled).apply() }
    fun bubbleEnabled() = prefs.getBoolean("bubble_enabled", false)
    fun setBubbleServices(services: Set<String>) { prefs.edit().putStringSet("bubble_services", services).apply() }
    fun bubbleServices(): Set<String> = prefs.getStringSet("bubble_services", emptySet())?.toSet() ?: emptySet()
    fun bubbleServiceOrder(): List<String> = prefs.getString("bubble_service_order", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    fun setBubbleServiceOrder(order: List<String>) { prefs.edit().putString("bubble_service_order", order.distinct().joinToString(",")).apply() }

    fun setAiEndpoint(value: String) { prefs.edit().putString("ai_endpoint", value.trim()).apply() }
    fun aiEndpoint() = prefs.getString("ai_endpoint", "https://api.openai.com/v1/chat/completions") ?: ""
    fun setAiModel(value: String) { prefs.edit().putString("ai_model", value.trim()).apply() }
    fun aiModel() = prefs.getString("ai_model", "gpt-4o-mini") ?: "gpt-4o-mini"
    fun setAiProviderName(value: String) { prefs.edit().putString("ai_provider", value).apply() }
    fun aiProviderName() = prefs.getString("ai_provider", "OpenAI-compatible") ?: "OpenAI-compatible"

    fun developerMode() = prefs.getBoolean("developer_mode", false)
    fun setDeveloperMode(value: Boolean) { prefs.edit().putBoolean("developer_mode", value).apply() }

    private fun readArray(key: String): JSONArray = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrElse { JSONArray() }
    private fun writeArray(key: String, value: JSONArray) { prefs.edit().putString(key, value.toString()).apply() }
    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }
}
