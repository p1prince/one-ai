package com.oneai.app.plugins

import org.json.JSONArray
import org.json.JSONObject

/** JSON form of trust entries for persistence. Decoding re-validates everything, so stored data is never trusted blindly. */
object TrustEntryCodec {
    fun encode(entry: TrustEntry): String = JSONObject().apply {
        put("package", entry.packageName)
        put("certificate", entry.certificateSha256)
        put("manifest", manifestToJson(entry.trustedManifest))
        entry.pendingUpdate?.let { pending ->
            put("pending", JSONObject().put("manifest", manifestToJson(pending.manifest)).put("reasons", JSONArray(pending.reasons)))
        }
    }.toString()

    /** Null when the data is corrupt or no longer valid; callers then treat the plugin as not imported. */
    fun decode(json: String): TrustEntry? = runCatching {
        val root = JSONObject(json)
        val manifest = manifestFromJson(root.getJSONObject("manifest"))
        val packageName = root.getString("package")
        val certificate = CertificateFingerprint.normalize(root.getString("certificate")) ?: return null
        if (packageName != manifest.packageName) return null
        val pending = root.optJSONObject("pending")?.let { p ->
            val reasons = p.optJSONArray("reasons")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            PendingUpdate(manifestFromJson(p.getJSONObject("manifest")), reasons)
        }
        if (pending != null && pending.manifest.packageName != packageName) return null
        TrustEntry(packageName, certificate, manifest, pending)
    }.getOrNull()

    private fun manifestToJson(m: ExternalPluginManifest) = JSONObject().apply {
        put("manifestVersion", m.manifestVersion)
        put("package", m.packageName)
        put("versionName", m.versionName)
        put("versionCode", m.versionCode)
        put("name", m.name)
        put("description", m.description)
        put("minCoreApi", m.minCoreApi)
        put("targetCoreApi", m.targetCoreApi)
        put("capabilities", JSONArray(m.capabilities))
        put("commands", JSONArray().apply {
            m.commands.forEach { c ->
                put(JSONObject().put("name", c.name).put("title", c.title).put("description", c.description).put("capability", c.capability))
            }
        })
        m.bubble?.let { put("bubble", JSONObject().put("service", it.service).put("title", it.title)) }
        put("dependencies", JSONArray(m.dependencies))
    }

    private fun strings(array: JSONArray?) = array?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

    private fun manifestFromJson(o: JSONObject): ExternalPluginManifest = ExternalManifestValidator.validate(
        ExternalPluginManifest(
            manifestVersion = o.getInt("manifestVersion"),
            packageName = o.getString("package"),
            versionName = o.getString("versionName"),
            versionCode = o.getLong("versionCode"),
            name = o.getString("name"),
            description = o.optString("description", ""),
            minCoreApi = o.getInt("minCoreApi"),
            targetCoreApi = o.getInt("targetCoreApi"),
            capabilities = strings(o.optJSONArray("capabilities")),
            commands = o.optJSONArray("commands")?.let { a ->
                (0 until a.length()).map { i ->
                    val c = a.getJSONObject(i)
                    ExternalCommandDeclaration(c.getString("name"), c.getString("title"), c.optString("description", ""), c.getString("capability"))
                }
            }.orEmpty(),
            bubble = o.optJSONObject("bubble")?.let { ExternalBubbleDeclaration(it.getString("service"), it.getString("title")) },
            dependencies = strings(o.optJSONArray("dependencies"))
        )
    ).getOrThrow()
}
