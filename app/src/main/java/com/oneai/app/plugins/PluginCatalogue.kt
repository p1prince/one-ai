package com.oneai.app.plugins

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Stage 9.7a — the generic plugin catalogue model (decision D6, `docs/STAGE_9_CATALOGUE_DESIGN.md`).
 *
 * A catalogue is a listing, never authority: nothing here installs, imports, trusts, enables or grants
 * Bubble access to a plugin. Entries describe plugins; the installed package's own manifest remains the
 * only source of what the host trusts and runs. All text here comes from an outside source and must be
 * shown as untrusted text.
 *
 * Pure Kotlin apart from `org.json` (the same JSON model the trust store codec uses). No network, no storage.
 */
data class CatalogueEntry(
    /** Android package name; the same identity rules as a plugin manifest. */
    val id: String,
    val name: String,
    val summary: String = "",
    val versionName: String,
    val versionCode: Long,
    val minCoreApi: Int,
    val targetCoreApi: Int,
    val capabilities: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    /** Normalised SHA-256 hex digests the source claims sign this plugin. Advisory; the user's own fingerprint check is the trust anchor. */
    val signerSha256: List<String>,
    /** HTTPS page the user opens to get the plugin. ONE AI never downloads or installs it (D-F). */
    val getUrl: String,
    val homepage: String? = null
)

/** A validated catalogue document. [skippedEntries] counts entries that were dropped for being invalid. */
data class CatalogueDocument(
    val name: String,
    val entries: List<CatalogueEntry>,
    val skippedEntries: Int
)

object CatalogueValidator {
    const val DOCUMENT_VERSION = 1
    const val MAX_DOCUMENT_BYTES = 256 * 1024
    const val MAX_ENTRIES = 200
    const val MAX_DOCUMENT_NAME = 60
    const val MAX_NAME = 60
    const val MAX_SUMMARY = 200
    const val MAX_VERSION_NAME = 40
    const val MAX_CAPABILITIES = 12
    const val MAX_DEPENDENCIES = 16
    const val MAX_SIGNERS = 4
    const val MAX_URL = 500

    /**
     * An HTTPS URL without credentials, safe to store or open. Anything else (http, other schemes, userinfo,
     * no host, whitespace, over-long) is refused; there is no cleartext fallback.
     */
    fun validateUrl(raw: String): Result<String> = runCatching {
        val url = raw.trim()
        require(url.isNotEmpty() && url.length <= MAX_URL) { "A URL must be 1–$MAX_URL characters" }
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Only https URLs are allowed" }
        require(uri.rawUserInfo == null) { "A URL must not contain credentials" }
        require(!uri.host.isNullOrBlank()) { "A URL needs a host name" }
        url
    }

    /** Returns a normalised copy of [entry], or a failure whose message is safe to show the user. */
    fun validateEntry(entry: CatalogueEntry): Result<CatalogueEntry> = runCatching {
        val id = entry.id.trim()
        require(ExternalManifestValidator.isValidPluginId(id)) { "Entry id must be a lowercase reverse-domain package name" }
        val name = entry.name.trim()
        require(name.isNotEmpty() && name.length <= MAX_NAME) { "Entry name must be 1–$MAX_NAME characters" }
        val summary = entry.summary.trim()
        require(summary.length <= MAX_SUMMARY) { "Entry summary must be $MAX_SUMMARY characters or fewer" }
        val versionName = entry.versionName.trim()
        require(versionName.length <= MAX_VERSION_NAME && ExternalManifestValidator.isValidVersionName(versionName)) {
            "Entry versionName must use semantic versioning"
        }
        require(entry.versionCode >= 1) { "Entry versionCode must be positive" }
        require(entry.minCoreApi >= 1) { "minCoreApi must be 1 or higher" }
        require(entry.targetCoreApi >= entry.minCoreApi) { "targetCoreApi must not be lower than minCoreApi" }

        require(entry.capabilities.size <= MAX_CAPABILITIES) { "An entry may list at most $MAX_CAPABILITIES capabilities" }
        val capabilities = entry.capabilities.map { it.trim().lowercase() }.distinct()
        require(capabilities.all { ExternalManifestValidator.isValidIdentifier(it) }) { "Capability names are lowercase identifiers" }

        require(entry.dependencies.size <= MAX_DEPENDENCIES) { "An entry may list at most $MAX_DEPENDENCIES dependencies" }
        val dependencies = entry.dependencies.map { it.trim() }
        require(dependencies.distinct().size == dependencies.size) { "Duplicate dependencies are not allowed" }
        require(dependencies.all { ExternalManifestValidator.isValidPluginId(it) }) { "Every dependency must be a valid plugin id" }
        require(id !in dependencies) { "An entry cannot depend on itself" }

        require(entry.signerSha256.size in 1..MAX_SIGNERS) { "An entry lists 1–$MAX_SIGNERS signer fingerprints" }
        val signers = entry.signerSha256.map {
            CertificateFingerprint.normalize(it) ?: throw IllegalArgumentException("A signer fingerprint is not a SHA-256 digest")
        }.distinct()

        val getUrl = validateUrl(entry.getUrl).getOrThrow()
        val homepage = entry.homepage?.let { validateUrl(it).getOrThrow() }

        entry.copy(
            id = id, name = name, summary = summary, versionName = versionName,
            capabilities = capabilities, dependencies = dependencies, signerSha256 = signers,
            getUrl = getUrl, homepage = homepage
        )
    }
}

/**
 * Parses and validates a catalogue document. An invalid document (too large, not JSON, wrong version,
 * missing name or entries, too many entries, duplicate ids) is rejected whole; a single invalid entry is
 * skipped and counted. Nothing is truncated and unknown fields are ignored, so newer documents stay readable.
 */
object CatalogueParser {
    fun parse(json: String): Result<CatalogueDocument> = runCatching {
        require(json.toByteArray(Charsets.UTF_8).size <= CatalogueValidator.MAX_DOCUMENT_BYTES) { "The catalogue is too large" }
        val root = try { JSONObject(json) } catch (e: Exception) { throw IllegalArgumentException("The catalogue is not valid JSON") }
        val version = root.opt("catalogueVersion")
        require((version is Int || version is Long) && (version as Number).toLong() == CatalogueValidator.DOCUMENT_VERSION.toLong()) {
            "Unsupported catalogue version"
        }
        val name = (root.opt("name") as? String)?.trim().orEmpty()
        require(name.isNotEmpty() && name.length <= CatalogueValidator.MAX_DOCUMENT_NAME) {
            "The catalogue needs a name of 1–${CatalogueValidator.MAX_DOCUMENT_NAME} characters"
        }
        val array = root.opt("entries") as? JSONArray ?: throw IllegalArgumentException("The catalogue has no entries list")
        require(array.length() <= CatalogueValidator.MAX_ENTRIES) { "The catalogue lists too many entries" }

        var skipped = 0
        val entries = ArrayList<CatalogueEntry>()
        for (i in 0 until array.length()) {
            val entry = (array.opt(i) as? JSONObject)
                ?.let { runCatching { CatalogueValidator.validateEntry(entryFrom(it)).getOrThrow() }.getOrNull() }
            if (entry == null) skipped++ else entries += entry
        }
        require(entries.map { it.id }.distinct().size == entries.size) { "The catalogue lists the same plugin id twice" }
        CatalogueDocument(name, entries, skipped)
    }

    /** Strict typing: a number given as a string, or a string given as a number, makes the entry invalid. */
    private fun entryFrom(o: JSONObject): CatalogueEntry = CatalogueEntry(
        id = o.string("id"),
        name = o.string("name"),
        summary = o.optionalString("summary") ?: "",
        versionName = o.string("versionName"),
        versionCode = o.integer("versionCode"),
        minCoreApi = o.integer("minCoreApi").toIntExact(),
        targetCoreApi = o.integer("targetCoreApi").toIntExact(),
        capabilities = o.stringList("capabilities", required = false),
        dependencies = o.stringList("dependencies", required = false),
        signerSha256 = o.stringList("signerSha256", required = true),
        getUrl = o.string("getUrl"),
        homepage = o.optionalString("homepage")
    )

    private fun JSONObject.string(key: String): String =
        opt(key) as? String ?: throw IllegalArgumentException("$key must be a string")

    private fun JSONObject.optionalString(key: String): String? =
        if (!has(key) || isNull(key)) null else opt(key) as? String ?: throw IllegalArgumentException("$key must be a string")

    private fun JSONObject.integer(key: String): Long {
        val value = opt(key)
        require(value is Int || value is Long) { "$key must be an integer" }
        return (value as Number).toLong()
    }

    private fun Long.toIntExact(): Int {
        require(this in Int.MIN_VALUE..Int.MAX_VALUE) { "Number out of range" }
        return toInt()
    }

    private fun JSONObject.stringList(key: String, required: Boolean): List<String> {
        if (!has(key) || isNull(key)) {
            require(!required) { "$key is required" }
            return emptyList()
        }
        val array = opt(key) as? JSONArray ?: throw IllegalArgumentException("$key must be a list")
        return (0 until array.length()).map { array.opt(it) as? String ?: throw IllegalArgumentException("$key must contain only strings") }
    }
}

/** Whether a listed plugin is present on this device and whether the user has trusted it. */
enum class CatalogueInstallState { NOT_INSTALLED, INSTALLED_NOT_IMPORTED, IMPORTED }

/** What the host knows about an installed package that a catalogue entry refers to. */
data class InstalledPluginFacts(
    val versionCode: Long,
    /** Current signing certificates of the installed package, as reported by the platform. */
    val signerSha256: List<String>,
    /** True when the user has already imported (trusted) it. */
    val imported: Boolean
)

/** How one entry relates to this device. Read-only information; it never changes plugin state. */
data class CatalogueEntryStatus(
    val install: CatalogueInstallState,
    val compatibility: Compatibility,
    /** Listed dependencies that are not installed and enabled, in listed order. */
    val missingDependencies: List<String>,
    /** The installed package's certificate is not among the entry's listed signers (or is not a single valid one). */
    val signerDiffers: Boolean,
    /** The listed versionCode is higher than the installed one. Informational: updates arrive through the platform and the existing update review. */
    val updateAvailable: Boolean
) {
    /**
     * Whether the catalogue view may offer to start the existing fingerprint-confirmed import (D-G). A signer
     * mismatch or an incompatible plugin blocks it here; the on-device import path is a separate flow.
     */
    val canImportFromCatalogue: Boolean
        get() = install == CatalogueInstallState.INSTALLED_NOT_IMPORTED && !signerDiffers && compatibility !is Compatibility.Incompatible
}

object CatalogueMatcher {
    /**
     * Classifies [entry] against the device. [installed] is null when no package with the entry's id is
     * present; [enabledPluginIds] are plugins that are installed and enabled, for dependency reporting.
     */
    fun classify(entry: CatalogueEntry, installed: InstalledPluginFacts?, enabledPluginIds: Set<String>): CatalogueEntryStatus {
        val state = when {
            installed == null -> CatalogueInstallState.NOT_INSTALLED
            installed.imported -> CatalogueInstallState.IMPORTED
            else -> CatalogueInstallState.INSTALLED_NOT_IMPORTED
        }
        val installedSigners = installed?.signerSha256.orEmpty().mapNotNull { CertificateFingerprint.normalize(it) }.distinct()
        val signerDiffers = installed != null && (installedSigners.isEmpty() || installedSigners.any { it !in entry.signerSha256 })
        return CatalogueEntryStatus(
            install = state,
            compatibility = PluginCompatibility.check(entry.minCoreApi, entry.targetCoreApi),
            missingDependencies = PluginCompatibility.missingDependencies(entry.dependencies, enabledPluginIds),
            signerDiffers = signerDiffers,
            updateAvailable = installed != null && entry.versionCode > installed.versionCode
        )
    }
}
