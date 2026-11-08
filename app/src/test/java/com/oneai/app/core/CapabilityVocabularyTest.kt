package com.oneai.app.core

import com.oneai.app.plugins.PluginManifestValidator
import com.oneai.app.security.ActionRequest
import com.oneai.app.security.CapabilityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Decision D10 / D-Q: the capability vocabulary is one closed list. The manifest validator and the gateway
 * policy derive from it, and the two documents that tell plugin authors about it must list the same names.
 */
class CapabilityVocabularyTest {
    private fun repoFile(relative: String): File =
        listOf(File("../$relative"), File(relative), File("../../$relative")).firstOrNull { it.isFile }
            ?: error("Could not locate $relative")

    private fun mainSource(relative: String): String {
        val candidates = listOf(File("src/main/java/com/oneai/app/$relative"), File("app/src/main/java/com/oneai/app/$relative"))
        return (candidates.firstOrNull { it.isFile } ?: error("Could not locate $relative")).readText()
    }

    private val identifier = Regex("`([a-z][a-z_]*)`")

    @Test fun theVocabularyHasTheTenDocumentedIdentifiersWithNoDuplicates() {
        assertEquals(10, CapabilityVocabulary.all.size)
        assertTrue(CapabilityVocabulary.all.all { it == it.trim() && it == it.lowercase() })
    }

    @Test fun theApprovalSetIsASubsetAndCommandsAndAudioNeverAsk() {
        assertTrue(CapabilityVocabulary.all.containsAll(CapabilityVocabulary.approvalRequired))
        assertFalse(CapabilityVocabulary.approvalRequired.contains("commands"))
        assertFalse(CapabilityVocabulary.approvalRequired.contains("audio"))
        assertEquals(CapabilityVocabulary.all - setOf("commands", "audio"), CapabilityVocabulary.approvalRequired)
    }

    @Test fun theManifestValidatorUsesTheSameList() {
        assertEquals(CapabilityVocabulary.all, PluginManifestValidator.supportedCapabilities)
    }

    @Test fun thePolicyAgreesWithTheVocabularyForEveryIdentifier() {
        for (capability in CapabilityVocabulary.all) {
            assertEquals(capability, capability in CapabilityVocabulary.approvalRequired, CapabilityPolicy.requiresApproval(capability))
            val request = ActionRequest("com.example.notes", "act", capability, "Do something")
            val verdict = CapabilityPolicy.evaluate(request, pluginEnabled = true, declaredCapabilities = setOf(capability))
            assertTrue("$capability must be recognized", verdict.allowed)
        }
    }

    @Test fun anIdentifierOutsideTheVocabularyIsRefusedByThePolicy() {
        val request = ActionRequest("com.example.notes", "act", "teleport", "Do something")
        val verdict = CapabilityPolicy.evaluate(request, pluginEnabled = true, declaredCapabilities = setOf("teleport"))
        assertFalse(verdict.allowed)
        assertFalse(CapabilityPolicy.requiresApproval("teleport"))
    }

    @Test fun thePolicyAndValidatorDoNotKeepTheirOwnCopies() {
        val policy = mainSource("security/ActionSecurity.kt")
        val manifest = mainSource("plugins/PluginManifest.kt")
        assertTrue(policy.contains("CapabilityVocabulary.all"))
        assertTrue(policy.contains("CapabilityVocabulary.approvalRequired"))
        assertTrue(manifest.contains("CapabilityVocabulary.all"))
        assertFalse("the policy must not list identifiers itself", policy.contains("\"screen_capture\", \"microphone\""))
        assertFalse("the validator must not list identifiers itself", manifest.contains("\"screen_capture\", \"microphone\""))
    }

    @Test fun theSdkNoteListsExactlyTheVocabularyOnceEach() {
        val section = repoFile("PLUGIN_SDK.md").readText()
            .substringAfter("## Supported capability identifiers").substringBefore("\n## ")
        val listed = section.lines().filter { it.startsWith("- `") }.map { identifier.find(it)!!.groupValues[1] }
        assertEquals("no identifier may be listed twice", listed.size, listed.toSet().size)
        assertEquals(CapabilityVocabulary.all, listed.toSet())
        assertTrue(section.contains("closed and versioned"))
    }

    @Test fun theDeveloperGuideListsExactlyTheVocabulary() {
        val section = repoFile("docs/PLUGIN_DEVELOPER_GUIDE.md").readText()
            .substringAfter("### Capabilities and approval").substringBefore("\n## ")
        val line = section.lines().first { it.startsWith("Supported:") }
        val listed = identifier.findAll(line).map { it.groupValues[1] }.toList()
        assertEquals(listed.size, listed.toSet().size)
        assertEquals(CapabilityVocabulary.all, listed.toSet())
        assertTrue(section.contains("closed and versioned"))
    }
}
