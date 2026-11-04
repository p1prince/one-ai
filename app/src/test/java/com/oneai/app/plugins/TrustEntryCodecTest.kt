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

    @Test fun corruptOrTamperedDataIsRejected() {
        val good = TrustEntryCodec.encode(TrustEntry("dev.example.notes", cert, manifest()))
        assertNull(TrustEntryCodec.decode("not json"))
        assertNull(TrustEntryCodec.decode(good.replace("\"package\":\"dev.example.notes\",\"certificate\"", "\"package\":\"dev.other.app\",\"certificate\"")))
        assertNull(TrustEntryCodec.decode(good.replace(cert, "nothex")))
        assertNull(TrustEntryCodec.decode(good.replace("\"files\"", "\"execute_arbitrary_code\"")))
    }
}
