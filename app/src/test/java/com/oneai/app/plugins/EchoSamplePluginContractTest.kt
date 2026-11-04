package com.oneai.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the reference plugin (samples/echo-plugin) against drifting from the host contract: its real
 * manifest XML and Bubble view JSON are read from disk and run through the host's real parsers and
 * validators. This proves the *files* are acceptable to the host; it does not prove that Android can
 * bind to, install or read the APK (that needs an emulator; see docs/EMULATOR_TEST_PLAN.md).
 */
class EchoSamplePluginContractTest {
    private val samplePackage = "com.oneai.sample.echo"

    private fun sampleFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "samples/echo-plugin/$relative")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("Sample plugin file not found: $relative")
    }

    private fun toNode(element: Element): XmlNode {
        val attributes = (0 until element.attributes.length).associate { index ->
            val attribute = element.attributes.item(index)
            attribute.nodeName to attribute.nodeValue
        }
        val children = (0 until element.childNodes.length)
            .map { element.childNodes.item(it) }
            .filterIsInstance<Element>()
            .map { toNode(it) }
        return XmlNode(element.tagName, attributes, children)
    }

    private fun manifest(): ExternalPluginManifest {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(sampleFile("src/main/res/xml/one_ai_plugin.xml"))
        return ExternalManifestParser.parse(toNode(document.documentElement), samplePackage, "1.0.0", 1L).getOrThrow()
    }

    @Test fun manifestIsAcceptedByTheHostParserAndValidator() {
        val manifest = manifest()
        assertEquals(samplePackage, manifest.id)
        assertEquals("Echo", manifest.name)
        assertEquals(listOf("commands"), manifest.capabilities)
        assertEquals(listOf("echo", "reverse", "wait"), manifest.commands.map { it.name })
        assertTrue(manifest.commands.all { it.capability == "commands" })
        assertEquals(ExternalBubbleDeclaration("echo", "Echo"), manifest.bubble)
        assertTrue("sample must be compatible with this host", PluginCompatibility.check(manifest).usable)
    }

    @Test fun bubbleViewIsAcceptedByTheHostViewParser() {
        val view = ExternalViewParser.parse(sampleFile("src/main/res/raw/echo_view.json").readText(), manifest()).getOrThrow()
        val buttons = view.elements.filterIsInstance<ExternalViewElement.Button>()
        assertEquals(listOf("echo", "reverse", "wait"), buttons.map { it.command })
        assertTrue(view.elements.any { it is ExternalViewElement.Input && it.key == "text" })
        // A button wired to the input fills the command's argument from what the user typed.
        assertEquals(mapOf("text" to "hi"), view.resolveArgs(buttons.first { it.command == "echo" }, mapOf("text" to "hi")))
        // Literal arguments pass through unchanged.
        assertEquals("3", view.resolveArgs(buttons.first { it.command == "wait" }, mapOf("text" to "hi"))["seconds"])
    }

    @Test fun androidManifestUsesTheContractConstantsAndNoPermissionOnTheService() {
        val text = sampleFile("src/main/AndroidManifest.xml").readText()
        assertTrue("service action", text.contains("android:name=\"${PluginApi.SERVICE_ACTION}\""))
        assertTrue("manifest meta-data", text.contains("android:name=\"${PluginApi.MANIFEST_META_DATA}\""))
        assertTrue("service must be exported", text.contains("android:exported=\"true\""))
        // The host refuses services that demand a permission, so the sample must not declare one.
        assertTrue("service must not require a permission", !text.contains("android:permission"))
    }

    @Test fun gradleFileDeclaresTheIdentityTheManifestTestAssumes() {
        val gradle = sampleFile("build.gradle.kts").readText()
        assertTrue(gradle.contains("applicationId = \"$samplePackage\""))
        assertTrue(gradle.contains("versionName = \"1.0.0\""))
        assertTrue(gradle.contains("versionCode = 1"))
    }
}
