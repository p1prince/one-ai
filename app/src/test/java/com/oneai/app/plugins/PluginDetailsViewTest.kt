package com.oneai.app.plugins

import com.oneai.app.core.ActivityEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 9.8: the read-only Details view of a trusted plugin. Pure JVM; builds text from a trust entry, never changes state. */
class PluginDetailsViewTest {
    private val cert = "ab".repeat(32)

    private fun manifest(
        capabilities: List<String> = listOf("commands", "files"),
        commands: List<ExternalCommandDeclaration> = listOf(
            ExternalCommandDeclaration("add_note", "Add note", "Saves a note", "files"),
            ExternalCommandDeclaration("list_notes", "List notes", "Lists notes", "commands")
        ),
        bubble: ExternalBubbleDeclaration? = ExternalBubbleDeclaration("notes", "Notes"),
        dependencies: List<String> = emptyList(),
        minCoreApi: Int = 2,
        targetCoreApi: Int = 3,
        name: String = "Example Notes"
    ) = ExternalPluginManifest(
        packageName = "com.example.notes", versionName = "1.2.0", versionCode = 12, name = name,
        description = "Quick notes", minCoreApi = minCoreApi, targetCoreApi = targetCoreApi,
        capabilities = capabilities, commands = commands, bubble = bubble, dependencies = dependencies
    )

    private fun details(
        m: ExternalPluginManifest = manifest(),
        status: ExternalStatus? = ExternalStatus.Ready,
        enabled: Boolean = true,
        bubbleAccess: Boolean = false,
        needs: List<String> = emptyList(),
        enabledIds: Set<String> = emptySet(),
        hostLevel: Int = PluginApi.LEVEL
    ) = PluginDetailsView.build(TrustEntry(m.packageName, cert, m), status, enabled, bubbleAccess, needs, enabledIds, hostLevel)

    private fun PluginDetails.section(heading: String) = sections.first { it.heading == heading }.lines
    private fun PluginDetails.all() = sections.flatMap { it.lines }

    @Test fun showsIdentityAndThePinnedCertificate() {
        val d = details()
        assertEquals("Example Notes", d.title)
        assertTrue(d.section("Identity").contains("Package: com.example.notes"))
        assertTrue(d.section("Identity").contains("Version: 1.2.0 (code 12)"))
        assertTrue(d.section("Trusted certificate").contains(CertificateFingerprint.display(cert)))
    }

    @Test fun sectionsAreInAFixedOrder() {
        assertEquals(
            listOf("Identity", "Trusted certificate", "Status", "Compatibility", "Depends on", "Access it can ask for", "Commands", "Floating Bubble", "Recent activity"),
            details().sections.map { it.heading }
        )
    }

    @Test fun statusDistinguishesEveryLifecycleState() {
        assertTrue(details(enabled = false).section("Status").first().startsWith("Disabled"))
        assertTrue(details(enabled = true).section("Status").contains("Trusted and ready."))
        assertFalse("a disabled plugin is not called ready", details(enabled = false).section("Status").contains("Trusted and ready."))
        assertTrue(details(status = ExternalStatus.SignatureChanged).section("Status").any { it.startsWith("Blocked") })
        assertTrue(details(status = ExternalStatus.NotInstalled).section("Status").any { it.contains("not installed") })
        val pending = details(status = ExternalStatus.UpdatePending(listOf("adds capability: files"))).section("Status")
        assertTrue(pending.any { it.contains("off until you review") })
        assertTrue(pending.contains("- adds capability: files"))
    }

    @Test fun missingRequiredSettingsAreNamedOnlyForAnEnabledPlugin() {
        assertTrue(details(enabled = true, needs = listOf("Mode")).section("Status").any { it.contains("Needs configuration") && it.contains("Mode") })
        assertFalse(details(enabled = false, needs = listOf("Mode")).all().any { it.contains("Needs configuration") })
    }

    @Test fun compatibilityShowsRequiredLevelAndTheHostVerdict() {
        assertTrue(details().section("Compatibility").contains("Compatible with this ONE AI."))
        val tooNew = details(m = manifest(minCoreApi = 4, targetCoreApi = 4), hostLevel = 3).section("Compatibility")
        assertTrue(tooNew.first().contains("Needs plugin API 4"))
        assertTrue(tooNew.last().contains("Update ONE AI"))
    }

    @Test fun dependenciesReportEachOneAsEnabledOrNot() {
        assertEquals(listOf("No other plugins."), details().section("Depends on"))
        val m = manifest(dependencies = listOf("com.example.core", "com.example.extra"))
        val lines = details(m = m, enabledIds = setOf("com.example.core")).section("Depends on")
        assertEquals("com.example.core: installed and enabled", lines[0])
        assertEquals("com.example.extra: not installed and enabled", lines[1])
    }

    @Test fun capabilitiesFollowTheGatewayApprovalPolicy() {
        val lines = details().section("Access it can ask for")
        assertEquals("commands: no extra prompt", lines[0])
        assertEquals("files: asks you each time, and each approval is single-use", lines[1])
        assertEquals(listOf("None declared."), details(m = manifest(capabilities = emptyList(), commands = emptyList())).section("Access it can ask for"))
    }

    @Test fun commandsAreListedWithTheCapabilityTheyUse() {
        assertEquals(
            listOf("add_note (Add note), uses files", "list_notes (List notes), uses commands"),
            details().section("Commands")
        )
        assertEquals(listOf("None."), details(m = manifest(commands = emptyList())).section("Commands"))
    }

    @Test fun commandListIsBoundedAndSaysHowManyAreHidden() {
        val many = (1..60).map { ExternalCommandDeclaration("cmd$it", "Command $it", "d", "commands") }
        val lines = details(m = manifest(commands = many)).section("Commands")
        assertEquals(PluginDetailsView.MAX_COMMANDS_SHOWN + 1, lines.size)
        assertEquals("and 10 more", lines.last())
    }

    @Test fun bubbleAccessIsSeparateFromEnabled() {
        assertTrue(details(bubbleAccess = false, enabled = true).section("Floating Bubble").last().contains("Not allowed in the Bubble"))
        assertEquals("You allowed it in the Bubble.", details(bubbleAccess = true, enabled = true).section("Floating Bubble").last())
        assertTrue(details(bubbleAccess = true, enabled = false).section("Floating Bubble").last().contains("disabled"))
        assertEquals(listOf("It does not offer a Bubble view."), details(m = manifest(bubble = null)).section("Floating Bubble"))
    }

    @Test fun pluginSuppliedTextIsCleanedOfHiddenAndControlCharacters() {
        val sneaky = "Notes\u202Eevil\u200B\nline"
        val d = details(m = manifest(name = sneaky))
        assertFalse(d.title.any { it == '\u202E' || it == '\u200B' || it == '\n' })
        assertTrue(d.all().none { line -> line.any { it == '\u202E' || it == '\u200B' } })
    }

    @Test fun buildingDetailsDoesNotChangeTheEntry() {
        val m = manifest()
        val entry = TrustEntry(m.packageName, cert, m)
        PluginDetailsView.build(entry, ExternalStatus.Ready, true, true, emptyList(), emptySet())
        assertEquals(TrustEntry(m.packageName, cert, m), entry)
    }

    @Test fun recentActivityShowsTimestampedEntriesAndAFallbackWhenEmpty() {
        assertEquals(listOf("Nothing recorded for this plugin."), details().section("Recent activity"))
        val shown = PluginDetailsView.build(
            TrustEntry("com.example.notes", cert, manifest()), ExternalStatus.Ready, true, false, emptyList(), emptySet(),
            recentActivity = listOf(ActivityEntry(5_000, "[com.example.notes] Failed (E_TIMEOUT): add_note"), ActivityEntry(0, "[com.example.notes] older")),
            formatTime = { "t$it" }
        ).section("Recent activity")
        assertEquals(listOf("t5000: [com.example.notes] Failed (E_TIMEOUT): add_note", "[com.example.notes] older"), shown)
    }

    @Test fun recentActivityTextIsCleanedAndBoundedToTheDefaultLimit() {
        val many = (1..25).map { ActivityEntry(it.toLong(), "[com.example.notes] event $it\u202E") }
        val lines = PluginDetailsView.build(
            TrustEntry("com.example.notes", cert, manifest()), ExternalStatus.Ready, true, false, emptyList(), emptySet(),
            recentActivity = many, formatTime = { "t" }
        ).section("Recent activity")
        assertEquals(PluginActivityView.DEFAULT_LIMIT, lines.size)
        assertTrue(lines.none { l -> l.any { it == '\u202E' } })
    }
}
