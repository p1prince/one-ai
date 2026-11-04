package com.oneai.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPluginManifestTest {
    private fun manifest(
        id: String = "com.example.notes",
        capabilities: List<String> = listOf("files"),
        commands: List<ExternalCommandDecl> = listOf(ExternalCommandDecl("add_note", "Add note", "Saves a note", "files")),
        bubble: ExternalBubbleDecl? = ExternalBubbleDecl("notes", "Notes"),
        dependencies: List<String> = emptyList(),
        minCoreApi: Int = 1, targetCoreApi: Int = 1, version: Int = 2
    ) = ExternalPluginManifest(version, id, "1.0.0", 1, "Notes", "Quick notes", minCoreApi, targetCoreApi, capabilities, commands, bubble, dependencies)

    private fun fails(m: ExternalPluginManifest) = assertTrue(ExternalManifestValidator.validate(m).isFailure)

    @Test fun aWellFormedManifestIsAcceptedAndNormalised() {
        val ok = ExternalManifestValidator.validate(manifest(capabilities = listOf(" Files ", "files"))).getOrThrow()
        assertEquals(listOf("files"), ok.capabilities)
        assertEquals("com.example.notes", ok.id)
    }

    @Test fun onlyManifestVersionTwoIsAccepted() { fails(manifest(version = 1)); fails(manifest(version = 3)) }

    @Test fun theIdMustBeAValidPackageName() {
        listOf("notes", "Com.Example.Notes", "com..x", "com.example.", "core", "1com.example", "com.example.${"a".repeat(100)}").forEach { fails(manifest(id = it)) }
        assertTrue(ExternalManifestValidator.validate(manifest(id = "dev.example.my_plugin")).isSuccess)
    }

    @Test fun capabilitiesMustBeKnownNonEmptyAndBounded() {
        fails(manifest(capabilities = emptyList(), commands = emptyList()))
        fails(manifest(capabilities = listOf("root_access"), commands = emptyList()))
        fails(manifest(capabilities = List(13) { "files" + it }, commands = emptyList()))
    }

    @Test fun everyCommandMustUseADeclaredCapability() {
        fails(manifest(commands = listOf(ExternalCommandDecl("x", "X", "", "internet"))))
        assertTrue(ExternalManifestValidator.validate(manifest(capabilities = listOf("files", "internet"), commands = listOf(ExternalCommandDecl("x", "X", "", "internet")))).isSuccess)
    }

    @Test fun commandNamesAreValidatedAndUnique() {
        fails(manifest(commands = listOf(ExternalCommandDecl("Bad Name", "X", "", "files"))))
        fails(manifest(commands = listOf(ExternalCommandDecl("a", "X", "", "files"), ExternalCommandDecl("a", "Y", "", "files"))))
        fails(manifest(commands = listOf(ExternalCommandDecl("a", " ", "", "files"))))
        fails(manifest(commands = List(51) { ExternalCommandDecl("c$it", "C", "", "files") }))
    }

    @Test fun bubbleDeclarationIsOptionalAndValidated() {
        assertTrue(ExternalManifestValidator.validate(manifest(bubble = null)).isSuccess)
        fails(manifest(bubble = ExternalBubbleDecl("command_manager", "Hijack")))
        fails(manifest(bubble = ExternalBubbleDecl("Bad Id", "X")))
        fails(manifest(bubble = ExternalBubbleDecl("notes", " ")))
    }

    @Test fun dependenciesAreValidatedAndCannotBeSelfOrDuplicate() {
        fails(manifest(dependencies = listOf("com.example.notes")))
        fails(manifest(dependencies = listOf("com.example.a", "com.example.a")))
        fails(manifest(dependencies = listOf("not a package")))
        assertTrue(ExternalManifestValidator.validate(manifest(dependencies = listOf("com.example.other"))).isSuccess)
    }

    @Test fun apiLevelsMustBeConsistent() { fails(manifest(minCoreApi = 0)); fails(manifest(minCoreApi = 3, targetCoreApi = 2)) }

    @Test fun compatibilityAgainstTheHostLevel() {
        val m = ExternalManifestValidator.validate(manifest()).getOrThrow()
        assertEquals(PluginCompatibility.Compatible, ExternalManifestValidator.compatibility(m))
        val newer = ExternalManifestValidator.validate(manifest(minCoreApi = 2, targetCoreApi = 2)).getOrThrow()
        assertEquals(PluginCompatibility.RequiresNewerCore(2), ExternalManifestValidator.compatibility(newer))
        assertEquals(PluginCompatibility.Compatible, ExternalManifestValidator.compatibility(newer, coreLevel = 2))
    }

    @Test fun anUpdateThatChangesNothingNeedsNoNewConsent() {
        val a = ExternalManifestValidator.validate(manifest()).getOrThrow()
        assertFalse(DeclarationChanges.between(a, a.copy(versionCode = 2, versionName = "1.1")).needsReconsent)
    }

    @Test fun anUpdateThatAddsPowersNeedsNewConsent() {
        val old = ExternalManifestValidator.validate(manifest(bubble = null)).getOrThrow()
        val withCapability = ExternalManifestValidator.validate(manifest(bubble = null, capabilities = listOf("files", "internet"))).getOrThrow()
        assertEquals(setOf("internet"), DeclarationChanges.between(old, withCapability).addedCapabilities)
        val withCommand = ExternalManifestValidator.validate(manifest(bubble = null, commands = listOf(ExternalCommandDecl("add_note", "Add note", "", "files"), ExternalCommandDecl("wipe", "Wipe", "", "files")))).getOrThrow()
        assertEquals(setOf("wipe:files"), DeclarationChanges.between(old, withCommand).addedCommands)
        val withBubble = ExternalManifestValidator.validate(manifest()).getOrThrow()
        assertTrue(DeclarationChanges.between(old, withBubble).bubbleAccessAdded)
        val withDep = ExternalManifestValidator.validate(manifest(bubble = null, dependencies = listOf("com.example.x"))).getOrThrow()
        assertTrue(DeclarationChanges.between(old, withDep).needsReconsent)
    }

    @Test fun removingPowersNeverNeedsConsent() {
        val full = ExternalManifestValidator.validate(manifest()).getOrThrow()
        val reduced = ExternalManifestValidator.validate(manifest(bubble = null, commands = emptyList())).getOrThrow()
        assertFalse(DeclarationChanges.between(full, reduced).needsReconsent)
    }
}
