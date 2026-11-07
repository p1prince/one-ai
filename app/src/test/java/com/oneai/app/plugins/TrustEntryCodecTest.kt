package com.oneai.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrustEntryCodecTest {
    private val cert = "a".repeat(64)

    private fun manifest(versionCode: Long = 1, bubble: ExternalBubbleDeclaration? = ExternalBubbleDeclaration("notes", "Notes")) = ExternalPluginManifest(
        packageName = "dev.example.notes", versionName = "1.0.0", versionCode = versionCode, name = "Notes", description = "Quick notes",
        minCoreApi = 1, targetCoreApi = 1, capabilities = listOf("files", "internet"),
        commands = listOf(ExternalCommandDeclaration("add_note", "Add note", "Saves", "files")),
        bubble = bubble, dependencies = listOf("dev.example.base")
    )

    @Test fun roundTripsAnEntryWithAndWithoutAPendingUpdate() {
        val plain = TrustEntry("dev.example.notes", cert, manifest())
        assertEquals(plain, TrustEntryCodec.decode(TrustEntryCodec.encode(plain)))
        val pending = plain.copy(pendingUpdate = PendingUpdate(manifest(versionCode = 2, bubble = null), listOf("New commands: find_note")))
        assertEquals(pending, TrustEntryCodec.decode(TrustEntryCodec.encode(pending)))
    }

    private fun tampered(edit: (org.json.JSONObject) -> Unit): String {
        val json = org.json.JSONObject(TrustEntryCodec.encode(TrustEntry("dev.example.notes", cert, manifest())))
        edit(json)
        return json.toString()
    }

    @Test fun corruptOrTamperedDataIsRejected() {
        assertNull(TrustEntryCodec.decode("not json"))
        assertNull("package swapped", TrustEntryCodec.decode(tampered { it.put("package", "dev.other.app") }))
        assertNull("certificate not hex", TrustEntryCodec.decode(tampered { it.put("certificate", "nothex") }))
        assertNull("unsupported capability", TrustEntryCodec.decode(tampered {
            it.getJSONObject("manifest").put("capabilities", org.json.JSONArray(listOf("execute_arbitrary_code")))
        }))
        assertNull("pending update for a different package", TrustEntryCodec.decode(tampered {
            it.put("pending", org.json.JSONObject().put("manifest", org.json.JSONObject(it.getJSONObject("manifest").toString())).put("reasons", org.json.JSONArray()))
            it.getJSONObject("pending").getJSONObject("manifest").put("package", "dev.other.app")
        }))
    }
}
