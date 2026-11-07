package com.oneai.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTrustTest {
    private val certA = "a".repeat(64)
    private val certB = "b".repeat(64)

    private class FakeSignatures : PackageSignatureSource {
        val installed = mutableMapOf<String, List<String>>()
        val history = mutableMapOf<String, Set<String>>()
        override fun currentSigners(packageName: String) = installed[packageName]
        override fun hasSigner(packageName: String, sha256: String): Boolean? {
            val current = installed[packageName] ?: return null
            return current.any { it.lowercase() == sha256 } || sha256 in history[packageName].orEmpty()
        }
    }

    private val events = mutableListOf<String>()
    private val signatures = FakeSignatures().also { it.installed["dev.example.notes"] = listOf("A".repeat(64)) }
    private val registry = PluginTrustRegistry(InMemoryTrustStore(), signatures) { events += it }

    private fun manifest(versionCode: Long = 1, capabilities: List<String> = listOf("files"), minCoreApi: Int = 1) = ExternalPluginManifest(
        packageName = "dev.example.notes", versionName = "1.0.0", versionCode = versionCode, name = "Notes",
        minCoreApi = minCoreApi, targetCoreApi = minCoreApi, capabilities = capabilities,
        commands = listOf(ExternalCommandDeclaration("add_note", "Add", "", "files"))
    )

    @Test fun fingerprintsNormaliseAndDisplay() {
        assertEquals(certA, CertificateFingerprint.normalize(CertificateFingerprint.display(certA)))
        assertNull(CertificateFingerprint.normalize("not-a-hash"))
        assertEquals("AA:AA", CertificateFingerprint.display(certA).take(5))
    }

    @Test fun nothingIsTrustedBeforeImport() {
        assertEquals(TrustCheck.NotImported, registry.check("dev.example.notes"))
        assertTrue(registry.trusted().isEmpty())
    }

    @Test fun importRequiresTheFingerprintTheUserSaw() {
        assertEquals(certA, registry.fingerprintForImport("dev.example.notes").getOrThrow())
        assertTrue(registry.import(manifest(), certB).isFailure)
        assertTrue(registry.import(manifest(), "garbage").isFailure)
        assertTrue(registry.trusted().isEmpty())
        assertTrue(registry.import(manifest(), CertificateFingerprint.display(certA)).isSuccess)
        assertTrue(registry.check("dev.example.notes") is TrustCheck.Trusted)
    }

    @Test fun cannotImportTwiceOrIncompatibleOrUninstalled() {
        assertTrue(registry.import(manifest(), certA).isSuccess)
        assertTrue(registry.import(manifest(), certA).isFailure)
        val other = PluginTrustRegistry(InMemoryTrustStore(), signatures)
        assertTrue(other.import(manifest(minCoreApi = 9), certA).isFailure)
        signatures.installed.remove("dev.example.notes")
        assertTrue(PluginTrustRegistry(InMemoryTrustStore(), signatures).import(manifest(), certA).isFailure)
    }

    @Test fun multiSignerPackagesAreRefused() {
        signatures.installed["dev.example.notes"] = listOf(certA, certB)
        assertTrue(registry.fingerprintForImport("dev.example.notes").isFailure)
        signatures.installed["dev.example.notes"] = listOf("not-hex")
        assertTrue(registry.fingerprintForImport("dev.example.notes").isFailure)
    }

    @Test fun replacedSigningKeyIsBlockedAndLogged() {
        registry.import(manifest(), certA)
        signatures.installed["dev.example.notes"] = listOf(certB)
        assertEquals(TrustCheck.SignatureMismatch, registry.check("dev.example.notes"))
        assertTrue(events.any { it.contains("no longer matches") })
    }

    @Test fun keyRotationHistoryKeepsTheTrust() {
        registry.import(manifest(), certA)
        signatures.installed["dev.example.notes"] = listOf(certB)
        signatures.history["dev.example.notes"] = setOf(certA)
        assertTrue(registry.check("dev.example.notes") is TrustCheck.Trusted)
    }

    @Test fun uninstalledPluginIsReported() {
        registry.import(manifest(), certA)
        signatures.installed.remove("dev.example.notes")
        assertEquals(TrustCheck.NotInstalled, registry.check("dev.example.notes"))
    }

    @Test fun harmlessUpdateIsAcceptedAndRefreshesTheDeclaration() {
        registry.import(manifest(), certA)
        assertEquals(UpdateDecision.Accepted, registry.evaluateUpdate(manifest(versionCode = 2)))
        assertEquals(2L, registry.trusted().single().trustedManifest.versionCode)
    }

    @Test fun updateThatAddsPowerWaitsForConsent() {
        registry.import(manifest(), certA)
        val decision = registry.evaluateUpdate(manifest(versionCode = 2, capabilities = listOf("files", "internet")))
        assertTrue(decision is UpdateDecision.ConsentRequired)
        val check = registry.check("dev.example.notes")
        assertTrue(check is TrustCheck.UpdatePending)
        assertEquals(1L, registry.trusted().single().trustedManifest.versionCode)
        val approved = registry.approvePendingUpdate("dev.example.notes").getOrThrow()
        assertEquals(listOf("files", "internet"), approved.trustedManifest.capabilities)
        assertTrue(registry.check("dev.example.notes") is TrustCheck.Trusted)
    }

    @Test fun downgradeAndWrongSignerUpdatesAreRejected() {
        registry.import(manifest(versionCode = 5), certA)
        assertTrue(registry.evaluateUpdate(manifest(versionCode = 4)) is UpdateDecision.Rejected)
        signatures.installed["dev.example.notes"] = listOf(certB)
        assertTrue(registry.evaluateUpdate(manifest(versionCode = 6)) is UpdateDecision.Rejected)
        assertTrue(registry.approvePendingUpdate("dev.example.notes").isFailure)
    }

    @Test fun forgetRemovesTrust() {
        registry.import(manifest(), certA)
        assertTrue(registry.forget("dev.example.notes"))
        assertFalse(registry.forget("dev.example.notes"))
        assertEquals(TrustCheck.NotImported, registry.check("dev.example.notes"))
    }
}
