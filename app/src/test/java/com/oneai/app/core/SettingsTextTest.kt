package com.oneai.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsTextTest {
    @Test fun oneIsSingularAndOtherNumbersArePlural() {
        assertEquals("1 enabled plugin \u2022 0 enabled commands", SettingsText.enabledSummary(1, 0))
        assertEquals("0 enabled plugins \u2022 1 enabled command", SettingsText.enabledSummary(0, 1))
        assertEquals("2 enabled plugins \u2022 3 enabled commands", SettingsText.enabledSummary(2, 3))
    }

    @Test fun settingsUsesTheHelpersInsteadOfFixedPlurals() {
        val dir = listOf("src/main/java/com/oneai/app", "app/src/main/java/com/oneai/app").map(::File).first { it.isDirectory }
        val source = File(dir, "MainActivity.kt").readText()
        assertTrue(source.contains("SettingsText.enabledSummary"))
        assertTrue(!source.contains("} enabled plugins"))
        assertTrue(source.contains("BubbleUiPolicy.serviceLabel"))
    }
}
