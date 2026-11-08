package com.oneai.app.plugins

import com.oneai.app.Page
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice C3 (decision D12 / D-AD): the pure rule for which plugins may be opened inside the app, and source guards
 * for the entry points. Source guards read source; nothing here renders a screen, which has never been shown on an
 * emulator or device.
 */
class InAppPluginAccessTest {
    private val cert = "a".repeat(64)

    private fun entry(id: String, name: String = "Notes", screen: ExternalBubbleDeclaration? = ExternalBubbleDeclaration("svc_$id", "Notes screen")) = TrustEntry(
        packageName = id, certificateSha256 = cert,
        trustedManifest = ExternalPluginManifest(
            packageName = id, versionName = "1", versionCode = 1, name = name, minCoreApi = 1, targetCoreApi = 1,
            capabilities = listOf("commands"), bubble = screen
        )
    )

    private fun build(
        entries: List<TrustEntry>,
        statuses: Map<String, ExternalStatus> = entries.associate { it.packageName to ExternalStatus.Ready },
        enabled: Set<String> = entries.map { it.packageName }.toSet(),
        needs: Map<String, List<String>> = emptyMap()
    ) = InAppPluginAccess.build(entries, statuses, enabled, needs)

    @Test fun anEnabledTrustedPluginWithAScreenAndCompleteSettingsIsListed() {
        val result = build(listOf(entry("dev.a")))
        assertEquals(listOf(InAppPluginEntry("dev.a", "Notes", "Notes screen")), result)
    }

    @Test fun aDisabledPluginIsNotListed() {
        assertTrue(build(listOf(entry("dev.a")), enabled = emptySet()).isEmpty())
    }

    @Test fun aPluginThatIsNotReadyIsNotListed() {
        val e = entry("dev.a")
        listOf(ExternalStatus.UpdatePending(listOf("more access")), ExternalStatus.SignatureChanged, ExternalStatus.NotInstalled).forEach { status ->
            assertTrue("$status", build(listOf(e), statuses = mapOf("dev.a" to status)).isEmpty())
        }
        assertTrue("no status at all", build(listOf(e), statuses = emptyMap()).isEmpty())
    }

    @Test fun aPluginWithoutAScreenIsNotListed() {
        assertTrue(build(listOf(entry("dev.a", screen = null))).isEmpty())
    }

    @Test fun aPluginThatNeedsConfigurationIsNotListed() {
        assertTrue(build(listOf(entry("dev.a")), needs = mapOf("dev.a" to listOf("Mode"))).isEmpty())
        assertEquals(1, build(listOf(entry("dev.a")), needs = mapOf("dev.a" to emptyList())).size)
    }

    @Test fun oneUnavailablePluginDoesNotHideAnother() {
        val result = build(listOf(entry("dev.a", "Alpha"), entry("dev.b", "Beta")), enabled = setOf("dev.b"))
        assertEquals(listOf("dev.b"), result.map { it.pluginId })
    }

    @Test fun listingIsSortedByNameAndBounded() {
        val many = (1..40).map { entry("dev.p%02d".format(it), "Plugin %02d".format(41 - it)) }
        val result = build(many)
        assertEquals(InAppPluginAccess.MAX_ENTRIES, result.size)
        assertEquals(result.map { it.name }, result.map { it.name }.sortedBy { it.lowercase() })
    }

    @Test fun pluginSuppliedNamesAreCleanedAndNeverBlank() {
        val hidden = entry("dev.a", name = "No\u0000te\ns‮")
        val shown = build(listOf(hidden)).single()
        assertFalse(shown.name.any { Character.isISOControl(it) || it == '‮' })
        val blank = build(listOf(entry("dev.b", name = "   "))).single()
        assertEquals("dev.b", blank.name)
    }

    // ---- source guards ----

    private fun source(relative: String): String =
        listOf("src/main/java/com/oneai/app/", "app/src/main/java/com/oneai/app/").map { File(it + relative) }.first { it.isFile }.readText()

    @Test fun theAccessRuleHasNoAndroidImportsAndNoBubbleAccessInput() {
        val rule = source("plugins/InAppPluginAccess.kt")
        assertFalse(rule.contains("import android"))
        listOf("hasBubbleAccess", "setBubbleAccess", "bubbleBindings", "bubbleAccessIds", "PluginManager").forEach {
            assertFalse("the rule must not read $it", rule.contains(it))
        }
    }

    @Test fun theCentersInAppListNeverReadsBubbleAccess() {
        val center = source("plugins/ExternalPluginCenter.kt")
        val body = center.substringAfter("fun inAppPlugins()").substringBefore("fun effectiveSettings")
        assertTrue(body.contains("InAppPluginAccess.build("))
        assertFalse(body.contains("hasBubbleAccess"))
        assertFalse(body.contains("bubbleAccessIds"))
    }

    @Test fun theControllerFactoryChecksTheListAndNeverReadsBubbleAccess() {
        val services = source("AppServices.kt")
        val factory = services.substringAfter("fun inAppPluginController(").substringBefore("fun restorePlugins")
        assertTrue("it refuses a plugin that is not openable", factory.contains("center.inAppPlugins()"))
        assertTrue(factory.contains("pluginRestoration.awaitReady()"))
        assertFalse(factory.contains("hasBubbleAccess"))
        assertFalse(factory.contains("bubbleBindings"))
    }

    @Test fun theOpenButtonAndHomeStripUseTheOpenableListAndNotBubbleAccess() {
        val main = source("MainActivity.kt")
        val card = main.substringAfter("if(openable.any { it.pluginId==p.id })").substringBefore("\n")
        assertTrue(card.contains("onOpenPlugin(p.id)"))
        assertTrue("Open waits for startup restoration like the other plugin controls", card.contains("enabled=restored"))
        assertFalse(card.contains("hasBubbleAccess"))
        val strip = main.substringAfter("YOUR PLUGINS").substringBefore("SYSTEM STATUS")
        assertTrue(strip.contains("onOpenPlugin(it.pluginId)"))
        assertFalse(strip.contains("hasBubbleAccess"))
    }

    @Test fun thePluginScreenIsASubPageAndEveryNavigationTapClosesIt() {
        val main = source("MainActivity.kt")
        assertTrue("no sixth destination", Page.destinations.size == 5)
        assertFalse("the sub-page is not a Page value", Page.values().any { it.name.contains("Plugin", ignoreCase = true) && it != Page.Plugins })
        assertTrue(main.contains("onClick={openPlugin=null;page=p;refresh++}"))
        assertTrue("the screen is only shown with a live controller", main.contains("if(opened!=null && openedController!=null)"))
        assertTrue("a plugin that stops being openable closes the screen", main.contains("if(opened!=null && openedController==null) LaunchedEffect(opened) { openPlugin=null"))
    }

    @Test fun nothingOpensAPluginScreenAtStartup() {
        val main = source("MainActivity.kt")
        assertTrue(main.contains("var openPlugin by remember { mutableStateOf<String?>(null) }"))
        assertFalse(main.contains("openPlugin = \""))
        assertFalse(main.contains("openPlugin=\""))
    }
}
