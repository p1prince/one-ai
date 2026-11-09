package com.oneai.app.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException

/** Each adapter against mock HTTP responses: request shape, response and error normalization, credential handling. */
class ApiKeyAdaptersTest {
    private fun fail(block: suspend () -> Unit): AiProviderException =
        assertThrows(AiProviderException::class.java) { runBlocking { block() } }

    private fun secrets(vararg pairs: Pair<String?, String>) = MapSecrets().also { s -> pairs.forEach { s.map[it.first] = it.second } }

    // ---- OpenAI-compatible (OpenAI, DeepSeek, Groq, OpenRouter, Custom) ----------------------------------------------

    private val chatOk = """{"model":"gpt-x","choices":[{"message":{"role":"assistant","content":"Hi there"}}]}"""

    @Test fun openAiCompatibleSendsBearerKeyAndNormalizesTheAnswer() = runBlocking {
        val http = FakeHttp(Canned.ok(chatOk))
        val p = profile(ProviderCatalog.OPENAI, name = "My OpenAI")
        val result = AiProviderClient(p, secrets(p.secretName to "sk-test-1"), http).complete(userMessage())
        assertEquals(AiCompletion("Hi there", "gpt-x", "My OpenAI"), result)
        val request = http.only()
        assertEquals("https://api.openai.com/v1/chat/completions", request.url)
        assertEquals("Bearer sk-test-1", request.headers["Authorization"])
        val body = JSONObject(request.body)
        assertEquals("gpt-4o-mini", body.getString("model"))
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertTrue(body.has("temperature"))
    }

    @Test fun eachOpenAiCompatibleProviderUsesItsOwnEndpointAndKey() = runBlocking {
        listOf(ProviderCatalog.OPENAI, ProviderCatalog.DEEPSEEK, ProviderCatalog.GROQ, ProviderCatalog.OPENROUTER, ProviderCatalog.CUSTOM).forEach { kind ->
            val http = FakeHttp(Canned.ok(chatOk))
            val p = profile(kind)
            AiProviderClient(p, secrets(p.secretName to "key-for-$kind", "key-other" to "WRONG"), http).complete(userMessage())
            assertEquals(ProviderCatalog.descriptor(kind)!!.defaultEndpoint, http.only().url)
            assertEquals("Bearer key-for-$kind", http.only().headers["Authorization"])
        }
    }

    @Test fun reasoningModelsDoNotGetATemperature() {
        assertFalse(AiProviderClient.acceptsTemperature("o4-mini"))
        assertFalse(AiProviderClient.acceptsTemperature("gpt-5"))
        assertFalse(AiProviderClient.acceptsTemperature("openai/o3"))
        assertTrue(AiProviderClient.acceptsTemperature("gpt-4o-mini"))
        assertTrue(AiProviderClient.acceptsTemperature("deepseek-chat"))
    }

    @Test fun openAiCompatibleReadsContentPartsAndRejectsEmptyAnswers() = runBlocking {
        val p = profile(ProviderCatalog.GROQ)
        val s = secrets(p.secretName to "k")
        val parts = """{"choices":[{"message":{"content":[{"type":"text","text":"one"},{"type":"text","text":"two"}]}}]}"""
        assertEquals("one\ntwo", AiProviderClient(p, s, FakeHttp(Canned.ok(parts))).complete(userMessage()).content)
        assertEquals("EMPTY_RESPONSE", fail { AiProviderClient(p, s, FakeHttp(Canned.ok("""{"choices":[]}"""))).complete(userMessage()) }.code)
        assertEquals("EMPTY_RESPONSE", fail { AiProviderClient(p, s, FakeHttp(Canned.ok("""{"choices":[{"message":{"content":"  "}}]}"""))).complete(userMessage()) }.code)
        assertEquals("INVALID_RESPONSE", fail { AiProviderClient(p, s, FakeHttp(Canned.ok("not json"))).complete(userMessage()) }.code)
    }

    @Test fun missingOrUnreadableKeyFailsBeforeAnyRequest() {
        val http = FakeHttp(Canned.ok(chatOk))
        val p = profile(ProviderCatalog.OPENAI)
        assertEquals("MISSING_CREDENTIALS", fail { AiProviderClient(p, MapSecrets(), http).complete(userMessage()) }.code)
        val broken = MapSecrets().also { it.unreadable += p.secretName }
        assertEquals("CREDENTIALS_UNREADABLE", fail { AiProviderClient(p, broken, http).complete(userMessage()) }.code)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun openAiCompatibleRefusesAnInsecureEndpointWithoutSendingTheKey() {
        val http = FakeHttp(Canned.ok(chatOk))
        val p = profile(ProviderCatalog.CUSTOM, endpoint = "http://api.example.com/v1/chat/completions")
        assertEquals("INVALID_ENDPOINT", fail { AiProviderClient(p, secrets(p.secretName to "k"), http).complete(userMessage()) }.code)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun httpErrorsAreNormalizedAndNeverEchoTheKey() {
        val p = profile(ProviderCatalog.OPENAI, name = "Work OpenAI")
        val s = secrets(p.secretName to "sk-secret-123")
        val e401 = fail { AiProviderClient(p, s, FakeHttp(Canned.json(401, """{"error":{"message":"Incorrect API key provided: sk-secret-123."}}"""))).complete(userMessage()) }
        assertEquals("HTTP_401", e401.code)
        assertFalse(e401.message!!.contains("sk-secret-123"))
        assertTrue(e401.message!!.contains("[redacted]"))
        assertEquals("HTTP_429", fail { AiProviderClient(p, s, FakeHttp(Canned.json(429, ""))).complete(userMessage()) }.code)
        val html = fail { AiProviderClient(p, s, FakeHttp(Canned.json(502, "<html>Bad gateway</html>"))).complete(userMessage()) }
        assertEquals("HTTP_502", html.code)
        assertFalse(html.message!!.contains("<html>"))
        // Transient codes are the ones the retry layer retries; 401 and 400 are not.
        assertTrue(RetryingAiProvider.isTransient("HTTP_429") && RetryingAiProvider.isTransient("HTTP_502"))
        assertFalse(RetryingAiProvider.isTransient("HTTP_401") || RetryingAiProvider.isTransient("HTTP_400"))
    }

    @Test fun networkFailuresBecomeRetryableNormalizedErrors() {
        val p = profile(ProviderCatalog.OPENAI)
        val s = secrets(p.secretName to "k")
        val refused = fail { AiProviderClient(p, s, FakeHttp { throw ConnectException("refused") }).complete(userMessage()) }
        assertEquals("CONNECTION_FAILED", refused.code)
        val broken = fail { AiProviderClient(p, s, FakeHttp { throw IOException("stream closed") }).complete(userMessage()) }
        assertEquals("REQUEST_FAILED", broken.code)
        assertTrue(RetryingAiProvider.isTransient(refused.code) && RetryingAiProvider.isTransient(broken.code))
    }

    @Test fun oversizedResponsesAreRefused() {
        val p = profile(ProviderCatalog.OPENAI)
        val huge = "x".repeat(ProviderSupport.MAX_RESPONSE_BYTES + 1)
        assertEquals("RESPONSE_TOO_LARGE", fail { AiProviderClient(p, secrets(p.secretName to "k"), FakeHttp(Canned.ok(huge))).complete(userMessage()) }.code)
    }

    // ---- Gemini ------------------------------------------------------------------------------------------------------

    private val geminiOk = """{"candidates":[{"content":{"role":"model","parts":[{"text":"Gem"},{"text":"ini"}]},"finishReason":"STOP"}],"modelVersion":"gemini-2.0-flash-001"}"""

    @Test fun geminiBuildsAGenerateContentRequestWithTheKeyInAHeader() = runBlocking {
        val http = FakeHttp(Canned.ok(geminiOk))
        val p = profile(ProviderCatalog.GEMINI, name = "Gem")
        val result = GeminiProvider(p, secrets(p.secretName to "AIza-test"), http).complete(
            listOf(AiMessage("system", "Be brief."), AiMessage("user", "Hi"), AiMessage("assistant", "Hello"), AiMessage("user", "Again"))
        )
        assertEquals(AiCompletion("Gemini", "gemini-2.0-flash-001", "Gem"), result)
        val request = http.only()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent", request.url)
        assertEquals("AIza-test", request.headers["x-goog-api-key"])
        assertFalse("the key must never appear in the URL", request.url.contains("AIza-test") || request.url.contains("key="))
        assertNull(request.headers["Authorization"])
        val body = JSONObject(request.body)
        assertEquals("Be brief.", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val contents = body.getJSONArray("contents")
        assertEquals(listOf("user", "model", "user"), (0 until contents.length()).map { contents.getJSONObject(it).getString("role") })
    }

    @Test fun geminiAcceptsAModelsPrefixAndRefusesPathTricks() {
        assertEquals("gemini-2.5-pro", GeminiProvider.modelId("models/gemini-2.5-pro"))
        val http = FakeHttp(Canned.ok(geminiOk))
        val p = profile(ProviderCatalog.GEMINI, model = "../../v1/other")
        assertEquals("INVALID_MODEL", fail { GeminiProvider(p, secrets(p.secretName to "k"), http).complete(userMessage()) }.code)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun geminiNormalizesBlocksEmptyAnswersAndErrors() {
        val p = profile(ProviderCatalog.GEMINI)
        val s = secrets(p.secretName to "AIza-secret")
        fun code(canned: Canned) = fail { GeminiProvider(p, s, FakeHttp(canned)).complete(userMessage()) }
        assertEquals("BLOCKED_BY_PROVIDER", code(Canned.ok("""{"promptFeedback":{"blockReason":"SAFETY"}}""")).code)
        assertEquals("BLOCKED_BY_PROVIDER", code(Canned.ok("""{"candidates":[{"finishReason":"SAFETY"}]}""")).code)
        assertEquals("EMPTY_RESPONSE", code(Canned.ok("""{"candidates":[]}""")).code)
        val bad = code(Canned.json(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key. AIza-secret","status":"INVALID_ARGUMENT"}}"""))
        assertEquals("HTTP_400", bad.code)
        assertFalse(bad.message!!.contains("AIza-secret"))
        assertEquals("MISSING_CREDENTIALS", fail { GeminiProvider(p, MapSecrets(), FakeHttp(Canned.ok(geminiOk))).complete(userMessage()) }.code)
    }

    // ---- Anthropic Claude --------------------------------------------------------------------------------------------

    private val claudeOk = """{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"Hello"},{"type":"text","text":"world"}],"stop_reason":"end_turn"}"""

    @Test fun claudeBuildsAMessagesRequestWithHeadersAndSystemField() = runBlocking {
        val http = FakeHttp(Canned.ok(claudeOk))
        val p = profile(ProviderCatalog.CLAUDE, name = "Claude")
        val result = AnthropicProvider(p, secrets(p.secretName to "sk-ant-test"), http).complete(
            listOf(AiMessage("system", "Be brief."), AiMessage("user", "Hi"))
        )
        assertEquals(AiCompletion("Hello\nworld", "claude-sonnet-5-5", "Claude"), result)
        val request = http.only()
        assertEquals("https://api.anthropic.com/v1/messages", request.url)
        assertEquals("sk-ant-test", request.headers["x-api-key"])
        assertEquals(AnthropicProvider.API_VERSION, request.headers["anthropic-version"])
        assertNull(request.headers["Authorization"])
        val body = JSONObject(request.body)
        assertEquals("Be brief.", body.getString("system"))
        assertEquals(AnthropicProvider.MAX_TOKENS, body.getInt("max_tokens"))
        assertEquals(1, body.getJSONArray("messages").length())
    }

    @Test fun claudeMergesSameRoleTurnsAndDropsALeadingAssistantTurn() {
        val body = AnthropicProvider.requestBody(
            listOf(AiMessage("assistant", "orphan"), AiMessage("user", "a"), AiMessage("user", "b"), AiMessage("assistant", "c"), AiMessage("user", "d")),
            "claude-x"
        )
        val turns = body.getJSONArray("messages")
        assertEquals(3, turns.length())
        assertEquals("user", turns.getJSONObject(0).getString("role"))
        assertEquals("a\n\nb", turns.getJSONObject(0).getString("content"))
        assertEquals("assistant", turns.getJSONObject(1).getString("role"))
        assertFalse(body.has("system"))
        assertEquals("INVALID_REQUEST", assertThrows(AiProviderException::class.java) {
            AnthropicProvider.requestBody(listOf(AiMessage("assistant", "only")), "m")
        }.code)
    }

    @Test fun claudeNormalizesRefusalsEmptyAnswersAndErrors() {
        val p = profile(ProviderCatalog.CLAUDE)
        val s = secrets(p.secretName to "sk-ant-secret")
        fun err(canned: Canned) = fail { AnthropicProvider(p, s, FakeHttp(canned)).complete(userMessage()) }
        assertEquals("BLOCKED_BY_PROVIDER", err(Canned.ok("""{"content":[],"stop_reason":"refusal"}""")).code)
        assertEquals("EMPTY_RESPONSE", err(Canned.ok("""{"content":[{"type":"tool_use","name":"x"}]}""")).code)
        val denied = err(Canned.json(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key sk-ant-secret"}}"""))
        assertEquals("HTTP_401", denied.code)
        assertFalse(denied.message!!.contains("sk-ant-secret"))
        assertEquals("HTTP_529", err(Canned.json(529, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")).code)
        assertEquals("MISSING_CREDENTIALS", fail { AnthropicProvider(p, MapSecrets(), FakeHttp(Canned.ok(claudeOk))).complete(userMessage()) }.code)
    }

    // ---- Ollama ------------------------------------------------------------------------------------------------------

    private val ollamaOk = """{"model":"llama3.2","message":{"role":"assistant","content":"Local answer"},"done":true}"""

    @Test fun ollamaNeedsNoKeyAndPostsToApiChat() = runBlocking {
        val http = FakeHttp(Canned.ok(ollamaOk))
        val p = profile(ProviderCatalog.OLLAMA, name = "Laptop")
        val result = OllamaProvider(p, MapSecrets(), http).complete(userMessage())
        assertEquals(AiCompletion("Local answer", "llama3.2", "Laptop"), result)
        val request = http.only()
        assertEquals("http://127.0.0.1:11434/api/chat", request.url)
        assertNull("no key saved, so no Authorization header", request.headers["Authorization"])
        val body = JSONObject(request.body)
        assertFalse(body.getBoolean("stream"))
        assertEquals("llama3.2", body.getString("model"))
    }

    @Test fun ollamaSendsAnOptionalKeyAndAcceptsAFullChatPath() = runBlocking {
        val http = FakeHttp(Canned.ok(ollamaOk))
        val p = profile(ProviderCatalog.OLLAMA, endpoint = "https://ollama.example.com/api/chat")
        OllamaProvider(p, secrets(p.secretName to "proxy-token"), http).complete(userMessage())
        assertEquals("https://ollama.example.com/api/chat", http.only().url)
        assertEquals("Bearer proxy-token", http.only().headers["Authorization"])
        assertEquals("http://localhost:11434/api/chat", OllamaProvider.chatUrl("http://localhost:11434/"))
    }

    @Test fun ollamaRefusesCleartextToAnotherMachineBeforeAnyRequest() {
        val http = FakeHttp(Canned.ok(ollamaOk))
        listOf("http://192.168.1.20:11434", "http://example.com", "http://127.0.0.1.evil.example").forEach { endpoint ->
            val p = profile(ProviderCatalog.OLLAMA, endpoint = endpoint)
            assertEquals(endpoint, "INVALID_ENDPOINT", fail { OllamaProvider(p, MapSecrets(), http).complete(userMessage()) }.code)
        }
        assertTrue(http.requests.isEmpty())
    }

    @Test fun ollamaExplainsWhenTheServerIsNotRunning() {
        val p = profile(ProviderCatalog.OLLAMA)
        val e = fail { OllamaProvider(p, MapSecrets(), FakeHttp { throw ConnectException("Connection refused") }).complete(userMessage()) }
        assertEquals("CONNECTION_FAILED", e.code)
        assertTrue(e.message!!.contains("127.0.0.1:11434"))
    }

    @Test fun ollamaNormalizesErrors() {
        val p = profile(ProviderCatalog.OLLAMA)
        fun err(canned: Canned) = fail { OllamaProvider(p, MapSecrets(), FakeHttp(canned)).complete(userMessage()) }
        assertEquals("HTTP_404", err(Canned.json(404, """{"error":"model 'llama3.2' not found, try pulling it first"}""")).code)
        assertTrue(err(Canned.json(404, """{"error":"model 'llama3.2' not found, try pulling it first"}""")).message!!.contains("not found"))
        assertEquals("PROVIDER_ERROR", err(Canned.ok("""{"error":"out of memory"}""")).code)
        assertEquals("EMPTY_RESPONSE", err(Canned.ok("""{"message":{"role":"assistant","content":""}}""")).code)
        assertEquals("INVALID_RESPONSE", err(Canned.ok("""{"done":true}""")).code)
        val blank = profile(ProviderCatalog.OLLAMA, model = "  ")
        assertEquals("INVALID_MODEL", fail { OllamaProvider(blank, MapSecrets(), FakeHttp(Canned.ok(ollamaOk))).complete(userMessage()) }.code)
    }

    // ---- shared behaviour --------------------------------------------------------------------------------------------

    @Test fun everyAdapterRejectsMalformedMessageLists() {
        val http = FakeHttp(Canned.ok("{}"))
        val s = secrets("key-p-openai" to "k", "key-p-gemini" to "k", "key-p-claude" to "k")
        val adapters: List<AiProvider> = listOf(
            AiProviderClient(profile(ProviderCatalog.OPENAI), s, http), GeminiProvider(profile(ProviderCatalog.GEMINI), s, http),
            AnthropicProvider(profile(ProviderCatalog.CLAUDE), s, http), OllamaProvider(profile(ProviderCatalog.OLLAMA), s, http)
        )
        adapters.forEach { adapter ->
            assertNotNull(assertThrows(IllegalArgumentException::class.java) { runBlocking { adapter.complete(emptyList()) } })
            assertNotNull(assertThrows(IllegalArgumentException::class.java) { runBlocking { adapter.complete(listOf(AiMessage("tool", "x"))) } })
            assertNotNull(assertThrows(IllegalArgumentException::class.java) { runBlocking { adapter.complete(listOf(AiMessage("user", " "))) } })
        }
        assertTrue(http.requests.isEmpty())
    }

    @Test fun credentialsAreIsolatedPerProfile() = runBlocking {
        // Two Gemini profiles with different keys: each request carries only its own profile's key.
        val vault = secrets("key-a" to "KEY-A", "key-b" to "KEY-B")
        val a = profile(ProviderCatalog.GEMINI, id = "a", secretName = "key-a")
        val b = profile(ProviderCatalog.GEMINI, id = "b", secretName = "key-b")
        val http = FakeHttp(Canned.ok(geminiOk))
        GeminiProvider(a, vault, http).complete(userMessage())
        GeminiProvider(b, vault, http).complete(userMessage())
        assertEquals(listOf("KEY-A", "KEY-B"), http.requests.map { it.headers["x-goog-api-key"] })
        // A profile without a saved key never borrows another profile's.
        val c = profile(ProviderCatalog.GEMINI, id = "c", secretName = "key-c")
        assertEquals("MISSING_CREDENTIALS", fail { GeminiProvider(c, vault, http).complete(userMessage()) }.code)
        assertEquals(2, http.requests.size)
    }

    @Test fun requestsNeverPrintTheirHeaders() {
        val request = AiHttpRequest("https://x.example", mapOf("Authorization" to "Bearer sk-live"), "{}")
        assertFalse(request.toString().contains("sk-live"))
    }
}
