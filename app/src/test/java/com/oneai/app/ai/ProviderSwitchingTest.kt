package com.oneai.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Profiles + registry + active provider + orchestrator, with mock HTTP: switching the active profile switches the service. */
class ProviderSwitchingTest {
    private val storage = MemoryProfileStorage()
    private val secrets = MapSecrets()
    private val logs = ArrayList<String>()
    private val http = FakeHttp { request ->
        when {
            "openai.com" in request.url -> Canned.ok("""{"model":"gpt","choices":[{"message":{"content":"from openai"}}]}""")
            "generativelanguage" in request.url -> Canned.ok("""{"candidates":[{"content":{"parts":[{"text":"from gemini"}]}}]}""")
            "anthropic" in request.url -> Canned.ok("""{"content":[{"type":"text","text":"from claude"}]}""")
            "127.0.0.1" in request.url -> Canned.ok("""{"message":{"content":"from ollama"}}""")
            else -> Canned(404, "")
        }
    }
    private val registry = AiProviders.registry(secrets, http)
    private val profiles = ProviderProfiles(storage, secrets, registry::kinds) { logs += it }
    private val provider = ActiveAiProvider(profiles, registry)
    private val orchestrator = AiOrchestrator(provider) { logs += it }

    private fun add(kind: String, key: String?): AiProviderProfile {
        val d = ProviderCatalog.descriptor(kind)!!
        val added = (profiles.add(profiles.suggestName(d.title), ProviderProfileRules.storedEndpoint(kind, d.defaultEndpoint), d.defaultModel, kind) as ProfileOutcome.Ok).profile
        if (key != null) assertTrue(profiles.saveKey(added.id, key))
        return added
    }

    @Test fun switchingTheActiveProfileChangesTheServiceAndTheKey() = runBlocking {
        val openai = add(ProviderCatalog.OPENAI, "sk-openai")
        val gemini = add(ProviderCatalog.GEMINI, "AIza-gem")
        val claude = add(ProviderCatalog.CLAUDE, "sk-ant")
        val ollama = add(ProviderCatalog.OLLAMA, null)

        suspend fun ask(profile: AiProviderProfile): String {
            assertTrue(profiles.setActive(profile.id))
            return orchestrator.respond("hello").content
        }
        assertEquals("from openai", ask(openai))
        assertEquals("from gemini", ask(gemini))
        assertEquals("from claude", ask(claude))
        assertEquals("from ollama", ask(ollama))
        assertEquals("from gemini", ask(gemini))

        val sent = http.requests
        assertEquals("Bearer sk-openai", sent[0].headers["Authorization"])
        assertEquals("AIza-gem", sent[1].headers["x-goog-api-key"])
        assertEquals("sk-ant", sent[2].headers["x-api-key"])
        assertNull(sent[3].headers["Authorization"])
        // Every request carried only its own provider's credential.
        sent.forEachIndexed { i, r ->
            val others = listOf("sk-openai", "AIza-gem", "sk-ant").filterIndexed { j, _ -> j != i && i < 3 }
            others.forEach { secret -> assertFalse("request $i leaked $secret", r.headers.values.any { it.contains(secret) } || r.url.contains(secret) || r.body.contains(secret)) }
        }
    }

    @Test fun theProviderFollowsTheActiveProfileOnEveryRequestWithoutRebuilding() = runBlocking {
        val a = add(ProviderCatalog.OPENAI, "k1")
        val b = add(ProviderCatalog.GEMINI, "k2")
        profiles.setActive(a.id)
        assertEquals("from openai", provider.complete(userMessage()).content)
        profiles.setActive(b.id)
        assertEquals("from gemini", provider.complete(userMessage()).content)
    }

    @Test fun profilesOfTheSameKindKeepSeparateKeys() = runBlocking {
        val one = add(ProviderCatalog.OPENAI, "sk-one")
        val two = add(ProviderCatalog.OPENAI, "sk-two")
        assertTrue(one.name != two.name)
        profiles.setActive(one.id); provider.complete(userMessage())
        profiles.setActive(two.id); provider.complete(userMessage())
        assertEquals(listOf("Bearer sk-one", "Bearer sk-two"), http.requests.map { it.headers["Authorization"] })
        // Removing one profile's key does not touch the other's.
        assertTrue(profiles.removeKey(one.id))
        profiles.setActive(two.id); provider.complete(userMessage())
        assertEquals("Bearer sk-two", http.requests.last().headers["Authorization"])
        profiles.setActive(one.id)
        assertEquals("MISSING_CREDENTIALS", assertThrows(AiProviderException::class.java) { runBlocking { provider.complete(userMessage()) } }.code)
    }

    @Test fun theExistingLegacyProfileKeepsItsPrimaryKeyAndStillWorks() = runBlocking {
        secrets.map[null] = "sk-legacy"
        val legacy = profiles.active() // first use migrates the single saved provider
        assertEquals(ProviderCatalog.CUSTOM, legacy.kind)
        assertNull(legacy.secretName)
        provider.complete(userMessage())
        // The default custom endpoint is not one of the mock hosts, so only the credential and kind are checked here.
        assertEquals("Bearer sk-legacy", http.requests.single().headers["Authorization"])
        // Adding other providers later never replaces it.
        add(ProviderCatalog.CLAUDE, "sk-ant")
        assertEquals("sk-legacy", secrets.map[null])
        assertTrue(profiles.list().any { it.id == legacy.id })
    }

    @Test fun aProfileWhoseAdapterIsMissingIsRefusedNotGuessed() {
        val withoutAccount = add(ProviderCatalog.OPENAI, "k")
        // ChatGPT Account is not registered here, so it cannot even be added.
        val refused = profiles.add("ChatGPT", ProviderCatalog.CHATGPT_RESPONSES_ENDPOINT, "gpt-6-luna", ProviderCatalog.CHATGPT_ACCOUNT)
        assertTrue(refused is ProfileOutcome.Refused)
        assertTrue(profiles.list().contains(withoutAccount))
    }

    @Test fun invalidProviderSettingsAreRefusedAtTheProfileLevel() {
        val bad = listOf(
            Triple(ProviderCatalog.OPENAI, "http://api.openai.com/v1", "gpt-4o"),
            Triple(ProviderCatalog.OLLAMA, "http://192.168.1.5:11434", "llama3.2"),
            Triple(ProviderCatalog.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "../x")
        )
        bad.forEach { (kind, endpoint, model) ->
            assertTrue("$kind $endpoint", profiles.add("Name $kind", endpoint, model, kind) is ProfileOutcome.Refused)
        }
    }

    @Test fun noRequestOrLogEverContainsASavedKey() = runBlocking {
        val p = add(ProviderCatalog.OPENAI, "sk-very-secret")
        profiles.setActive(p.id)
        orchestrator.respond("hello")
        assertTrue(logs.none { it.contains("sk-very-secret") })
        assertFalse(http.only().toString().contains("sk-very-secret"))
    }
}
