package com.oneai.app.plugins

import com.oneai.app.bubble.BubbleHostApprovalPresenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the in-app plugin screen asks the user to confirm. Text is host-written or cleaned plugin text. */
data class InAppLaunchPrompt(val title: String, val details: String, val warning: String)

/** The outcome shown after the user answered. Host-written; never plugin text. */
data class InAppLaunchResult(val title: String, val message: String)

/**
 * Slice C4 (decision D12 / D-AB): the in-app counterpart of the Bubble's approval surface for a plugin-requested
 * screen (API level 2). It implements the same [BubbleHostApprovalPresenter] contract, so the host launch policy
 * (identity checks, trust and Enabled re-check, one-use consumption, sending the PendingIntent) is the single
 * existing implementation and is not duplicated here. This class only holds what the Compose screen draws.
 *
 * Pure Kotlin, no Android. Nothing is sent from here: [approve] runs the host's approve action, which does the
 * checks. An unanswered request is rejected when the screen closes, so it can never surprise the user later.
 */
class InAppLaunchPresenter : BubbleHostApprovalPresenter {
    private class Pending(val approve: () -> String, val reject: () -> Unit)

    private val lock = Any()
    private var pending: Pending? = null
    private val promptState = MutableStateFlow<InAppLaunchPrompt?>(null)
    private val resultState = MutableStateFlow<InAppLaunchResult?>(null)

    val prompt: StateFlow<InAppLaunchPrompt?> get() = promptState
    val result: StateFlow<InAppLaunchResult?> get() = resultState

    override fun showApproval(title: String, details: String, warning: String, approve: () -> String, reject: () -> Unit) {
        val replaced = synchronized(lock) {
            val old = pending
            pending = Pending(approve, reject)
            old
        }
        // A newer request replaces an unanswered one; the old one is rejected so the broker is never left holding it.
        replaced?.reject?.invoke()
        resultState.value = null
        promptState.value = InAppLaunchPrompt(
            title = CatalogueView.clean(title, MAX_TITLE),
            details = details.split('\n').joinToString("\n") { CatalogueView.clean(it, MAX_LINE) },
            warning = CatalogueView.clean(warning, MAX_WARNING)
        )
    }

    override fun showResult(title: String, message: String) {
        synchronized(lock) { pending = null }
        promptState.value = null
        resultState.value = InAppLaunchResult(CatalogueView.clean(title, MAX_TITLE), CatalogueView.clean(message, MAX_WARNING))
    }

    /** The user pressed the confirm button for the request shown. */
    fun approve() {
        val taken = take() ?: return
        promptState.value = null
        val message = try {
            taken.approve()
        } catch (error: Exception) {
            FAILED
        }
        resultState.value = InAppLaunchResult(RESULT_TITLE, CatalogueView.clean(message, MAX_WARNING).ifBlank { FAILED })
    }

    /** The user rejected the request, or dismissed the dialog. */
    fun reject() {
        val taken = take() ?: return
        promptState.value = null
        taken.reject()
    }

    fun dismissResult() { resultState.value = null }

    /** The screen is closing: an unanswered request is rejected and nothing stays on screen. */
    fun close() {
        reject()
        resultState.value = null
    }

    private fun take(): Pending? = synchronized(lock) { pending.also { pending = null } }

    companion object {
        const val RESULT_TITLE = "Plugin screen"
        const val FAILED = "The plugin screen could not be opened."
        private const val MAX_TITLE = 60
        private const val MAX_LINE = 200
        private const val MAX_WARNING = 300
    }
}

/** Which surface presents a plugin-requested screen. Pure, so the routing rule is covered by JVM tests. */
object PluginLaunchRouting {
    /**
     * The in-app surface handles a request only while that plugin's own screen is open; every other request goes to the
     * Bubble surface (when one is attached) exactly as before. Null means nobody can present it yet and it stays queued.
     */
    fun <T : Any> select(requestPackage: String, bubble: T?, inApp: T?, inAppPluginId: String?): T? =
        if (inApp != null && inAppPluginId != null && requestPackage == inAppPluginId) inApp else bubble
}
