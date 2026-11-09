package com.oneai.app.ai

import com.oneai.app.security.CredentialStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {
    @Test fun catalogHasTheNineRequiredProvidersWithUniqueKinds() {
        val titles = ProviderCatalog.all.map { it.title }
        assertEquals(
            listOf("OpenAI", "ChatGPT Account", "Google Gemini", "Anthropic Claude", "DeepSeek", "Groq", "OpenRouter", "Ollama (local)", "Custom Provider"),
            titles
        )
        assertEquals(9, ProviderCatalog.all.map { it.kind }.toSet().size)
        assertTrue(ProviderCatalog.all.all { ProviderProfileRules.KIND.matches(it.kind) })
    }

    @Test fun customProviderKeepsTheKindExistingProfilesAlreadyUse() {
        assertEquals(ProviderProfiles.DEFAULT_KIND, ProviderCatalog.CUSTOM)
        assertEquals("Custom Provider", ProviderCatalog.descriptor(ProviderProfiles.DEFAULT_KIND)!!.title)
        // A stored kind this build does not know is edited as a custom provider, never crashes the Settings screen.
        assertEquals(ProviderCatalog.CUSTOM, ProviderCatalog.descriptorOrCustom("future_kind").kind)
    }

    @Test fun everyDefaultSatisfiesItsOwnRules() {
        ProviderCatalog.all.forEach { d ->
            assertNull("${d.kind} default endpoint", ProviderCatalog.endpointError(d.kind, d.defaultEndpoint))
            assertNull("${d.kind} default model", ProviderCatalog.modelError(d.kind, d.defaultModel))
            assertNull("${d.kind} defaults as a profile", ProviderProfileRules.fieldError(d.kind, d.title, d.defaultEndpoint, d.defaultModel))
            assertTrue("${d.kind} default model is suggested", d.models.isEmpty() || d.defaultModel in d.models)
        }
    }

    @Test fun authenticationRequirementsAreProviderSpecific() {
        fun auth(kind: String) = ProviderCatalog.descriptor(kind)!!.auth
        assertEquals(ProviderAuth.API_KEY, auth(ProviderCatalog.OPENAI))
        assertEquals(ProviderAuth.ACCOUNT_OAUTH, auth(ProviderCatalog.CHATGPT_ACCOUNT))
        assertEquals(ProviderAuth.OPTIONAL_API_KEY, auth(ProviderCatalog.OLLAMA))
        listOf(ProviderCatalog.GEMINI, ProviderCatalog.CLAUDE, ProviderCatalog.DEEPSEEK, ProviderCatalog.GROQ, ProviderCatalog.OPENROUTER, ProviderCatalog.CUSTOM)
            .forEach { assertEquals(it, ProviderAuth.API_KEY, auth(it)) }
    }

    @Test fun openAiApiKeyAndChatGptAccountAreSeparateProviders() {
        val openAi = ProviderCatalog.descriptor(ProviderCatalog.OPENAI)!!
        val chatGpt = ProviderCatalog.descriptor(ProviderCatalog.CHATGPT_ACCOUNT)!!
        assertTrue(openAi.defaultEndpoint.startsWith("https://api.openai.com/"))
        assertEquals(ProviderCatalog.CHATGPT_RESPONSES_ENDPOINT, chatGpt.defaultEndpoint)
        assertFalse(openAi.defaultEndpoint == chatGpt.defaultEndpoint)
        assertFalse(chatGpt.endpointEditable)
    }

    @Test fun chatGptEndpointCannotBeRedirectedToAnotherHost() {
        assertNull(ProviderCatalog.endpointError(ProviderCatalog.CHATGPT_ACCOUNT, ProviderCatalog.CHATGPT_RESPONSES_ENDPOINT))
        assertNotNull(ProviderCatalog.endpointError(ProviderCatalog.CHATGPT_ACCOUNT, "https://attacker.example/responses"))
        // Whatever the user typed, the stored endpoint for a fixed provider is the fixed address.
        assertEquals(
            ProviderCatalog.CHATGPT_RESPONSES_ENDPOINT,
            ProviderProfileRules.storedEndpoint(ProviderCatalog.CHATGPT_ACCOUNT, "https://attacker.example/responses")
        )
        assertEquals("https://x.example/v1", ProviderProfileRules.storedEndpoint(ProviderCatalog.CUSTOM, "  https://x.example/v1 "))
    }

    @Test fun apiKeyProvidersRequireHttps() {
        listOf(ProviderCatalog.OPENAI, ProviderCatalog.GEMINI, ProviderCatalog.CLAUDE, ProviderCatalog.DEEPSEEK, ProviderCatalog.GROQ, ProviderCatalog.OPENROUTER, ProviderCatalog.CUSTOM)
            .forEach { kind ->
                assertNotNull("$kind http", ProviderCatalog.endpointError(kind, "http://api.example.com/v1"))
                assertNotNull("$kind localhost http", ProviderCatalog.endpointError(kind, "http://127.0.0.1:11434/v1"))
                assertNotNull("$kind credentials in URL", ProviderCatalog.endpointError(kind, "https://user:pw@api.example.com/v1"))
            }
    }

    @Test fun ollamaAcceptsPlainHttpOnlyForThisDevice() {
        val kind = ProviderCatalog.OLLAMA
        listOf(
            "http://127.0.0.1:11434", "http://localhost:11434", "http://LOCALHOST:11434/", "http://127.0.0.1", "http://127.1.2.3:8080",
            "http://[::1]:11434", "https://ollama.example.com", "https://192.168.1.20:11434"
        ).forEach { assertNull(it, ProviderCatalog.endpointError(kind, it)) }
        listOf(
            "http://192.168.1.20:11434", "http://10.0.2.2:11434", "http://ollama.example.com", "http://127.0.0.1.evil.example:11434",
            "http://localhost.evil.example", "http://0.0.0.0:11434", "http://[::ffff:8.8.8.8]:11434", "ftp://127.0.0.1", "127.0.0.1:11434",
            "", "http://user:pw@127.0.0.1:11434", "http://127.0.0.1:11434/#frag", "http://999.0.0.1"
        ).forEach { assertNotNull("should refuse: $it", ProviderCatalog.endpointError(kind, it)) }
    }

    @Test fun geminiModelNameCannotChangeTheRequestPath() {
        listOf("gemini-2.0-flash", "models/gemini-2.5-pro", "gemini-1.5-flash-8b").forEach { assertNull(it, ProviderCatalog.modelError(ProviderCatalog.GEMINI, it)) }
        listOf("../../v1/secret", "gemini/x", "gemini?key=1", "gemini#x", "a b", "gemini:generateContent", "").forEach {
            assertNotNull("should refuse: $it", ProviderCatalog.modelError(ProviderCatalog.GEMINI, it))
        }
        // Other providers keep accepting any printable model id (OpenRouter ids contain slashes).
        assertNull(ProviderCatalog.modelError(ProviderCatalog.OPENROUTER, "meta-llama/llama-3.3-70b-instruct"))
        assertNotNull(ProviderCatalog.modelError(ProviderCatalog.OPENAI, "gpt\u0000x"))
    }

    @Test fun profileValidationUsesTheProvidersOwnEndpointRule() {
        assertNull(ProviderProfileRules.fieldError(ProviderCatalog.OLLAMA, "Home", "http://127.0.0.1:11434", "llama3.2"))
        assertNotNull(ProviderProfileRules.fieldError(ProviderCatalog.CUSTOM, "Home", "http://127.0.0.1:11434", "llama3.2"))
        // The original three-argument form still means "custom provider".
        assertNotNull(ProviderProfileRules.fieldError("Home", "http://127.0.0.1:11434", "m"))
        assertNull(ProviderProfileRules.fieldError("Home", "https://api.example.com/v1/chat/completions", "m"))
    }

    @Test fun onlyProvidersWithAnAdapterAreOffered() {
        assertEquals(emptyList<ProviderDescriptor>(), ProviderCatalog.available(emptySet()))
        val without = ProviderCatalog.available(AiProviders.registry(MapSecrets(), FakeHttp(Canned.ok("{}"))).kinds())
        assertFalse(without.any { it.kind == ProviderCatalog.CHATGPT_ACCOUNT })
        assertEquals(8, without.size)
        val account = ChatGptAccount(MemorySession(), FakeHttp(Canned.ok("{}")))
        val withAccount = ProviderCatalog.available(AiProviders.registry(MapSecrets(), FakeHttp(Canned.ok("{}")), account).kinds())
        assertEquals(ProviderCatalog.all.map { it.kind }, withAccount.map { it.kind })
    }

    @Test fun connectionStatesFollowAuthKeysAndAccount() {
        fun state(kind: String, key: CredentialStatus = CredentialStatus.NOT_SAVED, link: AccountLink = AccountLink.NOT_CONNECTED, issue: String? = null, adapter: Boolean = true) =
            ProviderConnection.of(ProviderCatalog.descriptor(kind)!!, adapter, key, link, issue)
        assertEquals(ProviderConnectionState.NEEDS_KEY, state(ProviderCatalog.GEMINI))
        assertEquals(ProviderConnectionState.READY, state(ProviderCatalog.GEMINI, CredentialStatus.SAVED))
        assertEquals(ProviderConnectionState.CREDENTIALS_UNREADABLE, state(ProviderCatalog.CLAUDE, CredentialStatus.UNREADABLE))
        assertEquals(ProviderConnectionState.LOCAL_READY, state(ProviderCatalog.OLLAMA))
        assertEquals(ProviderConnectionState.CREDENTIALS_UNREADABLE, state(ProviderCatalog.OLLAMA, CredentialStatus.UNREADABLE))
        assertEquals(ProviderConnectionState.NOT_CONNECTED, state(ProviderCatalog.CHATGPT_ACCOUNT))
        assertEquals(ProviderConnectionState.CONNECTED, state(ProviderCatalog.CHATGPT_ACCOUNT, link = AccountLink.CONNECTED))
        assertEquals(ProviderConnectionState.CREDENTIALS_UNREADABLE, state(ProviderCatalog.CHATGPT_ACCOUNT, link = AccountLink.UNREADABLE))
        // An API key saved on a ChatGPT profile does not make the account connected, and the reverse.
        assertEquals(ProviderConnectionState.NOT_CONNECTED, state(ProviderCatalog.CHATGPT_ACCOUNT, key = CredentialStatus.SAVED))
        assertEquals(ProviderConnectionState.NEEDS_KEY, state(ProviderCatalog.OPENAI, link = AccountLink.CONNECTED))
        assertEquals(ProviderConnectionState.INVALID_SETTINGS, state(ProviderCatalog.OPENAI, CredentialStatus.SAVED, issue = "bad"))
        assertEquals(ProviderConnectionState.ADAPTER_UNAVAILABLE, state(ProviderCatalog.OPENAI, CredentialStatus.SAVED, adapter = false))
        assertTrue(ProviderConnectionState.READY.usable && ProviderConnectionState.CONNECTED.usable && ProviderConnectionState.LOCAL_READY.usable)
        assertFalse(ProviderConnectionState.NEEDS_KEY.usable || ProviderConnectionState.ADAPTER_UNAVAILABLE.usable)
    }

    @Test fun forProfileCombinesAdapterKeyAndSettings() {
        val kinds = setOf(ProviderCatalog.OPENAI)
        val ok = profile(ProviderCatalog.OPENAI)
        assertEquals(ProviderConnectionState.READY, ProviderConnection.forProfile(ok, kinds, CredentialStatus.SAVED, AccountLink.NOT_CONNECTED))
        assertEquals(ProviderConnectionState.ADAPTER_UNAVAILABLE, ProviderConnection.forProfile(profile(ProviderCatalog.GEMINI), kinds, CredentialStatus.SAVED, AccountLink.NOT_CONNECTED))
        assertEquals(
            ProviderConnectionState.INVALID_SETTINGS,
            ProviderConnection.forProfile(ok.copy(endpoint = "http://api.openai.com"), kinds, CredentialStatus.SAVED, AccountLink.NOT_CONNECTED)
        )
    }

    @Test fun loopbackDetectionIsExact() {
        listOf("localhost", "127.0.0.1", "127.255.255.254", "::1", "[::1]").forEach { assertTrue(it, LoopbackHosts.isLoopback(it)) }
        listOf("128.0.0.1", "127.0.0", "127.0.0.256", "localhost.example", "example.com", "", null, "10.0.0.1").forEach { assertFalse("$it", LoopbackHosts.isLoopback(it)) }
    }
}
