package com.oneai.app.bubble

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class ScreenCaptureArtifactPolicyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun acceptsOnlyFilesInsidePrivateArtifactDirectory() {
        val root = temporaryFolder.newFolder("selected")
        val valid = File(root, "region.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val outside = temporaryFolder.newFile("outside.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertTrue(ScreenCaptureArtifactPolicy.isValidFile(root, valid))
        assertFalse(ScreenCaptureArtifactPolicy.isValidFile(root, outside))
    }

    @Test fun rejectsMissingEmptyAndOversizedFiles() {
        val root = temporaryFolder.newFolder("selected")
        assertFalse(ScreenCaptureArtifactPolicy.isValidFile(root, File(root, "missing.png")))
        val empty = File(root, "empty.png").apply { createNewFile() }
        assertFalse(ScreenCaptureArtifactPolicy.isValidFile(root, empty))
        val oversized = File(root, "large.png").apply {
            writeBytes(ByteArray((ScreenCaptureArtifactPolicy.MAX_ARTIFACT_BYTES + 1).toInt()))
        }
        assertFalse(ScreenCaptureArtifactPolicy.isValidFile(root, oversized))
    }

    @Test fun validatesArtifactExpiryAndRejectsClockRollback() {
        val now = 1_000_000L
        assertTrue(ScreenCaptureArtifactPolicy.isFresh(now - 1_000, now))
        assertTrue(ScreenCaptureArtifactPolicy.isFresh(now - ScreenCaptureArtifactPolicy.MAX_AGE_MS, now))
        assertFalse(ScreenCaptureArtifactPolicy.isFresh(now - ScreenCaptureArtifactPolicy.MAX_AGE_MS - 1, now))
        assertFalse(ScreenCaptureArtifactPolicy.isFresh(now + 1, now))
        assertFalse(ScreenCaptureArtifactPolicy.isFresh(0, now))
    }
}
