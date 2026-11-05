package com.oneai.app.plugins

import android.app.ActivityOptions
import android.app.Application
import android.app.PendingIntent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.oneai.app.bubble.BubbleHostApprovalPresenter
import com.oneai.app.core.OneAiStore

/**
 * Android host policy for plugin-requested screens. Kept outside the generic Bubble shell:
 * the Bubble only renders a generic approval request and its result.
 */
class PluginLaunchApprovalController(
    private val app: Application,
    private val broker: PluginLaunchApprovalBroker,
    private val trust: PluginTrustRegistry,
    private val store: OneAiStore
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var presenter: BubbleHostApprovalPresenter? = null

    private val observer: (PluginLaunchRequest?) -> Unit = { request ->
        main.post {
            val ui = presenter ?: return@post
            if (request == null) return@post
            ui.showApproval(
                title = "PLUGIN SCREEN REQUEST",
                details = "Package: ${request.pluginPackage}\nRequested screen: ${request.label}",
                warning = "This description comes from the plugin and may be misleading. Continue only if you expected this screen.",
                approve = { approve(request) },
                reject = {
                    broker.reject(request.requestId)
                }
            )
        }
    }

    fun attach(ui: BubbleHostApprovalPresenter) {
        presenter = ui
        broker.setObserver(observer)
    }

    /** Re-displays an unhandled request when the user reopens the Bubble after closing its panel. */
    fun presentPending(): Boolean {
        val request = broker.current() ?: return false
        observer(request)
        return true
    }

    fun detach() {
        presenter = null
        broker.setObserver(null)
    }

    private fun approve(request: PluginLaunchRequest): String {
        val payload = broker.approve(request.requestId) { packageName ->
            val stillTrusted = trust.check(packageName) is TrustCheck.Trusted
            val stillEnabled = store.plugins().any { it.id == packageName && it.enabled }
            stillTrusted && stillEnabled
        }
        val pendingIntent = payload as? PendingIntent
        if (pendingIntent == null) {
            store.log("Plugin screen request from ${request.pluginPackage}: blocked by trust/enabled/payload check")
            return "ONE AI could not verify that this plugin is still trusted and enabled. The request was discarded."
        }
        val result = runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                val options = ActivityOptions.makeBasic()
                val mode = if (Build.VERSION.SDK_INT >= 36) {
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                } else {
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
                options.setPendingIntentBackgroundActivityStartMode(mode)
                pendingIntent.send(app, 0, null, null, null, null, options.toBundle())
            } else {
                pendingIntent.send()
            }
            "The approved plugin screen launch was sent."
        }.getOrElse { error ->
            when (error) {
                is PendingIntent.CanceledException -> "The plugin screen request expired before it could open."
                is SecurityException -> "Android blocked this plugin screen launch."
                else -> "The plugin screen could not be opened."
            }
        }
        store.log("Plugin screen request from ${request.pluginPackage}: ${if (result.startsWith("The approved")) "sent" else "failed"}")
        return result
    }
}
