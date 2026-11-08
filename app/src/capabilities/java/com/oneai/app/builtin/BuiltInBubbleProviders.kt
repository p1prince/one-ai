package com.oneai.app.builtin

import com.oneai.app.ai.AiOrchestrator
import com.oneai.app.ai.AiProviders
import com.oneai.app.ai.BraveSearchProvider
import com.oneai.app.ai.OpenAiCompatibleVisionProvider
import com.oneai.app.ai.RetryingAiProvider
import com.oneai.app.ai.WebSearchPolicy
import com.oneai.app.bubble.BubbleAction
import com.oneai.app.bubble.BubbleApproval
import com.oneai.app.bubble.BubbleElement
import com.oneai.app.bubble.BubbleFollowUp
import com.oneai.app.bubble.BubbleHost
import com.oneai.app.bubble.BubbleOutcomes
import com.oneai.app.bubble.BubbleProviderRegistry
import com.oneai.app.bubble.BubbleView
import com.oneai.app.bubble.ScreenCaptureConsentActivity
import com.oneai.app.core.OneAiStore
import com.oneai.app.plugins.AndroidAutoDroidLauncher
import com.oneai.app.plugins.AutoDroidAdapter
import com.oneai.app.plugins.AutoDroidIntegrationPolicy
import com.oneai.app.plugins.PluginActionInput
import com.oneai.app.plugins.PluginAdapterRegistrar
import com.oneai.app.plugins.ScreenIntelligenceAdapter
import com.oneai.app.plugins.VoiceInputProvider
import com.oneai.app.plugins.VoiceInterfaceAdapter
import com.oneai.app.plugins.VoiceOutputProvider
import com.oneai.app.plugins.WebResearchAdapter
import com.oneai.app.security.SecretVault

/**
 * INTERIM. The capability UIs that ship inside the app, expressed through the generic Bubble
 * contract. They live outside the Bubble shell and are slated to move into separately installed
 * plugins (Stages 8-9). Nothing in the Bubble shell references this package.
 */
object BuiltInBubbleProviders {
    /** Plugin ID to Bubble service ID, kept in step with the built-in catalogue by a unit test. */
    val bindings: Map<String, String> = mapOf(
        WebResearchAdapter.PLUGIN_ID to WebResearchBubbleProvider.SERVICE_ID,
        ScreenIntelligenceAdapter.PLUGIN_ID to ScreenIntelligenceBubbleProvider.SERVICE_ID,
        VoiceInterfaceAdapter.PLUGIN_ID to VoiceBubbleProvider.SERVICE_ID,
        AutoDroidAdapter.PLUGIN_ID to AutoDroidBubbleProvider.SERVICE_ID
    )

    fun registerAll(registry: BubbleProviderRegistry, runtime: PluginAdapterRegistrar) {
        listOf(
            WebResearchBubbleProvider(runtime), ScreenIntelligenceBubbleProvider(runtime), VoiceBubbleProvider(runtime),
            AutoDroidBubbleProvider(runtime)
        ).forEach { registry.register(it) }
    }
}

class WebResearchBubbleProvider(runtime: PluginAdapterRegistrar) : BuiltInBubbleProvider(runtime) {
    override val serviceId = SERVICE_ID
    override val pluginId = WebResearchAdapter.PLUGIN_ID
    override val title = "Web research"

    override fun view(host: BubbleHost) = BubbleView(listOf(
        BubbleElement.Label("Search the web using your separately encrypted Brave Search credential. Each search requires your approval."),
        BubbleElement.TextInput("query", "What would you like to search for?"),
        BubbleElement.Button("Search the web") { values -> search(values["query"].orEmpty()) }
    ))

    private fun search(query: String): BubbleAction {
        val normalized = WebSearchPolicy.normalizeQuery(query)
            ?: return BubbleAction.Message("Search query needed", "Enter a search query between 2 and 300 characters.")
        return BubbleAction.PluginCall(
            pluginId = pluginId,
            input = PluginActionInput(WebResearchAdapter.SEARCH_ACTION, "internet", "Search the web for: $normalized", mapOf("query" to normalized)),
            approval = BubbleApproval(
                "ALLOW THIS WEB SEARCH ONCE?",
                "ONE AI will send this query to Brave Search over HTTPS. Search terms may be processed by that provider. Review the query before approving.",
                normalized, "Allow once and search"
            ),
            outcomes = BubbleOutcomes("Web Research unavailable", "Search blocked", "Search failed") { "Web search results" }
        )
    }

    override fun ensureReady(host: BubbleHost) = ensureRegistered(pluginId) { WebResearchAdapter(BraveSearchProvider(SecretVault(host.context))) }

    companion object { const val SERVICE_ID = "web_research" }
}

class ScreenIntelligenceBubbleProvider(runtime: PluginAdapterRegistrar) : BuiltInBubbleProvider(runtime) {
    override val serviceId = SERVICE_ID
    override val pluginId = ScreenIntelligenceAdapter.PLUGIN_ID
    override val title = "Screen intelligence"

    override fun view(host: BubbleHost) = BubbleView(listOf(
        BubbleElement.Label("Capture requires Android's system permission prompt. The screenshot and region selector open outside the Bubble; only the selected image reference returns here."),
        BubbleElement.Button("Capture screen region") { _ ->
            BubbleAction.GatedHostAction(
                request = com.oneai.app.bubble.BubbleHostActionRequest(
                    pluginId = pluginId,
                    actionId = "capture_region",
                    capability = "screen_capture",
                    description = "Capture one screen frame and let me select a region"
                ),
                approval = BubbleApproval(
                    "ALLOW SCREEN CAPTURE ONCE?",
                    "ONE AI will capture one screen frame so you can select a region. The image stays in private app storage. Android will then show its own screen-sharing consent prompt.",
                    null, "Allow once and continue"
                ),
                blockedTitle = "Screen capture blocked",
                onApproved = {
                    host.hideBubble()
                    ScreenCaptureConsentActivity.launch(host.context)
                }
            )
        }
    ))

    /** A second, separate approval is required before the selected image is sent online. */
    override fun followUp(imagePath: String, host: BubbleHost) = BubbleFollowUp(
        note = "The selected image stays private until you explicitly approve sending it to your configured AI provider.",
        label = "Analyze selected region",
        action = BubbleAction.PluginCall(
            pluginId = pluginId,
            input = PluginActionInput(
                ScreenIntelligenceAdapter.ANALYZE_ACTION, "internet",
                "Send the selected screen region to the configured AI provider for analysis",
                mapOf("image_path" to imagePath)
            ),
            approval = BubbleApproval(
                "SEND SELECTED IMAGE FOR ANALYSIS?",
                "This sends the selected screen region to your configured AI provider over HTTPS. Continue only if the image contains no information you want to keep private.",
                null, "Allow once and analyze"
            ),
            outcomes = BubbleOutcomes("Screen Intelligence unavailable", "Analysis blocked", "Analysis failed") { "Screen analysis complete" }
        )
    )

    override fun ensureReady(host: BubbleHost) = ensureRegistered(pluginId) {
        val store = OneAiStore(host.context)
        ScreenIntelligenceAdapter(host.context, OpenAiCompatibleVisionProvider(store, SecretVault(host.context)))
    }

    companion object { const val SERVICE_ID = "screen_intelligence" }
}

class VoiceBubbleProvider(runtime: PluginAdapterRegistrar) : BuiltInBubbleProvider(runtime) {
    override val serviceId = SERVICE_ID
    override val pluginId = VoiceInterfaceAdapter.PLUGIN_ID
    override val title = "Voice interface"

    override fun view(host: BubbleHost) = BubbleView(listOf(
        BubbleElement.Label("Speak one command and hear ONE AI's response. ONE AI asks for microphone permission only after you approve this voice session."),
        BubbleElement.Button("Start voice command") { _ ->
            BubbleAction.PluginCall(
                pluginId = pluginId,
                input = PluginActionInput(
                    actionId = VoiceInterfaceAdapter.VOICE_ACTION,
                    capability = "microphone",
                    description = "Record one voice command, send its transcript to the configured AI provider, and speak the response"
                ),
                approval = BubbleApproval(
                    "ALLOW ONE VOICE SESSION?",
                    "ONE AI will request Android microphone permission, listen once, send the recognized words to your configured AI provider, and speak its reply. Your voice is processed by Android's speech-recognition service; the transcript is sent to your AI provider.",
                    null, "Allow once"
                ),
                outcomes = BubbleOutcomes("Voice Interface unavailable", "Voice session blocked", "Voice session failed") { "Voice assistant" },
                backTo = SERVICE_ID
            )
        }
    ))

    override fun ensureReady(host: BubbleHost) = ensureRegistered(pluginId) {
        val context = host.context
        val store = OneAiStore(context)
        VoiceInterfaceAdapter(
            inputProvider = VoiceInputProvider { VoiceCaptureBridge.capture(context) },
            outputProvider = VoiceOutputProvider { VoiceSpeaker.speak(context, it) },
            orchestrator = AiOrchestrator(RetryingAiProvider(AiProviders.active(store, SecretVault(context))), store)
        )
    }

    override fun onHostStopped() = VoiceCaptureBridge.cancelPending()

    companion object { const val SERVICE_ID = "voice_interface" }
}

class AutoDroidBubbleProvider(runtime: PluginAdapterRegistrar) : BuiltInBubbleProvider(runtime) {
    override val serviceId = SERVICE_ID
    override val pluginId = AutoDroidAdapter.PLUGIN_ID
    override val title = "Automation"

    override fun view(host: BubbleHost) = BubbleView(listOf(
        BubbleElement.Label("Open the separate AutoDroid app after ONE AI approval. Choose and run macros inside AutoDroid; this hand-off does not execute a macro or transfer macro data."),
        BubbleElement.Button("Open AutoDroid") { _ ->
            BubbleAction.PluginCall(
                pluginId = pluginId,
                input = PluginActionInput(
                    actionId = AutoDroidIntegrationPolicy.OPEN_ACTION,
                    capability = "automation",
                    description = "Open AutoDroid automation workspace"
                ),
                approval = BubbleApproval(
                    "OPEN AUTODROID?",
                    "ONE AI will open AutoDroid. You choose and run macros inside AutoDroid, where its own permissions and controls apply. This action does not start a macro or transfer macro data.",
                    null, "Allow once and open"
                ),
                outcomes = BubbleOutcomes("AutoDroid unavailable", "AutoDroid launch blocked", "AutoDroid launch failed"),
                backTo = SERVICE_ID
            )
        }
    ))

    override fun ensureReady(host: BubbleHost) = ensureRegistered(pluginId) { AutoDroidAdapter(AndroidAutoDroidLauncher(host.context)) }

    companion object { const val SERVICE_ID = "automation" }
}
