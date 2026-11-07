package com.oneai.plugin.sdk

/** Identity of the one app allowed to call a plugin. */
object OneAiHost {
    const val PACKAGE = "com.oneai.app"

    /**
     * SHA-256 hex digests of ONE AI's release signing certificates. EMPTY until the release signing
     * key exists (Stage 12). Empty means fail closed: a plugin then accepts the host only if it opts
     * in to [HostVerifier.allowAnyCertificate] (development builds), or lists certificates itself.
     */
    val RELEASE_CERTIFICATES: Set<String> = emptySet()
}

/**
 * Decides whether a binder caller is ONE AI. Pure logic: the Android layer supplies the caller's
 * package names (from its UID) and a lookup of each package's current signing certificates.
 */
class HostVerifier(
    private val hostPackage: String = OneAiHost.PACKAGE,
    trustedCertificates: Set<String> = OneAiHost.RELEASE_CERTIFICATES,
    /** Development only: accept the host package whatever it is signed with. Never ship a release with this on. */
    private val allowAnyCertificate: Boolean = false
) {
    private val trusted = trustedCertificates.map { it.lowercase().replace(":", "") }.toSet()

    fun isHost(callerPackages: List<String>, certificatesOf: (String) -> Set<String>): Boolean {
        if (hostPackage !in callerPackages) return false
        if (allowAnyCertificate) return true
        val certs = certificatesOf(hostPackage).map { it.lowercase() }
        // Every signer must be trusted; an extra unknown co-signer is not acceptable.
        return trusted.isNotEmpty() && certs.isNotEmpty() && certs.all { it in trusted }
    }
}
