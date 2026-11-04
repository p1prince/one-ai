package com.oneai.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPluginManifestTest {
    private fun manifest(
        packageName: String = "dev.example.notes",
        versionName: String = "1.0.0",
        versionCode: Long = 1,
        capabilities: List<String> = listOf("files"),
        commands: List<ExternalCommandDeclaration> = listOf(ExternalCommandDeclaration("add_note", " Add note ", "Saves a note", "files")),
        bubble: ExternalBubbleDeclaration? = ExternalBubbleDeclaration("notes", "Notes"),
        dependencies: List<String> = emptyList(),
        minCoreApi: Int = 1,
        targetCoreApi: Int = 1,
        manifestVersion: Int = PluginApi.MANIFEST_VERSION
    ) = ExternalPluginManifest(
        manifestVersion = manifestVersion, packageName = packageName, versionName = versionName, versionCode = versionCode,
        name = " Notes ", description = " Save notes ", minCoreApi = minCoreApi, targetCoreApi = targetCoreApi,
        capabilities = capabilities, commands = commands, bubble = bubble, dependencies = dependencies
    )

    private fun fails(m: ExternalPluginManifest) = assertTrue(ExternalManifestValidator.validate(m).isFailure)

    @Test fun normalisesValidManifest() {
        val result = ExternalManifestValidator.validate(manifest()).getOrThrow()
        assertEquals("Notes", result.name)
        assertEquals("Add note", result.commands.single().title)
        assertEquals("dev.example.notes", result.id)
    }

    @Test fun rejectsBadIdentityAndVersions() {
        fails(manifest(packageName = "notes"))
        fails(manifest(packageName = "Dev.Example.Notes"))
        fails(manifest(versionName = "latest"))
        fails(manifest(versionCode = 0))
        fails(manifest(manifestVersion = 1))
    }

    @Test fun rejectsInvalidApiLevels() {
        fails(manifest(minCoreApi = 0))
        fails(manifest(minCoreApi = 3, targetCoreApi = 2))
    }

    @Test fun commandsMustUseADeclaredSupportedCapability() {
        fails(manifest(capabilities = listOf("execute_arbitrary_code")))
        fails(manifest(capabilities = emptyList()))
        fails(manifest(capabilities = listOf("files"), commands = listOf(ExternalCommandDeclaration("go", "Go", "", "internet"))))
    }

    @Test fun rejectsBadOrDuplicateCommandNames() {
        fails(manifest(commands = listOf(ExternalCommandDeclaration("Bad Name", "T", "", "files"))))
        fails(manifest(commands = listOf(
            ExternalCommandDeclaration("a", "A", "", "files"), ExternalCommandDeclaration("a", "A2", "", "files")
        )))
    }

    @Test fun enforcesCommandCountBound() {
        val many = (1..51).map { ExternalCommandDeclaration("cmd_$it", "C$it", "", "files") }
        fails(manifest(commands = many))
        assertTrue(ExternalManifestValidator.validate(manifest(commands = many.take(50))).isSuccess)
    }

    @Test fun bubbleDeclarationIsOptionalAndValidated() {
        assertNull(ExternalManifestValidator.validate(manifest(bubble = null)).getOrThrow().bubble)
        fails(manifest(bubble = ExternalBubbleDeclaration("Bad Service", "T")))
        fails(manifest(bubble = ExternalBubbleDeclaration("notes", " ")))
    }

    @Test fun dependenciesAreValidated() {
        fails(manifest(dependencies = listOf("dev.example.notes")))
        fails(manifest(dependencies = listOf("dev.other.a", "dev.other.a")))
        fails(manifest(dependencies = listOf("nodots")))
    }

    @Test fun bridgesToTheRecordManifestUsedByTheRuntime() {
        val bridged = ExternalManifestValidator.validate(manifest()).getOrThrow().toRecordManifest()
        assertTrue(PluginManifestValidator.validate(bridged).isSuccess)
        assertEquals("dev.example.notes", bridged.id)
    }

    @Test fun compatibilityFollowsApiLevels() {
        val ok = manifest()
        assertEquals(Compatibility.Compatible, PluginCompatibility.check(ok))
        val tooNew = PluginCompatibility.check(manifest(minCoreApi = 2, targetCoreApi = 2))
        assertTrue(tooNew is Compatibility.Incompatible)
        assertFalse(tooNew.usable)
        val old = PluginCompatibility.check(manifest(targetCoreApi = 1), hostLevel = 5, minSupportedTarget = 2)
        assertTrue(old is Compatibility.CompatibleWithWarning)
        assertTrue(old.usable)
    }

    @Test fun missingDependenciesListsOnlyUnavailableOnes() {
        val m = manifest(dependencies = listOf("dev.a.one", "dev.b.two"))
        assertEquals(listOf("dev.b.two"), PluginCompatibility.missingDependencies(m, setOf("dev.a.one")))
    }

    @Test fun updateReviewRequiresConsentOnlyWhenPowerGrows() {
        val old = manifest()
        assertFalse(PluginUpdateReview.review(old, old.copy(versionCode = 2, versionName = "1.0.1")).consentRequired)
        val moreCaps = old.copy(capabilities = listOf("files", "internet"))
        assertTrue(PluginUpdateReview.review(old, moreCaps).consentRequired)
        val moreCommands = old.copy(commands = old.commands + ExternalCommandDeclaration("find_note", "Find", "", "files"))
        assertTrue(PluginUpdateReview.review(old, moreCommands).consentRequired)
        val gainsBubble = manifest(bubble = null).let { PluginUpdateReview.review(it, it.copy(bubble = ExternalBubbleDeclaration("n", "N"))) }
        assertTrue(gainsBubble.consentRequired)
        val fewer = old.copy(commands = emptyList())
        assertFalse(PluginUpdateReview.review(old, fewer).consentRequired)
    }

    private fun node(name: String, vararg attrs: Pair<String, String>, children: List<XmlNode> = emptyList()) =
        XmlNode(name, attrs.toMap(), children)

    private fun root(vararg children: XmlNode, extra: Map<String, String> = emptyMap()) =
        XmlNode("one-ai-plugin", mapOf("manifestVersion" to "2", "minCoreApi" to "1", "targetCoreApi" to "1") + extra, children.toList())

    @Test fun parsesDeclarativeTreeAndTakesIdentityFromThePackage() {
        val parsed = ExternalManifestParser.parse(
            root(
                node("info", "name" to "Notes", "description" to "Quick notes"),
                node("capability", "name" to "files"),
                node("command", "name" to "add_note", "title" to "Add note", "description" to "Saves", "capability" to "files"),
                node("bubble", "service" to "notes", "title" to "Notes"),
                node("depends", "plugin" to "dev.other.base"),
                node("future-element", "x" to "ignored")
            ),
            packageName = "dev.example.notes", versionName = "2.1.0", versionCode = 7
        ).getOrThrow()
        assertEquals("dev.example.notes", parsed.id)
        assertEquals(7L, parsed.versionCode)
        assertEquals(listOf("dev.other.base"), parsed.dependencies)
        assertEquals("notes", parsed.bubble?.service)
    }

    @Test fun parserRejectsMalformedManifests() {
        fun parse(r: XmlNode) = ExternalManifestParser.parse(r, "dev.example.notes", "1.0.0", 1)
        assertTrue(parse(XmlNode("something-else")).isFailure)
        assertTrue(parse(root(node("capability", "name" to "files"))).isFailure) // no <info>
        assertTrue(parse(root(node("info", "name" to "N"))).isFailure) // no capability
        assertTrue(parse(root(node("info", "name" to "N"), node("capability", "name" to "files"),
            node("bubble", "service" to "a", "title" to "A"), node("bubble", "service" to "b", "title" to "B"))).isFailure)
        assertTrue(parse(XmlNode("one-ai-plugin", mapOf("manifestVersion" to "2"), emptyList())).isFailure) // no api levels
        assertTrue(parse(root(node("info", "name" to "x".repeat(2_000)), node("capability", "name" to "files"))).isFailure)
    }

    @Test fun parserBoundsManifestSize() {
        val many = (1..250).map { node("capability", "name" to "files") }
        assertTrue(ExternalManifestParser.parse(root(node("info", "name" to "N")).copy(children = many), "dev.example.notes", "1.0.0", 1).isFailure)
    }
}
