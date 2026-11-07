package com.oneai.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 9.6d: the pure logic behind the host-rendered Configure screen. JVM only; how the screen looks and
 * behaves on a device is Stage 11.
 */
class PluginConfigureFormTest {
    private val schema: PluginSettingsSchema = PluginSettingsValidator.validateSchema(
        PluginSettingsSchema(settings = listOf(
            PluginSettingDeclaration("loud", PluginSettingType.TOGGLE, "Uppercase replies"),
            PluginSettingDeclaration("prefix", PluginSettingType.TEXT, "Prefix", maxLength = 8),
            PluginSettingDeclaration("repeat", PluginSettingType.NUMBER, "Repeat count", default = "1", min = 1, max = 5),
            PluginSettingDeclaration(
                "mode", PluginSettingType.CHOICE, "Mode", required = true,
                options = listOf(PluginSettingOption("plain", "Plain"), PluginSettingOption("fancy", "Fancy"))
            ),
            PluginSettingDeclaration("owner", PluginSettingType.TEXT, "Owner name", required = true, maxLength = 20)
        ))
    ).getOrThrow()

    @Test fun theInitialDraftUsesEffectiveValuesAndBlanksWhereThereIsNone() {
        val draft = PluginConfigureForm.initialDraft(schema, mapOf("repeat" to "3"))
        assertEquals("false", draft["loud"])
        assertEquals("", draft["prefix"])
        assertEquals("3", draft["repeat"])
        assertEquals("", draft["mode"])
    }

    @Test fun missingRequiredSettingsAreReportedByTitleAndBlockSaving() {
        val draft = PluginConfigureForm.initialDraft(schema, emptyMap())
        val errors = PluginConfigureForm.errors(schema, draft)
        assertEquals(setOf("mode", "owner"), errors.keys)
        assertTrue(errors.getValue("mode").contains("Mode"))
    }

    @Test fun aBlankRequiredTextSettingCountsAsMissingEvenThoughEmptyTextIsOtherwiseValid() {
        val draft = PluginConfigureForm.initialDraft(schema, emptyMap()) + ("mode" to "plain") + ("owner" to "   ")
        assertEquals(setOf("owner"), PluginConfigureForm.errors(schema, draft).keys)
    }

    @Test fun invalidValuesGetFieldLevelMessagesThatNeverEchoTheValue() {
        val draft = PluginConfigureForm.initialDraft(schema, emptyMap()) +
            mapOf("mode" to "plain", "owner" to "Sam", "repeat" to "99", "prefix" to "far-too-long-secret")
        val errors = PluginConfigureForm.errors(schema, draft)
        assertEquals(setOf("repeat", "prefix"), errors.keys)
        assertTrue(errors.values.none { it.contains("far-too-long-secret") || it.contains("99") })
    }

    @Test fun aValidDraftHasNoErrorsAndSavesEverySettingTheUserHasAValueFor() {
        val draft = mapOf("loud" to "true", "prefix" to "", "repeat" to "4", "mode" to "fancy", "owner" to "Sam")
        assertTrue(PluginConfigureForm.errors(schema, draft).isEmpty())
        assertEquals(draft, PluginConfigureForm.valuesToSave(schema, draft))
    }

    @Test fun blankOptionalNumbersAndChoicesAreLeftOutOfASave() {
        val draft = mapOf("loud" to "false", "prefix" to "", "repeat" to "", "mode" to "plain", "owner" to "Sam")
        assertTrue(PluginConfigureForm.errors(schema, draft).isEmpty())
        val toSave = PluginConfigureForm.valuesToSave(schema, draft)
        assertFalse("a blank optional number is not saved", "repeat" in toSave)
        assertEquals("plain", toSave["mode"])
    }

    @Test fun savedValuesAlwaysPassTheHostValidator() {
        val draft = mapOf("loud" to "true", "prefix" to "ab", "repeat" to " 2 ", "mode" to "fancy", "owner" to "Sam")
        assertTrue(PluginSettingsValidator.validateValues(schema, PluginConfigureForm.valuesToSave(schema, draft)).isSuccess)
    }

    @Test fun saveStaysOffUntilTheDraftDiffersFromWhatIsInEffect() {
        val effective = mapOf("repeat" to "1", "mode" to "plain")
        val draft = PluginConfigureForm.initialDraft(schema, effective)
        assertFalse(PluginConfigureForm.hasChanges(schema, effective, draft))
        assertTrue(PluginConfigureForm.hasChanges(schema, effective, draft + ("mode" to "fancy")))
    }

    @Test fun theCardNoteNamesWhatIsMissingAndSaysWhatThatMeansForEnabledAndDisabledPlugins() {
        assertNull(PluginConfigureForm.needsConfigurationNote(emptyList(), enabled = true))
        val disabled = PluginConfigureForm.needsConfigurationNote(listOf("Mode", "Owner name"), enabled = false)!!
        assertTrue(disabled.contains("Mode, Owner name") && disabled.contains("cannot be turned on"))
        val enabled = PluginConfigureForm.needsConfigurationNote(listOf("Mode"), enabled = true)!!
        assertTrue(enabled.contains("will not run"))
    }
}
