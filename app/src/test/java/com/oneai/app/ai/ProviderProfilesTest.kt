package com.oneai.app.ai

import com.oneai.app.security.SecretRead
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Decision D-V / V1, slice S1: provider profiles, migration, per-profile keys and the adapter registry. */
class ProviderProfilesTest {
    private class FakeStorage(
        var profiles: String? = null,
        var activeId: String? = null,
        val legacy: LegacyProviderSettings = LegacyProviderSettings(null, null, null)
    ) : ProviderProfileStorage {
        var writes = 0
        override fun readProfiles() = profiles
        override fun writeProfiles(json: String) { profiles = json; writes++ }
        override fun readActiveId() = activeId
        override fun writeActiveId(id: String) { activeId = id }
        override fun legacySettings() = legacy
    }

    private class FakeSecrets : ProfileSecrets {
        val map = HashMap<String?, String>()
        val unreadable = HashSet<String?>()
        override fun read(secretName: String?): SecretRead =
            if (secretName in unreadable) SecretRead.Unreadable
            else map[secretName]?.let { SecretRead.Present(it) } ?: SecretRead.Absent
        override fun save(secretName: String?, value: String) {
            if (value.isBlank()) map.remove(secretName) else map[secretName] = value
        }
    }

    private val logs = mutableListOf<String>()
    private val kinds = setOf(ProviderProfiles.DEFAULT_KIND)

    private fun profiles(storage: FakeStorage, secrets: FakeSecrets = FakeSecrets()) =
        ProviderProfiles(storage, secrets, { kinds }) { logs += it }

    private fun addOk(p: ProviderProfiles, name: String, endpoint: String = "https://api.example.com/v1/chat/completions", model: String = "m1"): AiProviderProfile =
        (p.add(name, endpoint, model) as ProfileOutcome.Ok).profile

    private fun source(relativePath: String): String {
        val candidates = listOf(File("src/main/java/$relativePath"), File("app/src/main/java/$relativePath"))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("Could not locate production source: $relativePath")
    }

    // ---- migration ----------------------------------------------------------------------------------------------

    @Test fun firstUseMigratesTheLegacySettingsIntoTheDefaultProfileKeepingThePrimaryKey() {
        val storage = FakeStorage(legacy = LegacyProviderSettings("https://llm.example.org/v1/chat", "my-model", "My service"))
        val secrets = FakeSecrets().also { it.map[null] = "legacy-primary-key" }
        val p = profiles(storage, secrets)
        val list = p.list()
        assertEquals(1, list.size)
        val d = list.single()
        assertEquals(ProviderProfiles.DEFAULT_ID, d.id)
        assertEquals("My service", d.name)
        assertEquals("https://llm.example.org/v1/chat", d.endpoint)
        assertEquals("my-model", d.model)
        assertEquals(ProviderProfiles.DEFAULT_KIND, d.kind)
        assertNull("the migrated profile keeps using the primary key", d.secretName)
        assertEquals("legacy-primary-key", (p.keyResult(d.id) as SecretRead.Present).value)
        assertEquals(d.id, p.activeId())
    }

    @Test fun migrationUsesTheOldDefaultsWhenNothingWasEverSaved() {
        val d = profiles(FakeStorage()).active()
        assertEquals(ProviderProfiles.DEFAULT_NAME, d.name)
        assertEquals(ProviderProfiles.DEFAULT_ENDPOINT, d.endpoint)
        assertEquals(ProviderProfiles.DEFAULT_MODEL, d.model)
    }

    @Test fun migrationIsPersistedOnceAndLaterLegacyChangesAreIgnored() {
        val storage = FakeStorage(legacy = LegacyProviderSettings("https://a.example/v1", "a", "A"))
        profiles(storage).list()
        val writesAfterFirst = storage.writes
        assertTrue(writesAfterFirst > 0)
        profiles(storage).list()
        profiles(storage).active()
        assertEquals("a second read must not write again", writesAfterFirst, storage.writes)
    }

    // ---- one active profile -------------------------------------------------------------------------------------

    @Test fun exactlyOneProfileIsActiveAndSwitchingChangesOnlyThat() {
        val storage = FakeStorage()
        val p = profiles(storage)
        val a = addOk(p, "Alpha")
        assertEquals("adding a profile does not change the active one", ProviderProfiles.DEFAULT_ID, p.activeId())
        assertTrue(p.setActive(a.id))
        assertEquals(a.id, p.active().id)
        assertEquals(a.id, profiles(storage).activeId())
        assertFalse(p.setActive("nope"))
        assertEquals(a.id, p.activeId())
    }

    @Test fun aStoredActiveIdThatNoLongerExistsFallsBackToTheFirstProfile() {
        val storage = FakeStorage()
        val p = profiles(storage)
        p.list()
        storage.activeId = "gone"
        assertEquals(ProviderProfiles.DEFAULT_ID, p.active().id)
    }

    // ---- add / update / remove ----------------------------------------------------------------------------------

    @Test fun anAddedProfileGetsItsOwnSecretNameThatTheVaultAccepts() {
        val p = profiles(FakeStorage())
        val a = addOk(p, "Alpha")
        assertEquals(ProviderProfileRules.secretNameFor(a.id), a.secretName)
        assertTrue(Regex("[a-z][a-z0-9_]{0,31}").matches(a.secretName!!))
        val b = addOk(p, "Beta")
        assertNotEquals(a.id, b.id)
        assertNotEquals(a.secretName, b.secretName)
    }

    @Test fun keysAreKeptPerProfileAndNeverShared() {
        val secrets = FakeSecrets().also { it.map[null] = "primary" }
        val p = profiles(FakeStorage(), secrets)
        val a = addOk(p, "Alpha")
        val b = addOk(p, "Beta")
        assertTrue(p.saveKey(a.id, " key-a "))
        assertEquals("key-a", (p.keyResult(a.id) as SecretRead.Present).value)
        assertEquals(SecretRead.Absent, p.keyResult(b.id))
        assertEquals("primary", (p.keyResult(ProviderProfiles.DEFAULT_ID) as SecretRead.Present).value)
        assertTrue(p.removeKey(a.id))
        assertEquals(SecretRead.Absent, p.keyResult(a.id))
        assertEquals("removing one key leaves the primary key alone", "primary", secrets.map[null])
    }

    @Test fun savingABlankKeyDoesNothing() {
        val p = profiles(FakeStorage())
        val a = addOk(p, "Alpha")
        assertFalse(p.saveKey(a.id, "   "))
        assertEquals(SecretRead.Absent, p.keyResult(a.id))
    }

    @Test fun anUnreadableKeyStaysUnreadableAndIsNotReportedAsMissing() {
        val secrets = FakeSecrets()
        val p = profiles(FakeStorage(), secrets)
        val a = addOk(p, "Alpha")
        secrets.unreadable += a.secretName
        assertEquals(SecretRead.Unreadable, p.keyResult(a.id))
    }

    @Test fun updateChangesOnlyTheEditableFieldsOfThatProfile() {
        val p = profiles(FakeStorage())
        val a = addOk(p, "Alpha")
        val out = p.update(a.id, "  Alpha two  ", "https://api.other.example/v1/chat", " model-2 ") as ProfileOutcome.Ok
        assertEquals("Alpha two", out.profile.name)
        assertEquals("https://api.other.example/v1/chat", out.profile.endpoint)
        assertEquals("model-2", out.profile.model)
        assertEquals(a.id, out.profile.id)
        assertEquals(a.kind, out.profile.kind)
        assertEquals(a.secretName, out.profile.secretName)
        assertEquals(ProviderProfiles.DEFAULT_ID, p.activeId())
    }

    @Test fun invalidFieldsAreRefusedAndNothingIsChanged() {
        val storage = FakeStorage()
        val p = profiles(storage)
        val a = addOk(p, "Alpha")
        val before = storage.profiles
        assertTrue(p.add("X", "http://insecure.example/v1", "m") is ProfileOutcome.Refused)
        assertTrue(p.add("X", "https://user:pw@api.example/v1", "m") is ProfileOutcome.Refused)
        assertTrue(p.add("  ", "https://api.example/v1", "m") is ProfileOutcome.Refused)
        assertTrue(p.add("X", "https://api.example/v1", "  ") is ProfileOutcome.Refused)
        assertTrue(p.add("X", "https://api.example/v1", "m".repeat(ProviderProfileRules.MAX_MODEL + 1)) is ProfileOutcome.Refused)
        assertTrue(p.update(a.id, "Alpha", "not a url", "m") is ProfileOutcome.Refused)
        assertTrue(p.update("missing", "Alpha", "https://api.example/v1", "m") is ProfileOutcome.Refused)
        assertEquals(before, storage.profiles)
    }

    @Test fun anUnknownKindIsRefusedWhenAdding() {
        val p = profiles(FakeStorage())
        val out = p.add("X", "https://api.example/v1", "m", kind = "mystery")
        assertTrue(out is ProfileOutcome.Refused)
        assertEquals(1, p.list().size)
    }

    @Test fun duplicateNamesAreRefusedIgnoringCaseButARenameToItselfIsFine() {
        val p = profiles(FakeStorage())
        val a = addOk(p, "Alpha")
        assertTrue(p.add("alpha", "https://api.example/v1", "m") is ProfileOutcome.Refused)
        assertTrue(p.update(a.id, "ALPHA", "https://api.example/v1", "m") is ProfileOutcome.Ok)
        assertTrue(p.update(a.id, ProviderProfiles.DEFAULT_NAME, "https://api.example/v1", "m") is ProfileOutcome.Refused)
    }

    @Test fun theNumberOfProfilesIsBounded() {
        val p = profiles(FakeStorage())
        for (i in 1 until ProviderProfileRules.MAX_PROFILES) addOk(p, "P$i")
        assertEquals(ProviderProfileRules.MAX_PROFILES, p.list().size)
        assertTrue(p.add("One too many", "https://api.example/v1", "m") is ProfileOutcome.Refused)
    }

    @Test fun theLastProfileCannotBeRemoved() {
        val p = profiles(FakeStorage())
        assertTrue(p.remove(ProviderProfiles.DEFAULT_ID) is ProfileOutcome.Refused)
        assertEquals(1, p.list().size)
    }

    @Test fun removingTheActiveProfileClearsItsKeyAndActivatesTheFirstRemaining() {
        val secrets = FakeSecrets().also { it.map[null] = "primary" }
        val p = profiles(FakeStorage(), secrets)
        val a = addOk(p, "Alpha")
        p.saveKey(a.id, "key-a")
        p.setActive(a.id)
        assertTrue(p.remove(a.id) is ProfileOutcome.Ok)
        assertEquals(ProviderProfiles.DEFAULT_ID, p.activeId())
        assertFalse(secrets.map.containsKey(a.secretName))
        assertEquals("primary", secrets.map[null])
    }

    @Test fun aRemovedIdIsReusedWithoutInheritingTheOldKey() {
        val p = profiles(FakeStorage())
        val a = addOk(p, "Alpha")
        p.saveKey(a.id, "old-key")
        p.remove(a.id)
        val b = addOk(p, "Beta")
        assertEquals("the freed id is reused", a.id, b.id)
        assertEquals(SecretRead.Absent, p.keyResult(b.id))
    }

    // ---- storage safety -----------------------------------------------------------------------------------------

    @Test fun noKeyTextReachesStorageLogsOrToString() {
        val storage = FakeStorage()
        val p = profiles(storage)
        val a = addOk(p, "Alpha")
        p.saveKey(a.id, "sk-super-secret-123")
        p.setActive(a.id)
        p.update(a.id, "Alpha", "https://api.example/v1", "m2")
        p.removeKey(a.id)
        assertFalse(storage.profiles!!.contains("sk-super-secret-123"))
        assertTrue(logs.none { it.contains("sk-super-secret-123") })
        assertTrue(logs.isNotEmpty())
        assertFalse(a.toString().contains("sk-super-secret-123"))
        assertFalse(SecretRead.Present("sk-super-secret-123").toString().contains("sk-super-secret-123"))
    }

    @Test fun anUnreadableStoredListIsShownAsTheDefaultButNotOverwrittenByAReadAlone() {
        val storage = FakeStorage(profiles = "{ this is not json", activeId = "p1")
        val p = profiles(storage)
        assertEquals(ProviderProfiles.DEFAULT_ID, p.active().id)
        p.list()
        assertEquals("a read never replaces the stored text", "{ this is not json", storage.profiles)
    }

    @Test fun damagedEntriesAreSkippedAndGoodOnesKept() {
        val good = ProviderProfileCodec.encode(listOf(AiProviderProfile("p1", "Good", "openai_compatible", "https://a.example/v1", "m", "ai_p1")))
        val withBad = good.dropLast(1) +
            ",{\"id\":\"BAD ID\",\"name\":\"x\",\"kind\":\"openai_compatible\"}" +
            ",{\"id\":\"p2\",\"name\":\"Wrong secret\",\"kind\":\"openai_compatible\",\"endpoint\":\"https://b.example\",\"model\":\"m\",\"secret\":\"ai_other\"}" +
            ",{\"id\":\"p1\",\"name\":\"Duplicate id\",\"kind\":\"openai_compatible\"}" +
            ",42]"
        val list = profiles(FakeStorage(profiles = withBad)).list()
        assertEquals(listOf("p1"), list.map { it.id })
    }

    @Test fun anEmptyStoredListFallsBackToTheDefaultProfile() {
        assertEquals(ProviderProfiles.DEFAULT_ID, profiles(FakeStorage(profiles = "[]")).active().id)
    }

    @Test fun profilesRoundTripThroughTheCodecIncludingThePrimaryKeyMarker() {
        val list = listOf(
            AiProviderProfile("default", "One", "openai_compatible", "https://a.example/v1", "m", null),
            AiProviderProfile("p1", "Two", "openai_compatible", "https://b.example/v1", "n", "ai_p1")
        )
        assertEquals(list, ProviderProfileCodec.decode(ProviderProfileCodec.encode(list)))
        assertNull(ProviderProfileCodec.decode("not json"))
    }

    @Test fun displayNamesAreCleanedAndBounded() {
        assertEquals("ab", ProviderProfileRules.cleanName(" a\u0000b\n "))
        assertEquals(ProviderProfileRules.MAX_NAME, ProviderProfileRules.cleanName("x".repeat(500)).length)
    }

    @Test fun suggestedNamesAreUnused() {
        val p = profiles(FakeStorage())
        val first = p.suggestName()
        addOk(p, first)
        assertNotEquals(first, p.suggestName())
    }

    // ---- registry and active provider ---------------------------------------------------------------------------

    private class Echo(private val label: String) : AiProvider {
        override suspend fun complete(messages: List<AiMessage>) = AiCompletion(label, "m", label)
    }

    @Test fun anUnknownKindIsRefusedByTheRegistryWithASafeError() {
        val registry = AiProviderRegistry()
        registry.register("openai_compatible") { Echo(it.id) }
        val profile = AiProviderProfile("p1", "X", "mystery", "https://a.example/v1", "m", "ai_p1")
        val e = assertThrows(AiProviderException::class.java) { registry.create(profile) }
        assertEquals("UNKNOWN_PROVIDER_KIND", e.code)
    }

    @Test fun aKindCanBeRegisteredOnlyOnceAndMustBeValid() {
        val registry = AiProviderRegistry()
        registry.register("openai_compatible") { Echo(it.id) }
        assertThrows(IllegalArgumentException::class.java) { registry.register("openai_compatible") { Echo(it.id) } }
        assertThrows(IllegalArgumentException::class.java) { registry.register("Bad Kind") { Echo(it.id) } }
        assertEquals(setOf("openai_compatible"), registry.kinds())
    }

    @Test fun aNewKindIsOneRegisteredFactoryAndNeedsNoOtherChange() {
        val registry = AiProviderRegistry()
        registry.register("openai_compatible") { Echo("oa-" + it.id) }
        registry.register("future_kind") { Echo("fk-" + it.id) }
        val p = ProviderProfiles(FakeStorage(), FakeSecrets(), registry::kinds) { }
        val future = (p.add("Future", "https://f.example/v1", "m", kind = "future_kind") as ProfileOutcome.Ok).profile
        p.setActive(future.id)
        val answer = runBlocking { ActiveAiProvider(p, registry).complete(listOf(AiMessage("user", "hi"))) }
        assertEquals("fk-" + future.id, answer.content)
    }

    @Test fun theActiveProviderFollowsTheActiveProfileOnEveryRequest() {
        val registry = AiProviderRegistry()
        registry.register("openai_compatible") { Echo(it.id) }
        val p = ProviderProfiles(FakeStorage(), FakeSecrets(), registry::kinds) { }
        val a = (p.add("Alpha", "https://a.example/v1", "m") as ProfileOutcome.Ok).profile
        val active = ActiveAiProvider(p, registry)
        val ask = { runBlocking { active.complete(listOf(AiMessage("user", "hi"))).content } }
        assertEquals(ProviderProfiles.DEFAULT_ID, ask())
        p.setActive(a.id)
        assertEquals(a.id, ask())
    }

    // ---- source guards ------------------------------------------------------------------------------------------

    @Test fun theClientReadsItsKeyFromTheProfileAndNoLongerFromTheStore() {
        val client = source("com/oneai/app/ai/AiProviderClient.kt")
        assertTrue(client.contains("secrets.read(profile.secretName)"))
        assertFalse(client.contains("OneAiStore"))
        assertFalse(client.contains("SecretVault"))
    }

    @Test fun theStoreNoLongerOffersTheSingleConfigurationSetters() {
        val store = source("com/oneai/app/core/OneAiStore.kt")
        assertFalse(store.contains("fun setAiEndpoint"))
        assertFalse(store.contains("fun setAiModel"))
        assertFalse(store.contains("fun setAiProviderName"))
        assertTrue(store.contains("override fun legacySettings()"))
    }

    @Test fun appServicesUsesTheActiveProfileProviderAndSharesTheProfiles() {
        val services = source("com/oneai/app/AppServices.kt")
        assertTrue(services.contains("ActiveAiProvider(providerProfiles, aiRegistry)"))
        assertFalse(services.contains("AiProviderClient("))
    }

    @Test fun theProfileLogicIsPureKotlinWithNoAndroidTypes() {
        listOf("AiProviderProfile.kt", "ProviderProfiles.kt", "AiProviderRegistry.kt").forEach { name ->
            val text = source("com/oneai/app/ai/$name")
            assertFalse("$name must not import android.*", text.lines().any { it.startsWith("import android.") })
            assertFalse("$name must not use SecretVault or OneAiStore", text.contains("SecretVault") || text.contains("OneAiStore"))
        }
    }

    @Test fun settingsEditsProfilesThroughTheManagerAndNeverTouchesTheVaultDirectly() {
        val main = source("com/oneai/app/MainActivity.kt")
        val page = main.substring(main.indexOf("private fun SettingsPage"))
        assertTrue(page.contains("profiles.add("))
        assertTrue(page.contains("profiles.setActive("))
        assertTrue(page.contains("profiles.remove("))
        assertTrue(page.contains("profiles.saveKey("))
        assertFalse(page.contains("vault."))
        assertFalse(page.contains("saveNamed"))
    }
}
