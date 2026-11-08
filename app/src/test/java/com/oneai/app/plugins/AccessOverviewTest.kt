package com.oneai.app.plugins

import com.oneai.app.security.CapabilityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Stage 10.1: the read-only Access overview. Pure JVM; builds text from trust entries and never changes state. */
class AccessOverviewTest {
    private val cert = "ab".repeat(32)

    private fun manifest(
        pkg: String = "com.example.notes",
        name: String = "Example Notes",
        capabilities: List<String> = listOf("commands", "files"),
        bubble: ExternalBubbleDeclaration? = ExternalBubbleDeclaration("notes", "Notes")
    ) = ExternalPluginManifest(
        packageName = pkg, versionName = "1.0.0", versionCode = 1, name = name, description = "Quick notes",
        minCoreApi = 2, targetCoreApi = 3, capabilities = capabilities, commands = emptyList(), bubble = bubble
    )

    private fun entry(m: ExternalPluginManifest = manifest()) = TrustEntry(m.packageName, cert, m)

    private fun build(
        entries: List<TrustEntry> = listOf(entry()),
        statuses: Map<String, ExternalStatus> = entries.associate { it.packageName to ExternalStatus.Ready },
        enabled: Set<String> = emptySet(),
        bubble: Set<String> = emptySet(),
        needs: Map<String, List<String>> = emptyMap()
    ) = AccessOverview.build(entries, statuses, enabled, bubble, needs)

    private fun source(relativePath: String): String {
        val candidates = listOf(File("src/main/java/$relativePath"), File("app/src/main/java/$relativePath"))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("Could not locate production source: $relativePath")
    }

    @Test fun noTrustedPluginGivesTheEmptyState() {
        val data = build(entries = emptyList())
        assertTrue(data.isEmpty)
        assertTrue(data.rows.isEmpty())
        assertEquals(0, data.moreCount)
    }

    @Test fun enabledAndBubbleAccessAreSeparateLines() {
        val off = build().rows.single().lines
        assertTrue(off[0].startsWith("Enabled: no."))
        assertTrue(off[1].contains("not allowed"))

        val enabledOnly = build(enabled = setOf("com.example.notes")).rows.single().lines
        assertTrue(enabledOnly[0].startsWith("Enabled: yes."))
        assertTrue("enabling alone never shows Bubble access", enabledOnly[1].contains("not allowed"))

        val bubbleOnly = build(bubble = setOf("com.example.notes")).rows.single().lines
        assertTrue(bubbleOnly[0].startsWith("Enabled: no."))
        assertTrue(bubbleOnly[1].contains("allowed by you"))
    }

    @Test fun aPluginWithoutABubbleViewSaysSo() {
        val lines = build(entries = listOf(entry(manifest(bubble = null)))).rows.single().lines
        assertTrue(lines[1].contains("offers no Bubble view"))
    }

    @Test fun capabilitiesThatAskFirstFollowTheGatewayRule() {
        val all = listOf("commands", "internet", "screen_capture", "microphone", "audio", "device_actions", "accessibility", "automation", "files", "app_launch")
        val line = build(entries = listOf(entry(manifest(capabilities = all)))).rows.single().lines[2]
        for (capability in all) {
            assertEquals("$capability: ${CapabilityPolicy.requiresApproval(capability)}", CapabilityPolicy.requiresApproval(capability), line.contains(capability))
        }
    }

    @Test fun aPluginWithNoSensitiveCapabilityStatesThat() {
        val line = build(entries = listOf(entry(manifest(capabilities = listOf("commands"))))).rows.single().lines[2]
        assertTrue(line.contains("no capability that asks first"))
    }

    @Test fun statusLinesDistinguishEveryTrustState() {
        fun first(status: ExternalStatus?) = build(statuses = if (status == null) emptyMap() else mapOf("com.example.notes" to status)).rows.single().lines[0]
        assertTrue(first(ExternalStatus.Ready).contains("Trusted."))
        assertTrue(first(null).contains("Trusted."))
        assertTrue(first(ExternalStatus.UpdatePending(listOf("more access"))).contains("waiting for your review"))
        assertTrue(first(ExternalStatus.SignatureChanged).contains("different certificate"))
        assertTrue(first(ExternalStatus.NotInstalled).contains("not installed"))
    }

    @Test fun needsConfigurationIsShown() {
        val line = build(needs = mapOf("com.example.notes" to listOf("Mode"))).rows.single().lines[0]
        assertTrue(line.contains("needs configuration: Mode"))
    }

    @Test fun signerIsShortenedButRecognisable() {
        val line = build().rows.single().lines[3]
        assertEquals("Signer: AB:AB:AB:AB:AB:AB\u2026", line)
        assertEquals("AB:AB", AccessOverview.shortFingerprint("abab"))
    }

    @Test fun rowsAreSortedByNameThenPackage() {
        val entries = listOf(
            entry(manifest(pkg = "com.z", name = "beta")),
            entry(manifest(pkg = "com.b", name = "Alpha")),
            entry(manifest(pkg = "com.a", name = "Alpha"))
        )
        assertEquals(listOf("Alpha", "Alpha", "beta"), build(entries = entries).rows.map { it.title })
        val sameName = build(entries = entries).rows.take(2).map { it.lines[3] }
        assertEquals(2, sameName.size)
    }

    @Test fun hostilePluginTextIsCleaned() {
        val hostile = "Safe\u202Eevil\u200B\u0007 name"
        val data = build(entries = listOf(entry(manifest(name = hostile, capabilities = listOf("files\u202E", "commands")))), needs = mapOf("com.example.notes" to listOf("Mo\u202Ede")))
        val text = data.rows.flatMap { listOf(it.title) + it.lines }.joinToString("\n")
        assertFalse(text.any { it == '\u202E' || it == '\u200B' || it == '\u0007' })
        assertEquals("Safeevil name", data.rows.single().title)
    }

    @Test fun aBlankNameFallsBackToThePackageName() {
        assertEquals("com.example.notes", build(entries = listOf(entry(manifest(name = "\u200B")))).rows.single().title)
    }

    @Test fun theListIsBoundedAndCountsTheRest() {
        val entries = (1..AccessOverview.MAX_ROWS + 7).map { entry(manifest(pkg = "com.example.p$it", name = "Plugin %03d".format(it))) }
        val data = build(entries = entries)
        assertEquals(AccessOverview.MAX_ROWS, data.rows.size)
        assertEquals(7, data.moreCount)
        assertFalse(data.isEmpty)
    }

    @Test fun buildingChangesNothing() {
        val entries = listOf(entry())
        val before = entries.toList()
        build(entries = entries)
        assertEquals(before, entries)
    }

    @Test fun explanationStatesThereAreNoPermanentGrants() {
        assertTrue(AccessOverview.explanation.any { it.contains("no permanent grants") })
        assertTrue(AccessOverview.explanation.any { it.contains("Removing a plugin") })
    }

    @Test fun theSettingsCardOffersNoStateChangingControl() {
        val main = source("com/oneai/app/MainActivity.kt")
        val start = main.indexOf("ACCESS OVERVIEW")
        assertTrue(start > 0)
        val end = main.indexOf("if(confirmClearAiKey)", start)
        assertTrue(end > start)
        val card = main.substring(start, end)
        for (control in listOf("Switch(", "Button(", "TextButton(", "IconButton(", "onClick", "clickable")) {
            assertFalse("the overview must not contain $control", card.contains(control))
        }
        assertFalse("the old static security text is gone", main.contains("The shell does not execute downloaded plugin code."))
    }

    @Test fun centerBuildsTheOverviewWithoutScanningTheDevice() {
        val center = source("com/oneai/app/plugins/ExternalPluginCenter.kt")
        val body = center.substring(center.indexOf("fun accessOverview()"), center.indexOf("fun effectiveSettings"))
        assertFalse("must not call discover()", body.contains("discover()"))
        assertFalse("must use peek, which records nothing", body.contains("trust.check("))
        assertTrue(body.contains("trust.peek("))
    }
}
