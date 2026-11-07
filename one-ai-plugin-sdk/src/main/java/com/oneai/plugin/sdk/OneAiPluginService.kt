package com.oneai.plugin.sdk

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import java.security.MessageDigest

/**
 * Base class for a ONE AI plugin. Declare a subclass as an exported service with the intent action
 * `com.oneai.plugin.action.PLUGIN` and implement [onCommand]. Everything security-relevant is done
 * here: only ONE AI may call, input and output are bounded, requests have a deadline, only one runs
 * at a time, and failures are reported without internal detail.
 */
abstract class OneAiPluginService : Service() {

    /** Runs on a worker thread. Throw [PluginException] for a clean failure. Keep it cancellable (respond to interrupts). */
    protected abstract fun onCommand(command: String, args: Map<String, String>, context: CallContext): PluginOutput

    /** Declarative Bubble view (JSON) for a service declared in the manifest; null = none. Must be fast. */
    protected open fun onView(serviceId: String): String? = null

    /**
     * Who may call this plugin. The default trusts only ONE AI's release certificates (currently an
     * empty list, so it fails closed). Development builds may return
     * `HostVerifier(allowAnyCertificate = true)`; never ship that in a release.
     */
    protected open fun hostVerifier(): HostVerifier = HostVerifier()

    protected open val commandTimeoutMs: Long get() = PluginBounds.DEFAULT_TIMEOUT_MS

    private val configHolder = PluginConfigHolder()

    /**
     * The user's settings, as last delivered by ONE AI (plugin API level 3). Read it inside [onCommand] each time;
     * it is [PluginConfig.EMPTY] until the host delivers a snapshot. A plugin that declares `<settings>` also
     * declares `minCoreApi="3"` in its manifest.
     */
    protected val config: PluginConfig get() = configHolder.current

    private val verifier by lazy { hostVerifier() }
    private val runner by lazy { PluginCallRunner(timeoutMs = commandTimeoutMs, handler = ::onCommand) }

    private val binder = object : IOneAiPlugin.Stub() {
        override fun describe(): String {
            enforceHost()
            return """{"sdkApi":${PluginBounds.SDK_API}}"""
        }

        override fun execute(requestId: String?, commandName: String?, argKeys: Array<String>?, argValues: Array<String>?, callback: IPluginCallback?) {
            if (callback == null || !isHostCaller()) return
            runner.execute(requestId, commandName, argKeys?.toList(), argValues?.toList(), AidlSink(callback))
        }

        override fun view(serviceId: String?): String? {
            enforceHost()
            if (serviceId == null || !PluginBounds.isValidCommandName(serviceId)) return null
            return runCatching { onView(serviceId) }.getOrNull()?.takeIf { it.length <= PluginBounds.MAX_VIEW_JSON }
        }

        override fun cancel(requestId: String?) {
            if (isHostCaller()) runner.cancel(requestId)
        }

        override fun applyConfig(schemaVersion: Int, keys: Array<String>?, values: Array<String>?) {
            if (!isHostCaller()) return
            // Rejected whole when out of bounds; the previous snapshot stays.
            configHolder.apply(schemaVersion, keys?.toList(), values?.toList())
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runner.shutdown()
        super.onDestroy()
    }

    private fun enforceHost() {
        if (!isHostCaller()) throw SecurityException("Only ONE AI may call this plugin")
    }

    private fun isHostCaller(): Boolean = try {
        val packages = packageManager.getPackagesForUid(Binder.getCallingUid())?.toList().orEmpty()
        verifier.isHost(packages, ::signingCertificates)
    } catch (e: Exception) {
        false // fail closed
    }

    @Suppress("DEPRECATION")
    private fun signingCertificates(packageName: String): Set<String> = try {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                .signatures?.map { it.toByteArray() }.orEmpty()
        }
        signatures.map { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }.toSet()
    } catch (e: PackageManager.NameNotFoundException) {
        emptySet()
    }

    private class AidlSink(private val callback: IPluginCallback) : ReplySink {
        override fun result(requestId: String, title: String, content: String) = send { callback.onResult(requestId, title, content) }
        override fun error(requestId: String, code: String, message: String) =
            send { callback.onError(requestId, code, PluginBounds.bound(message, PluginBounds.MAX_ERROR_MESSAGE)) }
        override fun activity(requestId: String, message: String) = send { callback.onActivity(requestId, message) }
        override fun launch(requestId: String, label: String, payload: Any) {
            // Anything that is not a PendingIntent is dropped; the command's result is still delivered.
            (payload as? PendingIntent)?.let { screen -> send { callback.onLaunch(requestId, label, screen) } }
        }
        private fun send(block: () -> Unit) { try { block() } catch (e: android.os.RemoteException) { /* host went away */ } }
    }
}

/**
 * Asks ONE AI to open a screen for the user after they agree. Return it in [PluginOutput.launch]. The
 * [pendingIntent] must be created by this plugin (use `FLAG_IMMUTABLE`); ONE AI refuses one created by anyone
 * else. [label] says what will open ("Open Calculator"); ONE AI shows it as the plugin's own claim.
 * Requires `minCoreApi="2"` in the plugin manifest.
 */
fun screenLaunch(label: String, pendingIntent: PendingIntent) = PluginLaunch(label, pendingIntent)
