package com.oneai.app.plugins

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import org.xmlpull.v1.XmlPullParser
import java.security.MessageDigest

/** Android implementation of [PackageSignatureSource] (design §3: pin checked with hasSigningCertificate on API 28+). */
class AndroidPackageSignatureSource(private val packageManager: PackageManager) : PackageSignatureSource {
    @Suppress("DEPRECATION")
    override fun currentSigners(packageName: String): List<String>? = try {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                .signatures?.map { it.toByteArray() }.orEmpty()
        }
        signatures.map { sha256Hex(it) }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    override fun hasSigner(packageName: String, sha256: String): Boolean? {
        val current = currentSigners(packageName) ?: return null
        if (sha256 in current) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val digest = hexToBytes(sha256) ?: return false
        // Also true for a certificate the app was signed with before a key rotation.
        return try {
            packageManager.hasSigningCertificate(packageName, digest, PackageManager.CERT_INPUT_SHA256)
        } catch (e: Exception) {
            false
        }
    }

    private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray? =
        if (hex.length % 2 != 0 || !hex.all { it in '0'..'9' || it in 'a'..'f' }) null
        else ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** SharedPreferences persistence for trust pins. Corrupt entries are ignored (treated as not imported). */
class SharedPrefsTrustStore(context: Context) : TrustStore {
    private val prefs = context.applicationContext.getSharedPreferences("one_ai_plugin_trust", Context.MODE_PRIVATE)

    override fun get(packageName: String): TrustEntry? =
        prefs.getString(KEY + packageName, null)?.let(TrustEntryCodec::decode)

    override fun put(entry: TrustEntry) {
        prefs.edit().putString(KEY + entry.packageName, TrustEntryCodec.encode(entry)).apply()
    }

    override fun remove(packageName: String) {
        prefs.edit().remove(KEY + packageName).apply()
    }

    override fun all(): List<TrustEntry> =
        prefs.all.filterKeys { it.startsWith(KEY) }.values.mapNotNull { (it as? String)?.let(TrustEntryCodec::decode) }

    private companion object { const val KEY = "entry:" }
}

/** Lists installed plugin APKs and reads their declarative manifest without running any plugin code. */
class PluginDiscovery(private val context: Context) {
    fun discover(): List<DiscoveredPlugin> {
        val pm = context.packageManager
        val intent = Intent(PluginApi.SERVICE_ACTION)
        @Suppress("DEPRECATION")
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }
        return resolved.mapNotNull { it.serviceInfo }
            .filter { it.exported && it.permission == null && it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .take(MAX_DISCOVERED)
            .map { service ->
                DiscoveredPlugin(
                    packageName = service.packageName,
                    appLabel = service.applicationInfo?.loadLabel(pm)?.toString().orEmpty().take(60),
                    serviceClass = service.name,
                    manifest = readManifest(pm, service)
                )
            }
    }

    private fun readManifest(pm: PackageManager, service: ServiceInfo): Result<ExternalPluginManifest> = runCatching {
        val resourceId = service.metaData?.getInt(PluginApi.MANIFEST_META_DATA, 0) ?: 0
        require(resourceId != 0) { "The plugin does not declare a manifest" }
        val info = pm.getPackageInfo(service.packageName, 0)
        val parser = pm.getResourcesForApplication(service.applicationInfo).getXml(resourceId)
        val root = try { XmlTreeBuilder.build(parser) } finally { parser.close() }
        ExternalManifestParser.parse(root, service.packageName, info.versionName.orEmpty(), versionCodeOf(info)).getOrThrow()
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private companion object { const val MAX_DISCOVERED = 50 }
}

/** Builds the Android-free [XmlNode] tree from a plugin's compiled XML resource, with hard size limits. */
object XmlTreeBuilder {
    private const val MAX_NODES = 300
    private const val MAX_DEPTH = 4

    fun build(parser: XmlPullParser): XmlNode {
        var event = parser.eventType
        while (event != XmlPullParser.START_TAG) {
            require(event != XmlPullParser.END_DOCUMENT) { "The manifest is empty" }
            event = parser.next()
        }
        var count = 0
        fun read(depth: Int): XmlNode {
            require(depth <= MAX_DEPTH) { "The manifest is nested too deeply" }
            require(++count <= MAX_NODES) { "The manifest is too large" }
            val name = parser.name
            val attributes = (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
            val children = mutableListOf<XmlNode>()
            while (true) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> children += read(depth + 1)
                    XmlPullParser.END_TAG -> return XmlNode(name, attributes, children)
                    XmlPullParser.END_DOCUMENT -> throw IllegalArgumentException("The manifest is malformed")
                    else -> Unit
                }
            }
        }
        return read(1)
    }
}
