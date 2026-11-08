package com.oneai.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D8 slice 1: the Settings screen no longer offers a Web Research credential, and the Core never wipes
 * a key that was already saved (it stays encrypted in the vault).
 */
class SettingsCapabilityCredentialTest {
    private fun source(relativePath: String): String {
        val candidates = listOf(File("src/main/java/$relativePath"), File("app/src/main/java/$relativePath"))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("Could not locate production source: $relativePath")
    }

    private fun settingsPage(): String {
        val main = source("com/oneai/app/MainActivity.kt")
        val start = main.indexOf("private fun SettingsPage")
        assertTrue("SettingsPage must exist", start >= 0)
        return main.substring(start)
    }

    @Test fun settingsOffersNoWebResearchCredential() {
        val page = settingsPage()
        assertFalse(page.contains("brave_search"))
        assertFalse(page.contains("Brave", ignoreCase = true))
        assertFalse(page.contains("WEB RESEARCH"))
        assertFalse(page.contains("Web Research"))
        assertFalse(page.contains("saveNamed"))
    }

    @Test fun theAiProviderKeyControlsRemain() {
        val page = settingsPage()
        assertTrue(page.contains("AI PROVIDER"))
        assertTrue(page.contains("Remove AI provider key?"))
        // Slice S1 (provider profiles): the key status still comes from a stored read result, now per profile.
        assertTrue(page.contains("profiles.keyResult("))
    }

    @Test fun nothingInMainActivityWipesTheSavedSearchKey() {
        assertFalse(source("com/oneai/app/MainActivity.kt").contains("brave_search"))
    }

    @Test fun theVaultStillSupportsNamedSecretsSoASavedKeyIsNotLost() {
        val vault = source("com/oneai/app/security/SecretVault.kt")
        assertTrue(vault.contains("fun saveNamed"))
        assertTrue(vault.contains("fun readNamed"))
    }
}
