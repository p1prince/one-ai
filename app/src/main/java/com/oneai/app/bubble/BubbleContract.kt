package com.oneai.app.bubble

import android.content.Context
import com.oneai.app.core.PluginActionInput
import com.oneai.app.core.PluginActionOutput
import com.oneai.app.core.PluginInvocationResult

/**
 * Generic contract between the Floating Bubble and whatever contributes services to it.
 * The Bubble knows nothing about specific capabilities: it renders declarative views, runs the
 * single shared approval flow and shows results. Contributors supply everything else.
 */

/** Services the Bubble offers its contributor, such as the Android context. */
interface BubbleHost {
    val context: Context
    /** Removes the bubble icon while an external flow (for example a system prompt) runs. */
    fun hideBubble()
}

data class BubbleApproval(
    val title: String,
    val message: String,
    /** Optional exact detail the user should review, such as a search query. */
    val review: String? = null,
    val confirmLabel: String
)

data class BubbleMessage(val title: String, val content: String)

data class BubbleHostActionRequest(val pluginId: String, val actionId: String, val capability: String, val description: String)

/** How a plugin call's outcomes are titled. Returning null from [successTitle] shows nothing. */
data class BubbleOutcomes(
    val unavailableTitle: String,
    val blockedTitle: String,
    val failedTitle: String,
    val successTitle: (PluginActionOutput) -> String? = { it.title }
) {
    fun present(result: PluginInvocationResult): BubbleMessage? = when (result) {
        is PluginInvocationResult.Completed -> successTitle(result.output)?.let { BubbleMessage(it, result.output.content) }
        is PluginInvocationResult.Rejected -> BubbleMessage(blockedTitle, result.reason)
        is PluginInvocationResult.Failed -> BubbleMessage(failedTitle, result.message)
        is PluginInvocationResult.AwaitingApproval -> BubbleMessage("Approval expired", "A fresh approval is required.")
    }
}

sealed interface BubbleAction {
    /** Shows a message immediately, for example when input validation fails. */
    data class Message(val title: String, val content: String) : BubbleAction

    /**
     * Runs a plugin action through the plugin runtime. Sensitive actions show the approval panel;
     * only a confirmed approval produces the one-use ticket the runtime requires.
     * [backTo] is the service view shown on Cancel (null returns to the service list).
     */
    data class PluginCall(
        val pluginId: String,
        val input: PluginActionInput,
        val approval: BubbleApproval,
        val outcomes: BubbleOutcomes,
        val backTo: String? = null
    ) : BubbleAction

    /** An action the gateway gates but the contributor performs itself after approval. */
    data class GatedHostAction(
        val request: BubbleHostActionRequest,
        val approval: BubbleApproval,
        val blockedTitle: String,
        val onApproved: () -> Unit
    ) : BubbleAction
}

sealed interface BubbleElement {
    data class Label(val text: String, val size: Float = 14f) : BubbleElement
    data class TextInput(val key: String, val hint: String) : BubbleElement
    /** [onPress] receives the current text-input values by key and may return an action to perform. */
    data class Button(val label: String, val onPress: (Map<String, String>) -> BubbleAction?) : BubbleElement
}

data class BubbleView(val elements: List<BubbleElement>)

/** Offered when a result carries a private image the user may choose to act on. */
data class BubbleFollowUp(val note: String, val label: String, val action: BubbleAction)

/** Contributes one optional plugin service; core Command Manager is rendered by the shell itself. */
interface BubbleServiceProvider {
    val serviceId: String
    val pluginId: String
    val title: String

    fun view(host: BubbleHost): BubbleView

    /** True when the view must be fetched off the main thread (for example from another app). */
    val isAsync: Boolean get() = false

    /** Used instead of [view] when [isAsync]; may suspend and may fail. */
    suspend fun loadView(host: BubbleHost): BubbleView = view(host)

    /** Prepares the plugin runtime before an action (for example registering its adapter). */
    fun ensureReady(host: BubbleHost): Result<Unit> = Result.success(Unit)

    /**
     * Called when the Bubble service stops. A contributor releases any host-scoped resources it owns here.
     */
    fun releaseReady() {}

    fun followUp(imagePath: String, host: BubbleHost): BubbleFollowUp? = null

    /** Called when the Bubble service stops, so contributors can release pending work. */
    fun onHostStopped() {}
}

/** Process-wide set of Bubble contributors, filled by the application composition root. */
class BubbleProviderRegistry {
    private val providers = linkedMapOf<String, BubbleServiceProvider>()

    @Synchronized
    fun register(provider: BubbleServiceProvider): Result<Unit> {
        if (!SERVICE_ID.matches(provider.serviceId)) {
            return Result.failure(IllegalArgumentException("Bubble service IDs use lowercase letters, digits and underscores."))
        }
        if (provider.serviceId == BubbleUiPolicy.COMMAND_MANAGER) {
            return Result.failure(IllegalArgumentException("The core command service ID is reserved."))
        }
        if (provider.pluginId.isBlank()) return Result.failure(IllegalArgumentException("A provider must belong to a plugin."))
        if (provider.serviceId in providers) return Result.failure(IllegalStateException("Service already registered: ${provider.serviceId}"))
        if (providers.values.any { it.pluginId == provider.pluginId }) {
            return Result.failure(IllegalStateException("Plugin already has a Bubble service: ${provider.pluginId}"))
        }
        providers[provider.serviceId] = provider
        return Result.success(Unit)
    }

    @Synchronized fun provider(serviceId: String): BubbleServiceProvider? = providers[serviceId]
    @Synchronized fun providers(): List<BubbleServiceProvider> = providers.values.toList()
    /** Plugin ID to Bubble service ID for every registered contributor. */
    @Synchronized fun bindings(): Map<String, String> = providers.values.associate { it.pluginId to it.serviceId }
    @Synchronized fun unregisterPlugin(pluginId: String): Boolean = providers.values.removeAll { it.pluginId == pluginId }
    @Synchronized fun clear() { providers.clear() }

    private companion object { val SERVICE_ID = Regex("[a-z][a-z0-9_]{0,39}") }
}

/** The application-wide registry. Empty until the app (or, later, plugin discovery) fills it. */
object BubbleProviders { val registry = BubbleProviderRegistry() }
