package com.oneai.app.builtin

import com.oneai.app.ai.AiOrchestrator
import com.oneai.app.ai.AiProviderClient
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
import com.oneai.app.plugins.AndroidAppLauncher
import com.oneai.app.plugins.AndroidAutoDroidLauncher
import com.oneai.app.plugins.AndroidDeviceSettingsLauncher
import com.oneai.app.plugins.AppControlAdapter
import com.oneai.app.plugins.AutoDroidAdapter
import com.oneai.app.plugins.AutoDroidIntegrationPolicy
import com.oneai.app.plugins.DeviceControlAdapter
import com.oneai.app.plugins.DeviceControlPolicy
import com.oneai.app.plugins.InstalledAppCatalog
import com.oneai.app.plugins.PluginActionInput
import com.oneai.app.plugins.PluginRuntime
import com.oneai.app.plugins.ScreenIntelligenceAdapter
import com.oneai.app.plugins.VoiceInputProvider
import com.oneai.app.plugins.VoiceInterfaceAdapter
import com.oneai.app.plugins.VoiceOutputProvider
import com.oneai.app.plugins.WebResearchAdapter
import com.oneai.app.security.ActionRequest
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
        AppControlAdapter.PLUGIN_ID to AppControlBubbleProvider.SERVICE_ID,
        DeviceControlAdapter.PLUGIN_ID to DeviceControlBubbleProvider.SERVICE_ID,
        AutoDroidAdapter.PLUGIN_ID to AutoDroidBubbleProvider.SERVICE_ID
    )

    fun registerAll(registry: BubbleProviderRegistry) {
        listOf(
            WebResearchBubbleProvider(), ScreenIntelligenceBubbleProvider(), VoiceBubbleProvider(),
            AppControlBubbleProvider(), DeviceControlBubbleProvider(), AutoDroidBubbleProvider()
        ).forEach { registry.register(it) }
    }
}

class WebResearchBubbleProvider : BuiltInBubbleProvider() {
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

    override fun ensureReady(runtime: PluginRuntime, host: BubbleHost) =
        ensureRegistered(runtime, pluginId) { WebResearchAdapter(BraveSearchProvider(SecretVault(host.context))) }

    companion object { const val SERVICE_ID = "web_research" }
}

class ScreenIntelligenceBubbleProvider : BuiltInBubbleProvider() {
    override val serviceId = SERVICE_ID
    override val pluginId = ScreenIntelligenceAdapter.PLUGIN_ID
    override val title = "Screen intelligence"

    override fun view(host: BubbleHost) = BubbleView(listOf(
        BubbleElement.Label("Capture requires Android's system permission prompt. The screenshot and region selector open outside the Bubble; only the selected image reference returns here."),
        BubbleElement.Button("Capture screen region") { _ ->
            BubbleAction.GatedHostAction(
                request = ActionRequest(
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

    override fun ensureReady(runtime: PluginRuntime, host: BubbleHost) = ensureRegistered(runtime, pluginId) {
        val store = OneAiStore(host.context)
        ScreenIntelligenceAdapter(host.context, OpenAiCompatibleVisionProvider(store, SecretVault(host.context)))
    }

    companion object { const val SERVICE_ID = "screen_intelligence" }
}

class VoiceBubbleProvider : BuiltInBubbleProvider() {
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

    override fun ensureReady(runtime: PluginRuntime, host: BubbleHost) = ensureRegistered(runtime, pluginId) {
        val context = host.context
        val store = OneAiStore(context)
        VoiceInterfaceAdapter(
            inputProvider = VoiceInputProvider { VoiceCaptureBridge.capture(context) },
            outputProvider = VoiceOutputProvider { VoiceSpeaker.speak(context, it) },
            orchestrator = AiOrchestrator(RetryingAiProvider(AiProviderClient(store, SecretVault(context))), store)
        )
    }

    override fun onHostStopped() = VoiceCaptureBridge.cancelPending()

    companion object { const val SERVICE_ID = "voice_interface" }
}

class AppControlBubbleProvider : BuiltInBubbleProvider() {
    override val serviceId = SERVICE_ID
    override val pluginId = AppControlAdapter.PLUGIN_ID
    override val title = "App control"

    override fun view(host: BubbleHost): BubbleView {
        val apps = InstalledAppCatalog.load(host.context)
        val elements = mutableListOf<BubbleElement>(
            BubbleElement.Label("Choose an installed app to open. ONE AI will ask before launching it. This does not sign in, read private app data, or automate actions inside the selected app.")
        )
        if (apps.isEmpty()) elements += BubbleElement.Label("No launcher-visible apps were found.")
        apps.forEach { target -> elements += BubbleElement.Button(target.label) { _ -> launch(target.packageName, target.label) } }
        return BubbleView(elements)
    }

    private fun launch(packageName: String, appLabel: String): BubbleAction {
        val safeLabel = appLabel.take(80).ifBlank { packageName }
        return BubbleAction.PluginCall(
            pluginId = pluginId,
            input = PluginActionInput(
                actionId = AppControlAdapter.LAUNCH_ACTION,
                capability = "app_launch",
                description = "Launch installed app $safeLabel ($packageName)",
                values = mapOf("package_name" to packageName, "app_label" to safeLabel)
            ),
            approval = BubbleApproval(
                "LAUNCH $safeLabel?",
                "ONE AI will open this app using its Android launcher activity. It will not access the app's private data, sign in, or perform actions inside it.",
                null, "Allow once and open"
            ),
            outcomes = BubbleOutcomes("App Control unavailable", "App launch blocked", "App launch failed") {
                if (it.title == "App opened") "App launched" else "App launch result"
            },
            backTo = SERVICE_ID
        )
    }

    override fun ensureReady(runtime: PluginRuntime, host: BubbleHost) =
        ensureRegistered(runtime, pluginId) { AppControlAdapter(AndroidAppLauncher(host.context)) }

    companion object { const val SERVICE_ID = "app_control" }
}

class DeviceControlBubbleProvider : BuiltInBubbleProvider() {
    override val serviceId = SERVICE_ID
    override val pluginId = DeviceControlAdapter.PLUGIN_ID
    override val title = "Device control"

    override fun view(host: BubbleHost): BubbleView {
        val elements = mutableListOf<BubbleElement>(
            BubbleElement.Label("Choose an Android settings screen. ONE AI asks before opening it; Android remains in control of the actual setting and any system confirmation.")
        )
        DeviceControlPolicy.actions().forEach { action ->
            elements += BubbleElement.Button(action.label) { _ -> open(action.actionId, action.label) }
        }
        return BubbleView(elements)
    }

    private fun open(actionId: String, label: String): BubbleAction {
        val action = DeviceControlPolicy.action(actionId)
            ?: return BubbleAction.Message("Unsupported device action", "ONE AI does not support that settings action.")
        return BubbleAction.PluginCall(
            pluginId = pluginId,
            input = PluginActionInput(actionId = action.actionId, capability = "device_actions", description = "Open Android " + action.label + " settings"),
            approval = BubbleApproval(
                "OPEN " + label.uppercase() + " SETTINGS?",
                "ONE AI will open the corresponding Android Settings screen. You will make and confirm any changes there. ONE AI will not silently toggle restricted settings.",
                null, "Allow once and open"
            ),
            outcomes = BubbleOutcomes("Device Control unavailable", "Device action blocked", "Device action failed") {
                if (it.title == "Settings opened") null else it.title
            },
            backTo = SERVICE_ID
        )
    }

    override fun ensureReady(runtime: PluginRuntime, host: BubbleHost) =
        ensureRegistered(runtime, pluginId) { DeviceControlAdapter(AndroidDeviceSettingsLauncher(host.context)) }

    companion object { const val SERVICE_ID = "device_control" }
}

class AutoDroidBubbleProvider : BuiltInBubbleProvider() {
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

    override fun ensureReady(runtime: PluginRuntime, host: BubbleHost) =
        ensureRegistered(runtime, pluginId) { AutoDroidAdapter(AndroidAutoDroidLauncher(host.context)) }

    companion object { const val SERVICE_ID = "automation" }
}
