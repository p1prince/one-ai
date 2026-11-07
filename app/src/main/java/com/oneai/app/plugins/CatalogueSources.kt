package com.oneai.app.plugins

import com.oneai.app.core.CatalogueSourceStorage
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** One user-added catalogue source and the result of its last refresh. All text from the source is untrusted. */
data class CatalogueSourceRecord(
    val url: String,
    /** The document's own name from the last good refresh. Shown as untrusted text. */
    val name: String? = null,
    /** The last document that passed validation, kept so a later failure does not empty the listing. */
    val document: String? = null,
    val lastError: String? = null,
    val lastRefreshedMillis: Long? = null
)

data class DecodedCatalogueSources(val records: List<CatalogueSourceRecord>, val dropped: Int)

/** JSON form of the sources for persistence. Decoding re-checks everything, so stored data is never trusted blindly. */
object CatalogueSourceCodec {
    const val MAX_ERROR = 200

    fun encode(records: List<CatalogueSourceRecord>): String = JSONArray().apply {
        records.forEach { r ->
            put(JSONObject().apply {
                put("url", r.url)
                r.name?.let { put("name", it) }
                r.document?.let { put("document", it) }
                r.lastError?.let { put("error", it) }
                r.lastRefreshedMillis?.let { put("refreshed", it) }
            })
        }
    }.toString()

    /** Null when the data is not a JSON list at all; otherwise the usable records and how many were dropped. */
    fun decode(json: String): DecodedCatalogueSources? {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return null
        val seen = HashSet<String>()
        val records = ArrayList<CatalogueSourceRecord>()
        var dropped = 0
        for (i in 0 until array.length()) {
            val record = (array.opt(i) as? JSONObject)?.let { runCatching { recordFrom(it) }.getOrNull() }
            if (record == null) { dropped++; continue }
            if (records.size >= CatalogueSources.MAX_SOURCES || !seen.add(record.url.lowercase())) { dropped++; continue }
            records += record
        }
        return DecodedCatalogueSources(records, dropped)
    }

    private fun recordFrom(o: JSONObject): CatalogueSourceRecord {
        val url = CatalogueValidator.validateUrl(o.opt("url") as? String ?: error("url")).getOrThrow()
        val name = o.optionalText("name", CatalogueValidator.MAX_DOCUMENT_NAME)
        val document = o.optionalText("document", CatalogueValidator.MAX_DOCUMENT_BYTES)
        val lastError = o.optionalText("error", MAX_ERROR)
        val refreshed = if (!o.has("refreshed") || o.isNull("refreshed")) null else {
            val value = o.opt("refreshed")
            require(value is Int || value is Long) { "refreshed" }
            (value as Number).toLong()
        }
        return CatalogueSourceRecord(url, name, document, lastError, refreshed)
    }

    private fun JSONObject.optionalText(key: String, max: Int): String? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key) as? String ?: error(key)
        require(value.length <= max) { key }
        return value
    }
}

/** Fetches one catalogue document. Production uses HTTPS; tests use a fake. */
interface CatalogueTransport {
    /** Returns the response body, or a failure. Implementations must enforce [maxBytes] and never fall back to cleartext. */
    fun fetch(url: String, maxBytes: Int): Result<String>
}

/** Where a redirect may lead: only to another https URL, never to cleartext or another scheme. */
object RedirectPolicy {
    const val MAX_REDIRECTS = 3

    fun resolve(current: String, location: String?): Result<String> = runCatching {
        require(!location.isNullOrBlank()) { "The source redirected without a location" }
        CatalogueValidator.validateUrl(URI(current).resolve(location.trim()).toString()).getOrThrow()
    }
}

/** One explicit user action covers one refresh; a screen redraw cannot replay a consumed request. */
typealias CatalogueRefreshGate = PluginUpdateCheckGate

/** The result of refreshing one source. [error] is a safe, user-showable message. */
data class SourceRefreshOutcome(val url: String, val error: String?, val entryCount: Int? = null, val skippedEntries: Int? = null)

/** A source together with its parsed last-good document, or null when it has none (or the stored copy no longer validates). */
data class CatalogueListing(val source: CatalogueSourceRecord, val document: CatalogueDocument?)

/**
 * The user's catalogue sources and their refreshes (Stage 9.7c, decision D6). None exist by default. Adding or
 * removing a source never touches the network; only [refreshAll], called for an explicit user action, does.
 * A failed or invalid refresh keeps the previous good listing and records a safe error. Nothing here installs,
 * imports, trusts or enables a plugin.
 */
class CatalogueSources(
    private val storage: CatalogueSourceStorage,
    private val transport: CatalogueTransport,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {}
) {
    companion object { const val MAX_SOURCES = 8 }

    @Synchronized fun sources(): List<CatalogueSourceRecord> = load()

    /** Adds a source after validating its URL. Does not contact it. */
    @Synchronized fun add(rawUrl: String): Result<CatalogueSourceRecord> {
        val url = CatalogueValidator.validateUrl(rawUrl).getOrElse { return Result.failure(it) }
        val current = load()
        if (current.any { it.url.equals(url, ignoreCase = true) }) return Result.failure(IllegalStateException("That source is already added"))
        if (current.size >= MAX_SOURCES) return Result.failure(IllegalStateException("You can add at most $MAX_SOURCES sources"))
        val record = CatalogueSourceRecord(url)
        save(current + record)
        log("Added catalogue source ${hostOf(url)}")
        return Result.success(record)
    }

    /** Removes a source and its cached listing. Installed plugins are not affected. */
    @Synchronized fun remove(url: String): Boolean {
        val current = load()
        val remaining = current.filterNot { it.url.equals(url, ignoreCase = true) }
        if (remaining.size == current.size) return false
        save(remaining)
        log("Removed catalogue source ${hostOf(url)}")
        return true
    }

    /** Parsed last-good listings, re-validated on every read. */
    @Synchronized fun listings(): List<CatalogueListing> =
        load().map { source -> CatalogueListing(source, source.document?.let { CatalogueParser.parse(it).getOrNull() }) }

    /**
     * Fetches every source once. Call only for an explicit user action and off the main thread. The lock is not
     * held during network access, and a source removed meanwhile is not brought back.
     */
    fun refreshAll(): List<SourceRefreshOutcome> = sources().map { refresh(it.url) }

    private fun refresh(url: String): SourceRefreshOutcome {
        val fetched = transport.fetch(url, CatalogueValidator.MAX_DOCUMENT_BYTES)
        val body = fetched.getOrNull()
        if (body == null) {
            log("Catalogue refresh failed for ${hostOf(url)}: ${fetched.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"}")
            return applyFailure(url, "Could not reach the source")
        }
        val parsed = CatalogueParser.parse(body)
        val document = parsed.getOrNull()
        if (document == null) {
            val reason = (parsed.exceptionOrNull() as? IllegalArgumentException)?.message ?: "The document is invalid"
            log("Catalogue refresh rejected for ${hostOf(url)}")
            return applyFailure(url, "Not a usable catalogue: $reason")
        }
        return applySuccess(url, document, body)
    }

    @Synchronized private fun applyFailure(url: String, message: String): SourceRefreshOutcome {
        update(url) { it.copy(lastError = message.take(CatalogueSourceCodec.MAX_ERROR)) }
        return SourceRefreshOutcome(url, message)
    }

    @Synchronized private fun applySuccess(url: String, document: CatalogueDocument, raw: String): SourceRefreshOutcome {
        val applied = update(url) { it.copy(name = document.name, document = raw, lastError = null, lastRefreshedMillis = now()) }
        if (applied) log("Refreshed catalogue source ${hostOf(url)}: ${document.entries.size} entries, ${document.skippedEntries} skipped")
        return SourceRefreshOutcome(url, null, document.entries.size, document.skippedEntries)
    }

    /** Applies [change] to the stored record for [url]; false (and nothing written) if the source no longer exists. */
    private fun update(url: String, change: (CatalogueSourceRecord) -> CatalogueSourceRecord): Boolean {
        val current = load()
        if (current.none { it.url.equals(url, ignoreCase = true) }) return false
        save(current.map { if (it.url.equals(url, ignoreCase = true)) change(it) else it })
        return true
    }

    private fun load(): List<CatalogueSourceRecord> {
        val stored = storage.readCatalogueSources() ?: return emptyList()
        val decoded = CatalogueSourceCodec.decode(stored)
        if (decoded == null) {
            reportDamage("Discarded unreadable catalogue sources")
            return emptyList()
        }
        if (decoded.dropped > 0) reportDamage("Dropped ${decoded.dropped} invalid catalogue source record(s)")
        return decoded.records
    }

    /** Reads happen on every redraw, so damaged stored data is reported to Activity once per process, not per read. */
    private var damageReported = false
    private fun reportDamage(message: String) {
        if (damageReported) return
        damageReported = true
        log(message)
    }

    private fun save(records: List<CatalogueSourceRecord>) = storage.writeCatalogueSources(CatalogueSourceCodec.encode(records))

    /** Only the host goes to Activity; paths and queries could carry identifiers. */
    private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { "unknown host" }
}
