package com.oneai.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Verifies Device Control's real package metadata and declarative Bubble payload against host contracts. */
class DeviceControlReferencePluginContractTest {
    private val packageName = "com.oneai.reference.devicecontrol"

    private fun repositoryFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "reference-plugins/device-control/$relative")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("Device Control reference file not found: $relative")
    }

    private fun toNode(element: Element): XmlNode {
        val attributes = (0 until element.attributes.length).associate { index ->
            val attribute = element.attributes.item(index)
            attribute.nodeName to attribute.nodeValue
        }
        val children = (0 until element.childNodes.length)
            .map { element.childNodes.item(it) }
            .filterIsInstance<Element>()
            .map(::toNode)
        return XmlNode(element.tagName, attributes, children)
    }

    private fun manifest(): ExternalPluginManifest {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(repositoryFile("src/main/res/xml/one_ai_plugin.xml"))
        return ExternalManifestParser.parse(toNode(document.documentElement), packageName, "1.0.0", 1L).getOrThrow()
    }

    @Test fun actualPluginManifestPassesHostValidationAndApiCompatibility() {
        val parsed = manifest()
        assertEquals(packageName, parsed.packageName)
        assertEquals("Device Control", parsed.name)
        assertEquals(2, parsed.manifestVersion)
        assertEquals(2, parsed.minCoreApi)
        assertEquals(2, parsed.targetCoreApi)
        assertEquals(listOf("device_actions"), parsed.capabilities)
        assertEquals(
            listOf("open_wifi_settings", "open_bluetooth_settings", "open_sound_settings", "open_display_settings", "open_location_settings"),
            parsed.commands.map { it.name }
        )
        assertEquals(ExternalBubbleDeclaration("device_control", "Device control"), parsed.bubble)
        assertTrue(PluginCompatibility.check(parsed).usable)
        assertTrue(ExternalManifestValidator.validate(parsed).isSuccess)
    }

    @Test fun androidPackageDeclaresDiscoverablePermissionFreePluginService() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(repositoryFile("src/main/AndroidManifest.xml"))
        val services = document.getElementsByTagName("service")
        assertEquals(1, services.length)
        val service = services.item(0) as Element
        assertEquals("true", service.getAttribute("android:exported"))
        assertFalse(service.hasAttribute("android:permission"))
        val actions = service.getElementsByTagName("action")
        assertEquals(listOf(PluginApi.SERVICE_ACTION), (0 until actions.length).map {
            (actions.item(it) as Element).getAttribute("android:name")
        })
        val metadata = service.getElementsByTagName("meta-data").item(0) as Element
        assertEquals(PluginApi.MANIFEST_META_DATA, metadata.getAttribute("android:name"))
        assertEquals("@xml/one_ai_plugin", metadata.getAttribute("android:resource"))
    }

    @Test fun sharedBubbleFixtureIsAcceptedByTheActualHostParser() {
        val view = ExternalViewParser.parse(
            repositoryFile("src/test/resources/device_control_view.json").readText(),
            manifest()
        ).getOrThrow()
        val buttons = view.elements.filterIsInstance<ExternalViewElement.Button>()
        assertEquals(
            listOf("open_wifi_settings", "open_bluetooth_settings", "open_sound_settings", "open_display_settings", "open_location_settings"),
            buttons.map { it.command }
        )
    }

    @Test fun retiredInterimHostDeviceControlIsNotRegistered() {
        assertFalse(BuiltInCatalogue.templates.any { it.id == "one.device.actions" })
        assertFalse("device_control" in BuiltInCatalogue.bubbleBindings.values)
        assertFalse(BuiltInBubbleProviders.bindings.containsKey("one.device.actions"))
    }
}
