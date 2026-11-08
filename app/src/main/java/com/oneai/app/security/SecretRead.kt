package com.oneai.app.security

/**
 * Outcome of reading a stored credential (Stage 10, slice 10.4, decision D7/D-L).
 *
 * The old read returned null for both "nothing saved" and "saved but could not be decrypted" (for example after the
 * Keystore key was invalidated), so a damaged key looked like a missing one. This keeps the two apart. The secret
 * itself is never part of any text this type produces.
 */
sealed interface SecretRead {
    /** Nothing is stored. */
    object Absent : SecretRead { override fun toString() = "Absent" }

    /** A stored value was read. [toString] never shows it. */
    class Present(val value: String) : SecretRead { override fun toString() = "Present(redacted)" }

    /** Something is stored but could not be read back. */
    object Unreadable : SecretRead { override fun toString() = "Unreadable" }
}

private const val UNREADABLE_TEXT = "A saved key could not be read. Save it again."

/** What the user is told about a stored credential. Pure Kotlin so the wording is testable on the JVM. */
enum class CredentialStatus(val settingsText: String) {
    NOT_SAVED("not saved"),
    SAVED("saved (encrypted on this device)"),
    UNREADABLE(UNREADABLE_TEXT);

    companion object {
        /** Shown in Settings and returned as the AI error when a stored key cannot be read. */
        const val UNREADABLE_MESSAGE = UNREADABLE_TEXT

        fun of(result: SecretRead): CredentialStatus = when (result) {
            is SecretRead.Present -> if (result.value.isBlank()) NOT_SAVED else SAVED
            SecretRead.Absent -> NOT_SAVED
            SecretRead.Unreadable -> UNREADABLE
        }
    }
}
