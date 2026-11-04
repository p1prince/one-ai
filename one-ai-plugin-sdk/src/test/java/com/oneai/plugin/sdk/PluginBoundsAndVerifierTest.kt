package com.oneai.plugin.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginBoundsAndVerifierTest {
    @Test fun validatesIdsAndNames() {
        assertTrue(PluginBounds.isValidRequestId("req-1_A"))
        assertFalse(PluginBounds.isValidRequestId("bad id"))
        assertFalse(PluginBounds.isValidRequestId(null))
        assertFalse(PluginBounds.isValidRequestId("x".repeat(65)))
        assertTrue(PluginBounds.isValidCommandName("add_note"))
        assertFalse(PluginBounds.isValidCommandName("Add"))
    }

    @Test fun errorCodesAreSanitised() {
        assertEquals("NO_NETWORK", PluginBounds.safeErrorCode("NO_NETWORK"))
        assertEquals("PLUGIN_ERROR", PluginBounds.safeErrorCode("secret: /data/user/0/x"))
        assertEquals("PLUGIN_ERROR", PluginBounds.safeErrorCode(null))
    }

    @Test fun parsesAndBoundsArguments() {
        assertEquals(mapOf("a" to "1"), PluginBounds.parseArgs(listOf("a"), listOf("1")).getOrThrow())
        assertTrue(PluginBounds.parseArgs(listOf("a"), emptyList()).isFailure)
        assertTrue(PluginBounds.parseArgs(listOf("a", "a"), listOf("1", "2")).isFailure)
        assertTrue(PluginBounds.parseArgs(listOf("k".repeat(81)), listOf("1")).isFailure)
        assertTrue(PluginBounds.parseArgs(listOf("a"), listOf("v".repeat(4_001))).isFailure)
        assertTrue(PluginBounds.parseArgs((1..33).map { "k$it" }, (1..33).map { "v" }).isFailure)
        assertTrue(PluginBounds.parseArgs(null, null).isFailure)
    }

    @Test fun outputLimits() {
        assertNull(PluginBounds.outputProblem("t", "c"))
        assertNotNull(PluginBounds.outputProblem("t".repeat(121), "c"))
        assertNotNull(PluginBounds.outputProblem("t", "c".repeat(20_001)))
    }

    private val good = "a".repeat(64)

    @Test fun hostMustBeTheCallerAndSignedWithATrustedKey() {
        val verifier = HostVerifier(trustedCertificates = setOf(good))
        assertTrue(verifier.isHost(listOf("com.oneai.app")) { setOf(good) })
        assertFalse(verifier.isHost(listOf("com.evil.app")) { setOf(good) })
        assertFalse(verifier.isHost(listOf("com.oneai.app")) { setOf("b".repeat(64)) })
        assertFalse(verifier.isHost(listOf("com.oneai.app")) { setOf(good, "b".repeat(64)) }) // unknown co-signer
        assertFalse(verifier.isHost(listOf("com.oneai.app")) { emptySet() })
    }

    @Test fun failsClosedWhenNoCertificatesAreConfigured() {
        assertTrue(OneAiHost.RELEASE_CERTIFICATES.isEmpty())
        assertFalse(HostVerifier().isHost(listOf("com.oneai.app")) { setOf(good) })
    }

    @Test fun developmentOverrideStillRequiresTheHostPackage() {
        val dev = HostVerifier(allowAnyCertificate = true)
        assertTrue(dev.isHost(listOf("com.oneai.app")) { emptySet() })
        assertFalse(dev.isHost(listOf("com.evil.app")) { emptySet() })
    }

    @Test fun acceptsFormattedCertificatesInConfiguration() {
        val formatted = good.uppercase().chunked(2).joinToString(":")
        assertTrue(HostVerifier(trustedCertificates = setOf(formatted)).isHost(listOf("com.oneai.app")) { setOf(good) })
    }
}
