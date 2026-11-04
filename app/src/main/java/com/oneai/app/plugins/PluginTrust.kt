package com.oneai.app.plugins

/**
 * Stage 8 — trust and signing-certificate pinning for plugin APKs (design §3).
 *
 * Pure Kotlin: Android's PackageManager sits behind [PackageSignatureSource], so every rule here is
 * JVM-testable. Nothing is bound or run before the user imports a plugin, and every bind re-checks the pin.
 */
interface PackageSignatureSource {
    /** SHA-256 hex digests of the package's current signing certificates, or null if it is not installed. */
    fun currentSigners(packageName: String): List<String>?

    /**
     * Whether [sha256] is, or historically was (key rotation), a signer of the package.
     * Null when the package is not installed. Android 9+ implements this with
     * `PackageManager.hasSigningCertificate`; older versions fall back to the current signers.
     */
    fun hasSigner(packageName: String, sha256: String): Boolean?
}

object CertificateFingerprint {
    private val hex64 = Regex("[0-9a-f]{64}")

    /** Lowercase hex without separators, or null when the input is not a SHA-256 digest. */
    fun normalize(input: String): String? =
        input.trim().lowercase().replace(":", "").replace(" ", "").takeIf { hex64.matches(it) }

    /** `AB:CD:…` form shown to the user at import time. */
    fun display(sha256: String): String =
        sha256.uppercase().chunked(2).joinToString(":")
}

/** What the user agreed to when importing (or later re-approving) a plugin. */
data class TrustEntry(
    val packageName: String,
    val certificateSha256: String,
    val trustedManifest: ExternalPluginManifest,
    /** Set when an update asked for more powers and is waiting for the user. */
    val pendingUpdate: PendingUpdate? = null
)

data class PendingUpdate(val manifest: ExternalPluginManifest, val reasons: List<String>)

/** Persistence for trust entries (SharedPreferences-backed in the app, in-memory in tests). */
interface TrustStore {
    fun get(packageName: String): TrustEntry?
    fun put(entry: TrustEntry)
    fun remove(packageName: String)
    fun all(): List<TrustEntry>
}

class InMemoryTrustStore : TrustStore {
    private val entries = linkedMapOf<String, TrustEntry>()
    @Synchronized override fun get(packageName: String) = entries[packageName]
    @Synchronized override fun put(entry: TrustEntry) { entries[entry.packageName] = entry }
    @Synchronized override fun remove(packageName: String) { entries.remove(packageName) }
    @Synchronized override fun all() = entries.values.toList()
}

/** Outcome of checking a plugin before binding to it. */
sealed interface TrustCheck {
    data class Trusted(val entry: TrustEntry) : TrustCheck
    data object NotImported : TrustCheck
    data object NotInstalled : TrustCheck
    /** Signed by a different key than the one the user trusted. The plugin must be disabled. */
    data object SignatureMismatch : TrustCheck
    /** The installed update is waiting for the user's consent; the old declaration no longer applies. */
    data class UpdatePending(val reasons: List<String>) : TrustCheck
}

sealed interface UpdateDecision {
    /** Same or lower powers; the trusted declaration is refreshed automatically. */
    data object Accepted : UpdateDecision
    data class ConsentRequired(val reasons: List<String>) : UpdateDecision
    data class Rejected(val reason: String) : UpdateDecision
}

class PluginTrustRegistry(
    private val store: TrustStore,
    private val signatures: PackageSignatureSource,
    private val onEvent: (String) -> Unit = {}
) {
    /**
     * The fingerprint to show the user before they decide. Fails when the package is missing or has
     * several signers (multi-signer plugins are not supported, so trust is never ambiguous).
     */
    fun fingerprintForImport(packageName: String): Result<String> {
        val signers = signatures.currentSigners(packageName)
            ?: return Result.failure(IllegalStateException("The plugin app is not installed"))
        val normalised = signers.mapNotNull { CertificateFingerprint.normalize(it) }.distinct()
        if (normalised.size != 1 || normalised.size != signers.distinct().size) {
            return Result.failure(IllegalStateException("The plugin must be signed with exactly one valid certificate"))
        }
        return Result.success(normalised.single())
    }

    /**
     * Imports a plugin. [confirmedFingerprint] is what the user confirmed on screen; it must equal the
     * installed package's certificate, so a swap between "shown" and "stored" cannot happen.
     */
    @Synchronized
    fun import(manifest: ExternalPluginManifest, confirmedFingerprint: String): Result<TrustEntry> {
        val validated = ExternalManifestValidator.validate(manifest).getOrElse { return Result.failure(it) }
        if (PluginCompatibility.check(validated) is Compatibility.Incompatible) {
            return Result.failure(IllegalStateException("This plugin is not compatible with this version of ONE AI"))
        }
        if (store.get(validated.packageName) != null) {
            return Result.failure(IllegalStateException("This plugin is already imported"))
        }
        val actual = fingerprintForImport(validated.packageName).getOrElse { return Result.failure(it) }
        val confirmed = CertificateFingerprint.normalize(confirmedFingerprint)
        if (confirmed == null || confirmed != actual) {
            return Result.failure(SecurityException("The confirmed fingerprint does not match the installed plugin"))
        }
        val entry = TrustEntry(validated.packageName, actual, validated)
        store.put(entry)
        onEvent("Trusted plugin ${validated.name} (${validated.packageName}), certificate ${CertificateFingerprint.display(actual).take(23)}…")
        return Result.success(entry)
    }

    /** Called before every bind. A mismatch is logged so it appears in Activity. */
    @Synchronized
    fun check(packageName: String): TrustCheck = evaluate(packageName, report = true)

    /** Same answer as [check] without writing to the activity log, for screens that redraw often. */
    @Synchronized
    fun peek(packageName: String): TrustCheck = evaluate(packageName, report = false)

    private fun evaluate(packageName: String, report: Boolean): TrustCheck {
        val entry = store.get(packageName) ?: return TrustCheck.NotImported
        val matches = signatures.hasSigner(packageName, entry.certificateSha256) ?: return TrustCheck.NotInstalled
        if (!matches) {
            if (report) onEvent("Blocked ${entry.trustedManifest.name}: signing certificate no longer matches the trusted one")
            return TrustCheck.SignatureMismatch
        }
        entry.pendingUpdate?.let { return TrustCheck.UpdatePending(it.reasons) }
        return TrustCheck.Trusted(entry)
    }

    /**
     * Evaluates the freshly read manifest of an installed update. Downgrades are refused (rollback to a
     * version with known flaws); updates that add powers wait for the user.
     */
    @Synchronized
    fun evaluateUpdate(updated: ExternalPluginManifest): UpdateDecision {
        val validated = ExternalManifestValidator.validate(updated).getOrElse {
            return UpdateDecision.Rejected(it.message ?: "Invalid manifest")
        }
        val entry = store.get(validated.packageName) ?: return UpdateDecision.Rejected("The plugin is not imported")
        if (signatures.hasSigner(validated.packageName, entry.certificateSha256) != true) {
            onEvent("Blocked update of ${entry.trustedManifest.name}: signing certificate does not match")
            return UpdateDecision.Rejected("The update is not signed by the trusted certificate")
        }
        if (PluginCompatibility.check(validated) is Compatibility.Incompatible) {
            return UpdateDecision.Rejected("The update is not compatible with this version of ONE AI")
        }
        if (validated.versionCode < entry.trustedManifest.versionCode) {
            return UpdateDecision.Rejected("Older versions cannot replace a newer trusted version")
        }
        val review = PluginUpdateReview.review(entry.trustedManifest, validated)
        return if (review.consentRequired) {
            store.put(entry.copy(pendingUpdate = PendingUpdate(validated, review.reasons)))
            onEvent("${entry.trustedManifest.name} update needs approval: ${review.reasons.joinToString("; ")}")
            UpdateDecision.ConsentRequired(review.reasons)
        } else {
            store.put(entry.copy(trustedManifest = validated, pendingUpdate = null))
            UpdateDecision.Accepted
        }
    }

    /** The user approved the pending declaration shown to them. */
    @Synchronized
    fun approvePendingUpdate(packageName: String): Result<TrustEntry> {
        val entry = store.get(packageName) ?: return Result.failure(IllegalStateException("The plugin is not imported"))
        val pending = entry.pendingUpdate ?: return Result.failure(IllegalStateException("No update is waiting for approval"))
        if (signatures.hasSigner(packageName, entry.certificateSha256) != true) {
            return Result.failure(SecurityException("The installed plugin no longer matches the trusted certificate"))
        }
        val approved = entry.copy(trustedManifest = pending.manifest, pendingUpdate = null)
        store.put(approved)
        onEvent("Approved update of ${approved.trustedManifest.name} to ${pending.manifest.versionName}")
        return Result.success(approved)
    }

    /** Forgets the pin (the APK itself is uninstalled by the user through Android). */
    @Synchronized
    fun forget(packageName: String): Boolean {
        val entry = store.get(packageName) ?: return false
        store.remove(packageName)
        onEvent("Removed trust for ${entry.trustedManifest.name}")
        return true
    }

    fun trusted(): List<TrustEntry> = store.all()
}
