package com.oneai.app.plugins

import com.oneai.app.core.PluginConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [PluginConfigStore] for JVM tests. Counts writes so tests can prove nothing was saved. */
class FakePluginConfigStore : PluginConfigStore {
    private val data = linkedMapOf<String, Map<String, String>>()
    var saves = 0
        private set

    override fun values(pluginId: String): Map<String, String> = data[pluginId]?.toMap() ?: emptyMap()
    override fun save(pluginId: String, schemaVersion: Int, values: Map<String, String>) {
        saves++
        data[pluginId] = values.toMap()
    }
    override fun remove(pluginId: String) { data.remove(pluginId) }
    fun hasEntry(pluginId: String) = pluginId in data
}

/** Stage 9.6b: host-side validation, persistence, isolation, reset, required-setting and update-reconciliation rules. */
class PluginConfigurationTest {
    private val a = "dev.example.alpha"
    private val b = "dev.example.beta"

    private val schema: PluginSettingsSchema = PluginSettingsValidator.validateSchema(
        PluginSettingsSchema(settings = listOf(
            PluginSettingDeclaration("prefix", PluginSettingType.TEXT, "Prefix", default = "", maxLength = 40),
            PluginSettingDeclaration("loud", PluginSettingType.TOGGLE, "Uppercase replies", default = "false"),
            PluginSettingDeclaration("repeat", PluginSettingType.NUMBER, "Repeat count", default = "1", min = 1, max = 5),
            PluginSettingDeclaration(
                "mode", PluginSettingType.CHOICE, "Mode", required = true,
                options = listOf(PluginSettingOption("plain", "Plain"), PluginSettingOption("fancy", "Fancy"))
            )
        ))
    ).getOrThrow()

    private class Fixture(schema: PluginSettingsSchema?, trusted: Set<String>) {
        val store = FakePluginConfigStore()
        val logs = mutableListOf<String>()
        val config = PluginConfiguration(store, { id -> schema.takeIf { id in trusted } }, { logs += it })
    }

    private fun fixture(vararg trusted: String) = Fixture(schema, trusted.toSet())

    @Test fun effectiveValuesFallBackToDeclaredDefaults() {
        val f = fixture(a)
        assertEquals(mapOf("prefix" to "", "loud" to "false", "repeat" to "1"), f.config.effective(a))
    }

    @Test fun validUpdateIsStoredAndVisibleAsEffectiveValues() {
        val f = fixture(a)
        val result = f.config.update(a, mapOf("repeat" to "3", "mode" to "fancy")).getOrThrow()
        assertEquals("3", result["repeat"])
        assertEquals("fancy", result["mode"])
        assertEquals(result, f.config.effective(a))
        assertEquals(mapOf("repeat" to "3", "mode" to "fancy"), f.store.values(a))
    }

    @Test fun anInvalidUpdateSavesNothingAndNeverEchoesTheValue() {
        val f = fixture(a)
        f.config.update(a, mapOf("repeat" to "2")).getOrThrow()
        val savesBefore = f.store.saves
        val failure = f.config.update(a, mapOf("repeat" to "99999", "mode" to "plain"))
        assertTrue(failure.isFailure)
        assertFalse("values may be personal; messages never repeat them", failure.exceptionOrNull()!!.message!!.contains("99999"))
        assertEquals("nothing was saved, not even the valid part", savesBefore, f.store.saves)
        assertEquals(mapOf("repeat" to "2"), f.store.values(a))
    }

    @Test fun anUnknownKeyIsRejected() {
        val f = fixture(a)
        assertTrue(f.config.update(a, mapOf("not_declared" to "x")).isFailure)
        assertEquals(0, f.store.saves)
    }

    @Test fun updatesMergeWithWhatIsAlreadyStored() {
        val f = fixture(a)
        f.config.update(a, mapOf("loud" to "true")).getOrThrow()
        f.config.update(a, mapOf("prefix" to "Hi")).getOrThrow()
        assertEquals(mapOf("loud" to "true", "prefix" to "Hi"), f.store.values(a))
    }

    @Test fun oneStaleStoredValueDoesNotBlockAnUnrelatedChange() {
        val f = fixture(a)
        f.store.save(a, 1, mapOf("repeat" to "42", "loud" to "true"))
        val result = f.config.update(a, mapOf("prefix" to "Hi")).getOrThrow()
        assertEquals("1", result["repeat"])
        assertEquals("true", result["loud"])
        assertEquals(mapOf("loud" to "true", "prefix" to "Hi"), f.store.values(a))
    }

    @Test fun pluginsDoNotSeeEachOthersValues() {
        val f = fixture(a, b)
        f.config.update(a, mapOf("prefix" to "alpha-only")).getOrThrow()
        assertEquals("alpha-only", f.config.effective(a)["prefix"])
        assertEquals("", f.config.effective(b)["prefix"])
        assertTrue(f.store.values(b).isEmpty())
    }

    @Test fun activityNamesChangedKeysButNeverValues() {
        val f = fixture(a)
        f.config.update(a, mapOf("prefix" to "hunter2", "repeat" to "4")).getOrThrow()
        assertEquals(1, f.logs.size)
        assertTrue(f.logs.single().contains("prefix"))
        assertTrue(f.logs.single().contains("repeat"))
        assertTrue(f.logs.none { it.contains("hunter2") })
        assertFalse(f.logs.single().contains("repeat: 4"))
    }

    @Test fun anUpdateThatChangesNothingLogsNothing() {
        val f = fixture(a)
        f.config.update(a, mapOf("repeat" to "1")).getOrThrow()
        assertTrue(f.logs.isEmpty())
    }

    @Test fun aPluginThatIsNotTrustedHasNoConfigurableSettings() {
        val f = fixture()
        assertTrue(f.config.update(a, mapOf("repeat" to "2")).isFailure)
        assertTrue(f.config.reset(a).isFailure)
        assertTrue(f.config.effective(a).isEmpty())
        assertTrue(f.config.missingRequired(a).isEmpty())
        assertEquals(0, f.store.saves)
    }

    @Test fun resetReturnsEverySettingToItsDefault() {
        val f = fixture(a)
        f.config.update(a, mapOf("repeat" to "5", "mode" to "fancy")).getOrThrow()
        f.config.reset(a).getOrThrow()
        assertTrue(f.store.values(a).isEmpty())
        assertEquals("1", f.config.effective(a)["repeat"])
        assertTrue(f.logs.last().contains("Reset"))
    }

    @Test fun requiredSettingsWithoutADefaultAreReportedUntilTheyHaveAValue() {
        val f = fixture(a)
        assertEquals(listOf("mode"), f.config.missingRequired(a).map { it.key })
        assertEquals("titles are available for the user message", "Mode", f.config.missingRequired(a).single().title)
        f.config.update(a, mapOf("mode" to "plain")).getOrThrow()
        assertTrue(f.config.missingRequired(a).isEmpty())
    }

    @Test fun reconcileKeepsValidValuesResetsInvalidOnesAndDropsUndeclaredOnes() {
        val f = fixture(a)
        f.store.save(a, 1, mapOf("repeat" to "4", "loud" to "true", "old_key" to "secret-value", "mode" to "plain"))
        val updated = PluginSettingsValidator.validateSchema(
            PluginSettingsSchema(settings = listOf(
                PluginSettingDeclaration("loud", PluginSettingType.TOGGLE, "Uppercase replies", default = "false"),
                PluginSettingDeclaration("repeat", PluginSettingType.NUMBER, "Repeat count", default = "1", min = 1, max = 3),
                PluginSettingDeclaration(
                    "mode", PluginSettingType.CHOICE, "Mode", required = true,
                    options = listOf(PluginSettingOption("fancy", "Fancy"), PluginSettingOption("loud_mode", "Loud"))
                )
            ))
        ).getOrThrow()

        f.config.reconcile(a, updated)

        assertEquals("only the still-valid, still-declared value survives", mapOf("loud" to "true"), f.store.values(a))
        assertTrue(f.logs.any { it.contains("repeat") && it.contains("defaults") })
        assertTrue(f.logs.any { it.contains("mode") })
        assertTrue(f.logs.any { it.contains("old_key") && it.contains("no longer declared") })
        assertTrue("values never reach the activity log", f.logs.none { it.contains("secret-value") })
    }

    @Test fun reconcileWithNoSchemaRemovesEverythingStored() {
        val f = fixture(a)
        f.store.save(a, 1, mapOf("repeat" to "4"))
        f.config.reconcile(a, null)
        assertFalse(f.store.hasEntry(a))
        assertTrue(f.logs.single().contains("repeat"))
    }

    @Test fun reconcileLeavesUnchangedValuesAloneAndWritesNothing() {
        val f = fixture(a)
        f.store.save(a, 1, mapOf("repeat" to "4", "loud" to "true"))
        val savesBefore = f.store.saves
        f.config.reconcile(a, schema)
        assertEquals(savesBefore, f.store.saves)
        assertEquals(mapOf("repeat" to "4", "loud" to "true"), f.store.values(a))
        assertTrue(f.logs.isEmpty())
    }

    @Test fun reconcileWithNothingStoredDoesNothing() {
        val f = fixture(a)
        f.config.reconcile(a, null)
        f.config.reconcile(a, schema)
        assertEquals(0, f.store.saves)
        assertTrue(f.logs.isEmpty())
    }

    @Test fun removeClearsOnlyThatPluginsConfiguration() {
        val f = fixture(a, b)
        f.config.update(a, mapOf("repeat" to "2")).getOrThrow()
        f.config.update(b, mapOf("repeat" to "3")).getOrThrow()
        f.config.remove(a)
        assertFalse(f.store.hasEntry(a))
        assertEquals("3", f.config.effective(b)["repeat"])
    }
}
