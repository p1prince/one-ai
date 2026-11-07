package com.oneai.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSettingsTest {
    // ---- helpers -------------------------------------------------------------------------------

    private fun toggle(key: String = "loud", default: String? = null, required: Boolean = false) =
        PluginSettingDeclaration(key, PluginSettingType.TOGGLE, " Loud ", default = default, required = required)

    private fun text(key: String = "prefix", maxLength: Int? = null, default: String? = null, required: Boolean = false) =
        PluginSettingDeclaration(key, PluginSettingType.TEXT, "Prefix", default = default, maxLength = maxLength, required = required)

    private fun number(key: String = "repeat", min: Int? = 1, max: Int? = 5, default: String? = null, required: Boolean = false) =
        PluginSettingDeclaration(key, PluginSettingType.NUMBER, "Repeat", default = default, min = min, max = max, required = required)

    private fun choice(
        key: String = "mode",
        values: List<String> = listOf("plain", "fancy"),
        default: String? = null,
        required: Boolean = false
    ) = PluginSettingDeclaration(
        key, PluginSettingType.CHOICE, "Mode", default = default, required = required,
        options = values.map { PluginSettingOption(it, it.replaceFirstChar { c -> c.uppercase() }) }
    )

    private fun schema(vararg settings: PluginSettingDeclaration, version: Int = 1) = PluginSettingsSchema(version, settings.toList())

    private fun validSchema(vararg settings: PluginSettingDeclaration) =
        PluginSettingsValidator.validateSchema(schema(*settings)).getOrThrow()

    private fun schemaFails(s: PluginSettingsSchema) = assertTrue(PluginSettingsValidator.validateSchema(s).isFailure)

    private fun node(name: String, attributes: Map<String, String> = emptyMap(), vararg children: XmlNode) =
        XmlNode(name, attributes, children.toList())

    private fun settingNode(key: String, type: String, vararg extra: Pair<String, String>, options: List<XmlNode> = emptyList()) =
        XmlNode("setting", mapOf("key" to key, "type" to type, "title" to "Title $key") + extra.toMap(), options)

    private fun manifestRoot(minCoreApi: Int, vararg extraChildren: XmlNode) = XmlNode(
        "one-ai-plugin",
        mapOf("manifestVersion" to "2", "minCoreApi" to "$minCoreApi", "targetCoreApi" to "$minCoreApi"),
        listOf(
            XmlNode("info", mapOf("name" to "Notes")),
            XmlNode("capability", mapOf("name" to "files"))
        ) + extraChildren.toList()
    )

    private fun parse(root: XmlNode) = ExternalManifestParser.parse(root, "dev.example.notes", "1.0.0", 1)

    private val fullSettingsNode = node(
        "settings", mapOf("version" to "1"),
        settingNode("greeting_prefix", "text", "maxLength" to "40", "default" to ""),
        settingNode("loud", "toggle", "default" to "false"),
        settingNode("repeat", "number", "min" to "1", "max" to "5", "default" to "1"),
        settingNode(
            "mode", "choice", "default" to "plain", "required" to "true",
            options = listOf(
                XmlNode("option", mapOf("value" to "plain", "label" to "Plain")),
                XmlNode("option", mapOf("value" to "fancy", "label" to "Fancy"))
            )
        )
    )

    // ---- manifest parsing and API level --------------------------------------------------------

    @Test fun parsesAllFourSettingTypesFromManifest() {
        val manifest = parse(manifestRoot(3, fullSettingsNode)).getOrThrow()
        val settings = manifest.settings!!.settings
        assertEquals(listOf("greeting_prefix", "loud", "repeat", "mode"), settings.map { it.key })
        assertEquals(
            listOf(PluginSettingType.TEXT, PluginSettingType.TOGGLE, PluginSettingType.NUMBER, PluginSettingType.CHOICE),
            settings.map { it.type }
        )
        assertEquals(40, settings[0].maxLength)
        assertEquals(1, settings[2].min)
        assertEquals(5, settings[2].max)
        assertEquals(listOf("plain", "fancy"), settings[3].options.map { it.value })
        assertTrue(settings[3].required)
        assertEquals("plain", settings[3].default)
    }

    @Test fun manifestWithoutSettingsHasNullSettings() {
        assertNull(parse(manifestRoot(1)).getOrThrow().settings)
        assertNull(parse(manifestRoot(2)).getOrThrow().settings)
    }

    @Test fun settingsRequireApiLevelThree() {
        val result = parse(manifestRoot(2, fullSettingsNode))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("minCoreApi"))
    }

    @Test fun settingsPluginsNeedHostApiLevelThree() {
        val manifest = parse(manifestRoot(3, fullSettingsNode)).getOrThrow()
        assertFalse(PluginCompatibility.check(manifest, hostLevel = 2).usable)
        assertTrue(PluginCompatibility.check(manifest, hostLevel = 3).usable)
    }

    @Test fun rejectsMoreThanOneSettingsElement() {
        assertTrue(parse(manifestRoot(3, fullSettingsNode, fullSettingsNode)).isFailure)
    }

    @Test fun rejectsMissingOrUnsupportedSettingsVersion() {
        val noVersion = node("settings", emptyMap(), settingNode("loud", "toggle"))
        assertTrue(parse(manifestRoot(3, noVersion)).isFailure)
        val futureVersion = node("settings", mapOf("version" to "2"), settingNode("loud", "toggle"))
        assertTrue(parse(manifestRoot(3, futureVersion)).isFailure)
    }

    @Test fun rejectsUnsupportedTypeAndNonNumericBounds() {
        assertTrue(parse(manifestRoot(3, node("settings", mapOf("version" to "1"), settingNode("a", "password")))).isFailure)
        assertTrue(parse(manifestRoot(3, node("settings", mapOf("version" to "1"), settingNode("a", "number", "min" to "abc")))).isFailure)
    }

    @Test fun rejectsOversizedSettingsSubtreeAndAttributes() {
        val many = node("settings", mapOf("version" to "1"), *Array(30) { settingNode("s$it", "toggle") })
        assertTrue(parse(manifestRoot(3, many)).isFailure)
        val longAttribute = node("settings", mapOf("version" to "1"), settingNode("a", "text", "description" to "x".repeat(1_001)))
        assertTrue(parse(manifestRoot(3, longAttribute)).isFailure)
    }

    @Test fun ignoresUnknownSettingsAttributesAndElements() {
        val withUnknown = node(
            "settings", mapOf("version" to "1", "futureThing" to "x"),
            settingNode("loud", "toggle", "futureAttr" to "y"),
            node("futureElement")
        )
        assertEquals(listOf("loud"), parse(manifestRoot(3, withUnknown)).getOrThrow().settings!!.settings.map { it.key })
    }

    // ---- schema validation ---------------------------------------------------------------------

    @Test fun normalisesDeclarations() {
        val result = validSchema(toggle(), text(), number(), choice(default = " fancy "))
        val byKey = result.settings.associateBy { it.key }
        assertEquals("Loud", byKey.getValue("loud").title)
        assertEquals("false", byKey.getValue("loud").default)
        assertEquals(PluginSettingsValidator.MAX_TEXT, byKey.getValue("prefix").maxLength)
        assertEquals("fancy", byKey.getValue("mode").default)
    }

    @Test fun numberDefaultsToFullIntRangeWhenUnbounded() {
        val declared = validSchema(number(min = null, max = null)).settings.single()
        assertEquals(Int.MIN_VALUE, declared.min)
        assertEquals(Int.MAX_VALUE, declared.max)
    }

    @Test fun rejectsBadSchemaShape() {
        schemaFails(schema())
        schemaFails(schema(toggle(), version = 2))
        schemaFails(schema(toggle("loud"), toggle("loud")))
        schemaFails(schema(*Array(PluginSettingsValidator.MAX_SETTINGS + 1) { toggle("s$it") }))
    }

    @Test fun rejectsBadKeysAndTitles() {
        schemaFails(schema(toggle("Loud")))
        schemaFails(schema(toggle("1loud")))
        schemaFails(schema(toggle("lou-d")))
        schemaFails(schema(toggle("a".repeat(41))))
        schemaFails(schema(PluginSettingDeclaration("loud", PluginSettingType.TOGGLE, "  ")))
        schemaFails(schema(PluginSettingDeclaration("loud", PluginSettingType.TOGGLE, "t".repeat(61))))
        schemaFails(schema(PluginSettingDeclaration("loud", PluginSettingType.TOGGLE, "Loud", description = "d".repeat(201))))
    }

    @Test fun rejectsBadDefaults() {
        schemaFails(schema(toggle(default = "maybe")))
        schemaFails(schema(number(default = "9")))
        schemaFails(schema(number(default = "x")))
        schemaFails(schema(choice(default = "unknown")))
        schemaFails(schema(text(maxLength = 3, default = "toolong")))
    }

    @Test fun rejectsBadTextBounds() {
        schemaFails(schema(text(maxLength = 0)))
        schemaFails(schema(text(maxLength = PluginSettingsValidator.MAX_TEXT + 1)))
    }

    @Test fun rejectsTotalTextCapacityAboveLimit() {
        schemaFails(schema(text("a", 500), text("b", 500), text("c", 500), text("d", 500), text("e", 1)))
        validSchema(text("a", 500), text("b", 500), text("c", 500), text("d", 500))
    }

    @Test fun rejectsInvertedNumberRange() {
        schemaFails(schema(number(min = 5, max = 1)))
    }

    @Test fun rejectsBadChoiceOptions() {
        schemaFails(schema(choice(values = listOf("only"))))
        schemaFails(schema(choice(values = listOf("a", "a"))))
        schemaFails(schema(choice(values = listOf("a", "B"))))
        schemaFails(schema(choice(values = (1..17).map { "o$it" })))
        schemaFails(schema(
            PluginSettingDeclaration(
                "mode", PluginSettingType.CHOICE, "Mode",
                options = listOf(PluginSettingOption("a", ""), PluginSettingOption("b", "B"))
            )
        ))
    }

    @Test fun rejectsTotalOptionsAboveLimit() {
        // 7 choices x 14 options = 98 > 96
        val choices = (0 until 7).map { choice("c$it", values = (1..14).map { n -> "o$n" }) }
        schemaFails(schema(*choices.toTypedArray()))
    }

    // ---- value validation ----------------------------------------------------------------------

    @Test fun acceptsAndNormalisesValidValues() {
        val s = validSchema(toggle(), text(maxLength = 10), number(), choice())
        val result = PluginSettingsValidator.validateValues(
            s, mapOf("loud" to " TRUE ", "prefix" to "hi there", "repeat" to " 3 ", "mode" to " fancy ")
        ).getOrThrow()
        assertEquals(mapOf("loud" to "true", "prefix" to "hi there", "repeat" to "3", "mode" to "fancy"), result)
    }

    @Test fun rejectsInvalidValues() {
        val s = validSchema(toggle(), text(maxLength = 5), number(), choice())
        fun fails(key: String, value: String) =
            assertTrue("$key=$value", PluginSettingsValidator.validateValues(s, mapOf(key to value)).isFailure)
        fails("loud", "yes")
        fails("prefix", "toolong")
        fails("prefix", "a\nb")
        fails("prefix", "a\u0000b")
        fails("repeat", "0")
        fails("repeat", "6")
        fails("repeat", "2.5")
        fails("repeat", "99999999999")
        fails("mode", "unknown")
        fails("nonexistent", "x")
    }

    @Test fun failureMessagesNeverEchoTheValue() {
        val s = validSchema(number(), text(maxLength = 3))
        val secret = "s3cr3t-value"
        val textFailure = PluginSettingsValidator.validateValues(s, mapOf("prefix" to secret)).exceptionOrNull()?.message.orEmpty()
        val numberFailure = PluginSettingsValidator.validateValues(s, mapOf("repeat" to secret)).exceptionOrNull()?.message.orEmpty()
        assertFalse(textFailure.contains(secret))
        assertFalse(numberFailure.contains(secret))
        assertTrue(textFailure.isNotEmpty() && numberFailure.isNotEmpty())
    }

    @Test fun emptySnapshotIsValid() {
        assertEquals(emptyMap<String, String>(), PluginSettingsValidator.validateValues(validSchema(toggle()), emptyMap()).getOrThrow())
    }

    @Test fun worstCaseSnapshotFitsTheSizeCap() {
        fun key(prefix: String, i: Int) = (prefix + i.toString().padStart(2, '0')).padEnd(40, 'a')
        val texts = (0 until 4).map { text(key("t", it), maxLength = 500) }
        val a = "a".repeat(40)
        val b = "b".repeat(40)
        val choices = (0 until 20).map { i ->
            PluginSettingDeclaration(
                key("c", i), PluginSettingType.CHOICE, "Choice $i",
                options = listOf(PluginSettingOption(a, "A"), PluginSettingOption(b, "B"))
            )
        }
        val s = validSchema(*(texts + choices).toTypedArray())
        // U+20AC (euro sign) is 3 bytes in UTF-8: the worst case per character.
        val values = texts.associate { it.key to "€".repeat(500) } + choices.associate { it.key to a }
        val accepted = PluginSettingsValidator.validateValues(s, values).getOrThrow()
        assertEquals(24, accepted.size)
        val bytes = accepted.entries.sumOf { it.key.toByteArray(Charsets.UTF_8).size + it.value.toByteArray(Charsets.UTF_8).size }
        assertTrue("$bytes bytes", bytes <= PluginSettingsValidator.MAX_SNAPSHOT_BYTES)
    }

    // ---- effective values and required settings ------------------------------------------------

    @Test fun effectiveValuesUseStoredThenDefault() {
        val s = validSchema(toggle(default = "true"), number(default = "2"), text(), choice(default = "plain"))
        val effective = PluginSettingsValidator.effectiveValues(s, mapOf("repeat" to "4", "mode" to "fancy"))
        assertEquals(mapOf("loud" to "true", "repeat" to "4", "mode" to "fancy"), effective)
    }

    @Test fun effectiveValuesIgnoreInvalidStoredData() {
        val s = validSchema(number(default = "2"), choice(default = "plain"))
        val effective = PluginSettingsValidator.effectiveValues(s, mapOf("repeat" to "banana", "mode" to "gone", "stale" to "x"))
        assertEquals(mapOf("repeat" to "2", "mode" to "plain"), effective)
    }

    @Test fun settingsWithoutValueOrDefaultAreAbsent() {
        val s = validSchema(text(), number())
        assertEquals(emptyMap<String, String>(), PluginSettingsValidator.effectiveValues(s, emptyMap()))
    }

    @Test fun missingRequiredReportsOnlyUnsatisfiedRequiredSettings() {
        val s = validSchema(
            text("name", required = true),
            number("count", required = true, default = "1"),
            choice("mode", required = true),
            toggle("optional")
        )
        assertEquals(listOf("name", "mode"), PluginSettingsValidator.missingRequired(s, emptyMap()))
        assertEquals(listOf("mode"), PluginSettingsValidator.missingRequired(s, mapOf("name" to "x")))
        assertEquals(emptyList<String>(), PluginSettingsValidator.missingRequired(s, mapOf("name" to "x", "mode" to "plain")))
        // an invalid stored value does not satisfy a required setting that has no default
        assertEquals(listOf("mode"), PluginSettingsValidator.missingRequired(s, mapOf("name" to "x", "mode" to "gone")))
    }

    // ---- existing manifest behaviour is unchanged ----------------------------------------------

    @Test fun updateReviewIgnoresSettingsChanges() {
        val base = parse(manifestRoot(3, node("settings", mapOf("version" to "1"), settingNode("loud", "toggle")))).getOrThrow()
        val changed = parse(manifestRoot(3, fullSettingsNode)).getOrThrow()
        assertFalse(PluginUpdateReview.review(base, changed).consentRequired)
    }
}
