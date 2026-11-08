package com.oneai.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Stage 10, slice 10.4: an unreadable saved key is told apart from a missing one, and no secret reaches any text. */
class SecretReadTest {
    private fun source(relativePath: String): String {
        val candidates = listOf(File("src/main/java/$relativePath"), File("app/src/main/java/$relativePath"))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("Could not locate production source: $relativePath")
    }

    @Test fun absentIsNotSaved() = assertEquals(CredentialStatus.NOT_SAVED, CredentialStatus.of(SecretRead.Absent))

    @Test fun aReadableKeyIsSaved() = assertEquals(CredentialStatus.SAVED, CredentialStatus.of(SecretRead.Present("sk-test")))

    @Test fun aBlankStoredValueIsNotSaved() = assertEquals(CredentialStatus.NOT_SAVED, CredentialStatus.of(SecretRead.Present("   ")))

    @Test fun anUnreadableKeyIsNotReportedAsMissing() {
        val status = CredentialStatus.of(SecretRead.Unreadable)
        assertEquals(CredentialStatus.UNREADABLE, status)
        assertTrue(status != CredentialStatus.NOT_SAVED)
    }

    @Test fun theThreeStatesHaveDistinctWording() {
        assertEquals(3, CredentialStatus.values().map { it.settingsText }.toSet().size)
        assertEquals("A saved key could not be read. Save it again.", CredentialStatus.UNREADABLE.settingsText)
        assertEquals(CredentialStatus.UNREADABLE_MESSAGE, CredentialStatus.UNREADABLE.settingsText)
    }

    @Test fun theSecretNeverAppearsInTheTextOfAReadResult() {
        val secret = "sk-very-secret-value"
        assertFalse(SecretRead.Present(secret).toString().contains(secret))
        CredentialStatus.values().forEach { assertFalse(it.settingsText.contains(secret)) }
        assertFalse(SecretRead.Unreadable.toString().contains(secret))
    }

    @Test fun vaultKeepsAbsentAndUnreadableApartAndNeverLogsTheCause() {
        val vault = source("com/oneai/app/security/SecretVault.kt")
        assertTrue(vault.contains("?: return SecretRead.Absent"))
        assertTrue(vault.contains("SecretRead.Unreadable"))
        assertFalse("the old null-for-everything read must be gone", vault.contains(".getOrNull()"))
        assertFalse(vault.contains("Log.") || vault.contains("println"))
    }

    @Test fun existingReadersKeepTheirNullableContract() {
        val vault = source("com/oneai/app/security/SecretVault.kt")
        assertTrue(vault.contains("fun read(): String? = (readResult() as? SecretRead.Present)?.value"))
        assertTrue(vault.contains("fun readNamed(name: String): String? = (readNamedResult(name) as? SecretRead.Present)?.value"))
    }

    @Test fun theAiClientAndSettingsUseTheUnreadableMessage() {
        val client = source("com/oneai/app/ai/AiProviderClient.kt")
        assertTrue(client.contains("CREDENTIALS_UNREADABLE"))
        assertTrue(client.contains("CredentialStatus.UNREADABLE_MESSAGE"))
        val main = source("com/oneai/app/MainActivity.kt")
        // Slice S1 (provider profiles): the key status is still derived from a stored read result, now the selected profile's.
        assertTrue(main.contains("CredentialStatus.of(profiles.keyResult(selected.id))"))
        assertTrue(main.contains("aiKeyStatus.settingsText"))
        assertFalse("the AI key status must not collapse unreadable into not saved", main.contains("if(hasAiKey) \"saved"))
    }

    @Test fun anUnreadableKeyCanStillBeRemovedFromSettings() {
        val main = source("com/oneai/app/MainActivity.kt")
        assertTrue(main.contains("enabled=aiKeyStatus!=CredentialStatus.NOT_SAVED"))
    }
}
