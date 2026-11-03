package com.oneai.app.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.text.InputType
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.oneai.app.core.OneAiStore
import com.oneai.app.ai.AiProviderClient
import com.oneai.app.ai.AiOrchestrator
import com.oneai.app.plugins.BuiltInCatalogue
import com.oneai.app.commands.CommandRouter
import com.oneai.app.security.ActionGateResult
import com.oneai.app.security.ActionRequest
import com.oneai.app.security.SecretVault
import com.oneai.app.plugins.ScreenIntelligenceAdapter
import com.oneai.app.plugins.PluginActionInput
import com.oneai.app.plugins.PluginInvocationResult
import com.oneai.app.ai.OpenAiCompatibleVisionProvider
import com.oneai.app.ai.BraveSearchProvider
import com.oneai.app.ai.WebSearchPolicy
import com.oneai.app.plugins.WebResearchAdapter
import com.oneai.app.plugins.VoiceInterfaceAdapter
import com.oneai.app.plugins.VoiceInputProvider
import com.oneai.app.plugins.VoiceOutputProvider
import com.oneai.app.plugins.VoiceTextPolicy
import com.oneai.app.plugins.AppControlAdapter
import com.oneai.app.plugins.AndroidAppLauncher
import com.oneai.app.plugins.InstalledAppCatalog
import com.oneai.app.plugins.DeviceControlAdapter
import com.oneai.app.plugins.AndroidDeviceSettingsLauncher
import com.oneai.app.plugins.DeviceControlPolicy
import com.oneai.app.plugins.AutoDroidAdapter
import com.oneai.app.plugins.AutoDroidIntegrationPolicy
import com.oneai.app.plugins.AndroidAutoDroidLauncher
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class FloatingBubbleService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var pendingVoiceCapture: CancellableContinuation<String>? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "ONE AI Bubble", NotificationManager.IMPORTANCE_LOW))
            return Notification.Builder(this, CHANNEL).setContentTitle("ONE AI bubble active")
                .setContentText("Tap the floating ONE icon to open quick actions.")
                .setSmallIcon(android.R.drawable.ic_dialog_info).build()
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this).setContentTitle("ONE AI bubble active")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build()
    }

    private fun showBubble() {
        val view = TextView(this).apply {
            text = "1"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.BLACK)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(214,174,88)) }
            setOnClickListener { if (panel == null) showPanel() else hidePanel() }
        }
        val prefs = getSharedPreferences("one_ai_bubble_ui", MODE_PRIVATE)
        val safeSize = BubbleUiPolicy.safeSize(
            prefs.getInt("bubble_size", BubbleUiPolicy.DEFAULT_SIZE_DP)
        )
        val position = BubbleUiPolicy.clampPosition(
            prefs.getInt("bubble_x", 24),
            prefs.getInt("bubble_y", 220),
            safeSize,
            safeSize,
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels
        )
        val params = WindowManager.LayoutParams(safeSize, safeSize, overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = position.x
            y = position.y
        }
        view.setOnTouchListener(object : android.view.View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx=e.rawX; sy=e.rawY; ox=params.x; oy=params.y; moved=false; return true }
                    MotionEvent.ACTION_MOVE -> { val dx=(e.rawX-sx).toInt(); val dy=(e.rawY-sy).toInt(); if (kotlin.math.abs(dx)+kotlin.math.abs(dy)>8) moved=true; val position = BubbleUiPolicy.clampPosition(
                            ox + dx, oy + dy, params.width, params.height,
                            resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels
                        ); params.x = position.x; params.y = position.y
                        runCatching { windowManager.updateViewLayout(view,params) }; return true }
                    MotionEvent.ACTION_UP -> {
                        getSharedPreferences("one_ai_bubble_ui", MODE_PRIVATE).edit().putInt("bubble_x", params.x).putInt("bubble_y", params.y).apply()
                        if (!moved) v.performClick()
                        return true
                    }
                }
                return false
            }
        })
        runCatching { windowManager.addView(view, params) }.onSuccess { bubble = view; bubbleParams = params }.onFailure { stopSelf() }
    }

    private fun basePanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(22,18,22,18)
        background = GradientDrawable().apply { setColor(Color.rgb(26,28,32)); cornerRadius=28f; setStroke(2,Color.rgb(214,174,88)) }
    }

    private fun addLabel(box: LinearLayout, text: String, size: Float = 14f): TextView {
        val label = TextView(this).apply { this.text=text; setTextColor(Color.WHITE); textSize=size; setPadding(0,8,0,8) }
        box.addView(label)
        return label
    }

    private fun addButton(box: LinearLayout, title: String, action: () -> Unit) {
        box.addView(Button(this).apply { text=title; setOnClickListener { action() } })
    }

    private fun showPanel() {
        val store = OneAiStore(this)
        val enabledPlugins = store.plugins().filter { it.enabled }.map { it.id }.toSet()
        val availableServices = BubbleUiPolicy.availableServices(enabledPlugins, BuiltInCatalogue.bubbleBindings)
        // Ignore stale saved toggles when a plugin is disabled or removed.
        val enabled = BubbleUiPolicy.visibleServices(store.bubbleServices(), availableServices)
        val box = basePanel()
        addLabel(box, "ONE AI  •  QUICK PANEL", 16f)
        if (enabled.isEmpty()) {
            addLabel(box, "No services enabled. Choose services in ONE AI Settings.")
        } else {
            BubbleUiPolicy.orderedServices(store.bubbleServiceOrder(), enabled).forEach { service ->
                addButton(box, service.replace('_',' ').replaceFirstChar { it.uppercase() }) { showService(service) }
            }
        }
        addButton(box, "Resize bubble") { resizeBubble() }
        addButton(box, "Hide bubble for 1 minute") { hideBubbleTemporarily() }
        addButton(box, "Close panel") { hidePanel() }
        attachPanel(box)
    }

    /** Service views are rendered inside the overlay; they never redirect to MainActivity. */
    private fun showService(service: String) {
        val box = basePanel()
        val title = service.replace('_',' ').replaceFirstChar { it.uppercase() }
        addLabel(box, "ONE AI  •  $title", 16f)
        if (service == "command_manager") {
            val commands = OneAiStore(this).commands().filter { it.enabled }
            if (commands.isEmpty()) addLabel(box, "No enabled commands yet. Create commands in Command Manager.")
            commands.forEach { command ->
                addButton(box, "Run: ${command.title}") {
                    val output = addLabel(box, "Running ${command.title}…", 12f)
                    serviceScope.launch {
                        val result = runCatching {
                            val store = OneAiStore(this@FloatingBubbleService)
                            val provider = AiProviderClient(store, SecretVault(this@FloatingBubbleService))
                            val router = CommandRouter(store, AiOrchestrator(provider, store))
                            router.run(command.id).content
                        }.getOrElse { "Error: ${it.message}" }
                        if (serviceJob.isActive) {
                            Handler(Looper.getMainLooper()).post {
                                if (serviceJob.isActive) output.text = result
                            }
                        }
                    }
                }
            }
            addButton(box, "Back to services") { showPanel() }
            addButton(box, "Close panel") { hidePanel() }
            attachPanel(box)
            return
        }
        if (service == "screen_intelligence") {
            addLabel(box, "Capture requires Android's system permission prompt. The screenshot and region selector open outside the Bubble; only the selected image reference returns here.")
            addButton(box, "Capture screen region") {
                requestScreenCaptureApproval()
            }
        } else if (service == "web_research") {
            addLabel(box, "Search the web using your separately encrypted Brave Search credential. Each search requires your approval.")
            val query = EditText(this).apply {
                hint = "What would you like to search for?"
                setTextColor(Color.WHITE)
                setHintTextColor(Color.LTGRAY)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 2
            }
            box.addView(query, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addButton(box, "Search the web") { requestWebSearch(query.text.toString()) }
        } else if (service == "app_control") {
            addLabel(box, "Choose an installed app to open. ONE AI will ask before launching it. This does not sign in, read private app data, or automate actions inside the selected app.")
            val apps = InstalledAppCatalog.load(this)
            if (apps.isEmpty()) addLabel(box, "No launcher-visible apps were found.")
            apps.forEach { target ->
                addButton(box, target.label) { requestAppLaunch(target.packageName, target.label) }
            }
        } else if (service == "device_control") {
            addLabel(box, "Choose an Android settings screen. ONE AI asks before opening it; Android remains in control of the actual setting and any system confirmation.")
            DeviceControlPolicy.actions().forEach { action ->
                addButton(box, action.label) { requestDeviceSettings(action.actionId, action.label) }
            }
        } else if (service == "automation") {
            addLabel(box, "Open the separate AutoDroid app after ONE AI approval. Choose and run macros inside AutoDroid; this hand-off does not execute a macro or transfer macro data.")
            addButton(box, "Open AutoDroid") { requestAutoDroid() }
        } else if (service == "voice_interface") {
            addLabel(box, "Speak one command and hear ONE AI's response. ONE AI asks for microphone permission only after you approve this voice session.")
            addButton(box, "Start voice command") { requestVoiceInteraction() }
        } else {
            val message = when (service) {
                "voice_interface" -> "Voice Interface is not connected yet. Voice capture, playback and runtime permission flows are still in development."
                "automation" -> "Automation is not connected yet. No device workflow executor is connected."
                else -> "This service is enabled, but no compatible provider is connected yet."
            }
            addLabel(box, message)
        }
        addButton(box, "Back to services") { showPanel() }
        addButton(box, "Close panel") { hidePanel() }
        attachPanel(box)
    }

    /** One explicit plugin approval gates one recording, AI request and spoken reply. */
    private fun requestVoiceInteraction() {
        val store = OneAiStore(this)
        val runtime = store.pluginRuntime()
        val adapter = VoiceInterfaceAdapter(
            inputProvider = VoiceInputProvider { captureVoiceInput() },
            outputProvider = VoiceOutputProvider { speakVoiceResponse(it) },
            orchestrator = AiOrchestrator(
                AiProviderClient(store, SecretVault(this)),
                store
            )
        )
        if (VoiceInterfaceAdapter.PLUGIN_ID !in runtime.registeredPluginIds()) {
            runtime.register(adapter).onFailure {
                showHandoffResult("Voice Interface unavailable", it.message ?: "Could not register the trusted voice adapter.")
                return
            }
        }
        val input = PluginActionInput(
            actionId = VoiceInterfaceAdapter.VOICE_ACTION,
            capability = "microphone",
            description = "Record one voice command, send its transcript to the configured AI provider, and speak the response"
        )
        serviceScope.launch {
            val decision = runtime.invoke(VoiceInterfaceAdapter.PLUGIN_ID, input)
            if (!serviceJob.isActive) return@launch
            uiHandler.post {
                if (!serviceJob.isActive) return@post
                when (decision) {
                    is PluginInvocationResult.AwaitingApproval -> {
                        val box = basePanel()
                        addLabel(box, "ALLOW ONE VOICE SESSION?", 16f)
                        addLabel(box, "ONE AI will request Android microphone permission, listen once, send the recognized words to your configured AI provider, and speak its reply. Your voice is processed by Android's speech-recognition service; the transcript is sent to your AI provider.")
                        addButton(box, "Cancel") { showService("voice_interface") }
                        addButton(box, "Allow once") {
                            val ticket = runtime.approveSensitiveAction(VoiceInterfaceAdapter.PLUGIN_ID, input)
                                .getOrElse {
                                    showHandoffResult("Voice session blocked", "ONE AI could not verify approval for this voice session.")
                                    return@addButton
                                }
                            hidePanel()
                            serviceScope.launch {
                                val result = runtime.invoke(VoiceInterfaceAdapter.PLUGIN_ID, input, ticket)
                                val message = when (result) {
                                    is PluginInvocationResult.Completed -> result.output.content
                                    is PluginInvocationResult.Rejected -> result.reason
                                    is PluginInvocationResult.Failed -> result.message
                                    is PluginInvocationResult.AwaitingApproval -> "A new approval is required."
                                }
                                if (serviceJob.isActive) uiHandler.post {
                                    if (serviceJob.isActive) showHandoffResult(
                                        if (result is PluginInvocationResult.Completed) "Voice assistant" else "Voice session unavailable",
                                        message
                                    )
                                }
                            }
                        }
                        attachPanel(box)
                    }
                    is PluginInvocationResult.Rejected -> showHandoffResult("Voice session blocked", decision.reason)
                    is PluginInvocationResult.Failed -> showHandoffResult("Voice session failed", decision.message)
                    is PluginInvocationResult.Completed -> showHandoffResult("Voice assistant", decision.output.content)
                }
            }
        }
    }

    /** Opens only allowlisted Android Settings screens after an exact one-use approval. */
    private fun requestDeviceSettings(actionId: String, label: String) {
        val store = OneAiStore(this)
        val runtime = store.pluginRuntime()
        val adapter = DeviceControlAdapter(AndroidDeviceSettingsLauncher(this))
        if (DeviceControlAdapter.PLUGIN_ID !in runtime.registeredPluginIds()) {
            runtime.register(adapter).onFailure {
                showHandoffResult("Device Control unavailable", it.message ?: "Could not register the trusted settings adapter.")
                return
            }
        }
        val action = DeviceControlPolicy.action(actionId) ?: run {
            showHandoffResult("Unsupported device action", "ONE AI does not support that settings action.")
            return
        }
        val input = PluginActionInput(
            actionId = action.actionId,
            capability = "device_actions",
            description = "Open Android " + action.label + " settings"
        )
        serviceScope.launch {
            val decision = runtime.invoke(DeviceControlAdapter.PLUGIN_ID, input)
            if (!serviceJob.isActive) return@launch
            uiHandler.post {
                if (!serviceJob.isActive) return@post
                when (decision) {
                    is PluginInvocationResult.AwaitingApproval -> {
                        val box = basePanel()
                        addLabel(box, "OPEN " + label.uppercase() + " SETTINGS?", 16f)
                        addLabel(box, "ONE AI will open the corresponding Android Settings screen. You will make and confirm any changes there. ONE AI will not silently toggle restricted settings.")
                        addButton(box, "Cancel") { showService("device_control") }
                        addButton(box, "Allow once and open") {
                            val ticket = runtime.approveSensitiveAction(DeviceControlAdapter.PLUGIN_ID, input)
                                .getOrElse {
                                    showHandoffResult("Device action blocked", "ONE AI could not verify approval for this settings action.")
                                    return@addButton
                                }
                            hidePanel()
                            serviceScope.launch {
                                val result = runtime.invoke(DeviceControlAdapter.PLUGIN_ID, input, ticket)
                                if (!serviceJob.isActive) return@launch
                                uiHandler.post {
                                    if (!serviceJob.isActive) return@post
                                    when (result) {
                                        is PluginInvocationResult.Completed ->
                                            if (result.output.title != "Settings opened") showHandoffResult(result.output.title, result.output.content)
                                        is PluginInvocationResult.Rejected -> showHandoffResult("Device action blocked", result.reason)
                                        is PluginInvocationResult.Failed -> showHandoffResult("Device action failed", result.message)
                                        is PluginInvocationResult.AwaitingApproval -> showHandoffResult("Approval expired", "A fresh approval is required.")
                                    }
                                }
                            }
                        }
                        attachPanel(box)
                    }
                    is PluginInvocationResult.Rejected -> showHandoffResult("Device action blocked", decision.reason)
                    is PluginInvocationResult.Failed -> showHandoffResult("Device action failed", decision.message)
                    is PluginInvocationResult.Completed ->
                        if (decision.output.title != "Settings opened") showHandoffResult(decision.output.title, decision.output.content)
                }
            }
        }
    }

    /** Opens the separate AutoDroid app only after an exact, one-use ONE AI approval. */
    private fun requestAutoDroid() {
        val store = OneAiStore(this)
        val runtime = store.pluginRuntime()
        val adapter = AutoDroidAdapter(AndroidAutoDroidLauncher(this))
        if (AutoDroidAdapter.PLUGIN_ID !in runtime.registeredPluginIds()) {
            runtime.register(adapter).onFailure {
                showHandoffResult("AutoDroid unavailable", it.message ?: "Could not register the trusted AutoDroid adapter.")
                return
            }
        }
        val input = PluginActionInput(
            actionId = AutoDroidIntegrationPolicy.OPEN_ACTION,
            capability = "automation",
            description = "Open AutoDroid automation workspace"
        )
        serviceScope.launch {
            val decision = runtime.invoke(AutoDroidAdapter.PLUGIN_ID, input)
            if (!serviceJob.isActive) return@launch
            uiHandler.post {
                if (!serviceJob.isActive) return@post
                when (decision) {
                    is PluginInvocationResult.AwaitingApproval -> {
                        val box = basePanel()
                        addLabel(box, "OPEN AUTODROID?", 16f)
                        addLabel(box, "ONE AI will open AutoDroid. You choose and run macros inside AutoDroid, where its own permissions and controls apply. This action does not start a macro or transfer macro data.")
                        addButton(box, "Cancel") { showService("automation") }
                        addButton(box, "Allow once and open") {
                            val ticket = runtime.approveSensitiveAction(AutoDroidAdapter.PLUGIN_ID, input)
                                .getOrElse {
                                    showHandoffResult("AutoDroid launch blocked", "ONE AI could not verify approval for this hand-off.")
                                    return@addButton
                                }
                            hidePanel()
                            serviceScope.launch {
                                val result = runtime.invoke(AutoDroidAdapter.PLUGIN_ID, input, ticket)
                                val message = when (result) {
                                    is PluginInvocationResult.Completed -> result.output.content
                                    is PluginInvocationResult.Rejected -> result.reason
                                    is PluginInvocationResult.Failed -> result.message
                                    is PluginInvocationResult.AwaitingApproval -> "A fresh one-time approval is required."
                                }
                                if (serviceJob.isActive) uiHandler.post {
                                    if (serviceJob.isActive) showHandoffResult(
                                        if (result is PluginInvocationResult.Completed && result.output.title == "AutoDroid opened") "AutoDroid opened" else "AutoDroid hand-off result",
                                        message
                                    )
                                }
                            }
                        }
                        attachPanel(box)
                    }
                    is PluginInvocationResult.Rejected -> showHandoffResult("AutoDroid launch blocked", decision.reason)
                    is PluginInvocationResult.Failed -> showHandoffResult("AutoDroid launch failed", decision.message)
                    is PluginInvocationResult.Completed -> showHandoffResult(decision.output.title, decision.output.content)
                }
            }
        }
    }

    /** Each selected app launch is routed through the plugin runtime and an exact one-use approval. */
    private fun requestAppLaunch(packageName: String, appLabel: String) {
        val store = OneAiStore(this)
        val runtime = store.pluginRuntime()
        val adapter = AppControlAdapter(AndroidAppLauncher(this))
        if (AppControlAdapter.PLUGIN_ID !in runtime.registeredPluginIds()) {
            runtime.register(adapter).onFailure {
                showHandoffResult("App Control unavailable", it.message ?: "Could not register the trusted app launcher.")
                return
            }
        }
        val safeLabel = appLabel.take(80).ifBlank { packageName }
        val input = PluginActionInput(
            actionId = AppControlAdapter.LAUNCH_ACTION,
            capability = "app_launch",
            description = "Launch installed app $safeLabel ($packageName)",
            values = mapOf("package_name" to packageName, "app_label" to safeLabel)
        )
        serviceScope.launch {
            val decision = runtime.invoke(AppControlAdapter.PLUGIN_ID, input)
            if (!serviceJob.isActive) return@launch
            uiHandler.post {
                if (!serviceJob.isActive) return@post
                when (decision) {
                    is PluginInvocationResult.AwaitingApproval -> {
                        val box = basePanel()
                        addLabel(box, "LAUNCH $safeLabel?", 16f)
                        addLabel(box, "ONE AI will open this app using its Android launcher activity. It will not access the app's private data, sign in, or perform actions inside it.")
                        addButton(box, "Cancel") { showService("app_control") }
                        addButton(box, "Allow once and open") {
                            val ticket = runtime.approveSensitiveAction(AppControlAdapter.PLUGIN_ID, input)
                                .getOrElse {
                                    showHandoffResult("App launch blocked", "ONE AI could not verify approval for this app launch.")
                                    return@addButton
                                }
                            hidePanel()
                            serviceScope.launch {
                                val result = runtime.invoke(AppControlAdapter.PLUGIN_ID, input, ticket)
                                val message = when (result) {
                                    is PluginInvocationResult.Completed -> result.output.content
                                    is PluginInvocationResult.Rejected -> result.reason
                                    is PluginInvocationResult.Failed -> result.message
                                    is PluginInvocationResult.AwaitingApproval -> "A new approval is required."
                                }
                                if (serviceJob.isActive) uiHandler.post {
                                    if (serviceJob.isActive) showHandoffResult(
                                        if (result is PluginInvocationResult.Completed && result.output.title == "App opened") "App launched" else "App launch result",
                                        message
                                    )
                                }
                            }
                        }
                        attachPanel(box)
                    }
                    is PluginInvocationResult.Rejected -> showHandoffResult("App launch blocked", decision.reason)
                    is PluginInvocationResult.Failed -> showHandoffResult("App launch failed", decision.message)
                    is PluginInvocationResult.Completed -> showHandoffResult(decision.output.title, decision.output.content)
                }
            }
        }
    }

    private suspend fun captureVoiceInput(): String = suspendCancellableCoroutine { continuation ->
        pendingVoiceCapture = continuation
        continuation.invokeOnCancellation {
            if (pendingVoiceCapture === continuation) pendingVoiceCapture = null
        }
        uiHandler.post {
            if (!continuation.isActive || !serviceJob.isActive) return@post
            runCatching {
                startActivity(Intent(this, VoiceCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { error ->
                if (pendingVoiceCapture === continuation) pendingVoiceCapture = null
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    private fun completeVoiceCapture(intent: Intent) {
        val continuation = pendingVoiceCapture ?: return
        pendingVoiceCapture = null
        val value = intent.getStringExtra(EXTRA_VOICE_TEXT).orEmpty()
        if (intent.getBooleanExtra(EXTRA_VOICE_SUCCESS, false)) {
            val transcript = VoiceTextPolicy.normalizeTranscript(value)
            if (transcript == null) continuation.resumeWithException(IllegalArgumentException("No usable speech was recognized."))
            else continuation.resume(transcript)
        } else {
            continuation.resumeWithException(IllegalStateException(value.ifBlank { "Voice input was cancelled." }))
        }
    }

    private suspend fun speakVoiceResponse(text: String) = suspendCancellableCoroutine<Unit> { continuation ->
        uiHandler.post {
            if (!continuation.isActive) return@post
            lateinit var engine: TextToSpeech
            engine = TextToSpeech(this) { status ->
                if (!continuation.isActive) {
                    engine.shutdown()
                    return@TextToSpeech
                }
                if (status != TextToSpeech.SUCCESS) {
                    engine.shutdown()
                    continuation.resumeWithException(IllegalStateException("Android text-to-speech is unavailable."))
                    return@TextToSpeech
                }
                val utteranceId = UUID.randomUUID().toString()
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) {
                        engine.shutdown()
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    override fun onError(id: String?) {
                        engine.shutdown()
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Android text-to-speech failed."))
                    }
                })
                val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (result == TextToSpeech.ERROR) {
                    engine.shutdown()
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Android text-to-speech could not start."))
                }
            }
            continuation.invokeOnCancellation {
                uiHandler.post {
                    runCatching { engine.stop() }
                    runCatching { engine.shutdown() }
                }
            }
        }
    }

    /** Web requests are routed through the plugin runtime and require an exact one-use approval. */
    private fun requestWebSearch(query: String) {
        val normalizedQuery = WebSearchPolicy.normalizeQuery(query)
        if (normalizedQuery == null) {
            showHandoffResult("Search query needed", "Enter a search query between 2 and 300 characters.")
            return
        }
        val store = OneAiStore(this)
        val runtime = store.pluginRuntime()
        val adapter = WebResearchAdapter(BraveSearchProvider(SecretVault(this)))
        if (WebResearchAdapter.PLUGIN_ID !in runtime.registeredPluginIds()) {
            runtime.register(adapter).onFailure {
                showHandoffResult("Web Research unavailable", it.message ?: "Could not register the trusted search adapter.")
                return
            }
        }
        val input = PluginActionInput(
            actionId = WebResearchAdapter.SEARCH_ACTION,
            capability = "internet",
            description = "Search the web for: $normalizedQuery",
            values = mapOf("query" to normalizedQuery)
        )
        serviceScope.launch {
            val decision = runtime.invoke(WebResearchAdapter.PLUGIN_ID, input)
            if (!serviceJob.isActive) return@launch
            uiHandler.post {
                if (!serviceJob.isActive) return@post
                when (decision) {
                    is PluginInvocationResult.AwaitingApproval -> {
                        val box = basePanel()
                        addLabel(box, "ALLOW THIS WEB SEARCH ONCE?", 16f)
                        addLabel(box, "ONE AI will send this query to Brave Search over HTTPS. Search terms may be processed by that provider. Review the query before approving.")
                        addLabel(box, normalizedQuery, 12f)
                        addButton(box, "Cancel") { showPanel() }
                        addButton(box, "Allow once and search") {
                            val ticket = runtime.approveSensitiveAction(WebResearchAdapter.PLUGIN_ID, input)
                                .getOrElse {
                                    showHandoffResult("Search blocked", "ONE AI could not verify approval for this search.")
                                    return@addButton
                                }
                            hidePanel()
                            serviceScope.launch {
                                val result = runtime.invoke(WebResearchAdapter.PLUGIN_ID, input, ticket)
                                val message = when (result) {
                                    is PluginInvocationResult.Completed -> result.output.content
                                    is PluginInvocationResult.Rejected -> result.reason
                                    is PluginInvocationResult.Failed -> result.message
                                    is PluginInvocationResult.AwaitingApproval -> "A new approval is required."
                                }
                                if (serviceJob.isActive) uiHandler.post {
                                    if (serviceJob.isActive) showHandoffResult(
                                        if (result is PluginInvocationResult.Completed) "Web search results" else "Web search unavailable",
                                        message
                                    )
                                }
                            }
                        }
                        attachPanel(box)
                    }
                    is PluginInvocationResult.Rejected -> showHandoffResult("Search blocked", decision.reason)
                    is PluginInvocationResult.Failed -> showHandoffResult("Search failed", decision.message)
                    is PluginInvocationResult.Completed -> showHandoffResult("Web search results", decision.output.content)
                }
            }
        }
    }

    /** ONE AI approval is consumed before Android's separate MediaProjection consent prompt. */
    private fun requestScreenCaptureApproval() {
        val pluginId = "one.screen.intelligence"
        val action = ActionRequest(
            pluginId = pluginId,
            actionId = "capture_region",
            capability = "screen_capture",
            description = "Capture one screen frame and let me select a region"
        )
        val store = OneAiStore(this)
        val gateway = store.pluginActionGateway()
        when (val decision = gateway.check(action)) {
            is ActionGateResult.Rejected -> {
                showHandoffResult("Screen capture blocked", decision.authorization.reason)
                return
            }
            is ActionGateResult.Permitted -> {
                showHandoffResult("Screen capture unavailable", "This sensitive action unexpectedly bypassed its approval requirement.")
                return
            }
            is ActionGateResult.AwaitingUserApproval -> Unit
        }
        val box = basePanel()
        addLabel(box, "ALLOW SCREEN CAPTURE ONCE?", 16f)
        addLabel(box, "ONE AI will capture one screen frame so you can select a region. The image stays in private app storage. Android will then show its own screen-sharing consent prompt.")
        addButton(box, "Cancel") { showPanel() }
        addButton(box, "Allow once and continue") {
            runCatching {
                val ticket = gateway.confirmUserApproval(action)
                when (val approved = gateway.check(action, ticket)) {
                    is ActionGateResult.Permitted -> {
                        hidePanel()
                        bubble?.let { runCatching { windowManager.removeView(it) } }
                        bubble = null
                        bubbleParams = null
                        ScreenCaptureConsentActivity.launch(this)
                    }
                    is ActionGateResult.AwaitingUserApproval ->
                        showHandoffResult("Approval required", approved.authorization.reason)
                    is ActionGateResult.Rejected ->
                        showHandoffResult("Screen capture blocked", approved.authorization.reason)
                }
            }.onFailure {
                showHandoffResult("Screen capture blocked", "ONE AI could not verify approval for this capture.")
            }
        }
        attachPanel(box)
    }

    private fun attachPanel(box: LinearLayout) {
        hidePanel()
        // Keep large command/service lists inside the visible display instead of letting
        // the overlay extend beyond the screen. The Bubble remains independently usable.
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(box, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val maxPanelHeight = (resources.displayMetrics.heightPixels * 0.78f).toInt().coerceAtLeast(320)
        val params = WindowManager.LayoutParams((resources.displayMetrics.widthPixels*0.84f).toInt(),
            maxPanelHeight, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.CENTER; x=0; y=0
        }
        runCatching { windowManager.addView(scroll,params) }.onSuccess { panel=scroll }
    }

    private fun resizeBubble() {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        val prefs = getSharedPreferences("one_ai_bubble_ui", MODE_PRIVATE)
        val next = if (params.width <= 58) 76 else 48
        params.width = next; params.height = next
        prefs.edit().putInt("bubble_size", next).apply()
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun hideBubbleTemporarily() {
        hidePanel()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null; bubbleParams = null
        uiHandler.postDelayed({ if (bubble == null) showBubble() }, 60_000L)
    }

    /** Receives validated text results from trusted feature modules without opening MainActivity. */
    private fun showHandoffResult(title: String?, content: String?, imagePath: String? = null) {
        val result = BubbleResultHandoff.validate(title, content, imagePath) ?: return
        val safeImage = result.imagePath?.let { path ->
            runCatching {
                val file = java.io.File(path)
                val allowedRoot = java.io.File(cacheDir, "one-ai-selected").canonicalPath + java.io.File.separator
                file.takeIf { it.isFile && it.canonicalPath.startsWith(allowedRoot) }
            }.getOrNull()
        }
        val box = basePanel()
        addLabel(box, "ONE AI  •  RESULT", 16f)
        addLabel(box, result.title, 14f)
        addLabel(box, result.content, 13f)
        if (safeImage != null) {
            addLabel(box, "The selected image stays private until you explicitly approve sending it to your configured AI provider.", 11f)
            addButton(box, "Analyze selected region") { requestImageAnalysis(safeImage.absolutePath) }
        }
        addButton(box, "Back to services") { showPanel() }
        addButton(box, "Close panel") { hidePanel() }
        attachPanel(box)
    }

    /** A second, separate approval is required before the selected image is sent online. */
    private fun requestImageAnalysis(imagePath: String) {
        val store = OneAiStore(this)
        val runtime = store.pluginRuntime()
        val adapter = ScreenIntelligenceAdapter(
            this,
            OpenAiCompatibleVisionProvider(store, SecretVault(this))
        )
        if (ScreenIntelligenceAdapter.PLUGIN_ID !in runtime.registeredPluginIds()) {
            runtime.register(adapter).onFailure {
                showHandoffResult("Screen Intelligence unavailable", it.message ?: "Could not register the trusted adapter.")
                return
            }
        }
        val input = PluginActionInput(
            actionId = ScreenIntelligenceAdapter.ANALYZE_ACTION,
            capability = "internet",
            description = "Send the selected screen region to the configured AI provider for analysis",
            values = mapOf("image_path" to imagePath)
        )
        serviceScope.launch {
            val decision = runtime.invoke(ScreenIntelligenceAdapter.PLUGIN_ID, input)
            if (!serviceJob.isActive) return@launch
            uiHandler.post {
                if (!serviceJob.isActive) return@post
                when (decision) {
                    is PluginInvocationResult.AwaitingApproval -> {
                        val box = basePanel()
                        addLabel(box, "SEND SELECTED IMAGE FOR ANALYSIS?", 16f)
                        addLabel(box, "This sends the selected screen region to your configured AI provider over HTTPS. Continue only if the image contains no information you want to keep private.")
                        addButton(box, "Cancel") { showPanel() }
                        addButton(box, "Allow once and analyze") {
                            val ticket = runtime.approveSensitiveAction(ScreenIntelligenceAdapter.PLUGIN_ID, input)
                                .getOrElse {
                                    showHandoffResult("Analysis blocked", "ONE AI could not verify approval for this image.")
                                    return@addButton
                                }
                            hidePanel()
                            serviceScope.launch {
                                val result = runtime.invoke(ScreenIntelligenceAdapter.PLUGIN_ID, input, ticket)
                                val message = when (result) {
                                    is PluginInvocationResult.Completed -> result.output.content
                                    is PluginInvocationResult.Rejected -> result.reason
                                    is PluginInvocationResult.Failed -> result.message
                                    is PluginInvocationResult.AwaitingApproval -> "A new approval is required."
                                }
                                if (serviceJob.isActive) uiHandler.post {
                                    if (serviceJob.isActive) showHandoffResult(
                                        if (result is PluginInvocationResult.Completed) "Screen analysis complete" else "Screen analysis unavailable",
                                        message
                                    )
                                }
                            }
                        }
                        attachPanel(box)
                    }
                    is PluginInvocationResult.Rejected -> showHandoffResult("Analysis blocked", decision.reason)
                    is PluginInvocationResult.Failed -> showHandoffResult("Analysis failed", decision.message)
                    is PluginInvocationResult.Completed -> showHandoffResult("Screen analysis complete", decision.output.content)
                }
            }
        }
    }

    private fun hidePanel() { panel?.let { runCatching { windowManager.removeView(it) } }; panel=null }
    private fun overlayType() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_VOICE_CAPTURE_RESULT) {
            completeVoiceCapture(intent)
        } else if (intent?.action == BubbleResultHandoff.ACTION_SHOW_RESULT) {
            if (bubble == null) showBubble()
            showHandoffResult(
                intent.getStringExtra(BubbleResultHandoff.EXTRA_TITLE),
                intent.getStringExtra(BubbleResultHandoff.EXTRA_CONTENT),
                intent.getStringExtra(BubbleResultHandoff.EXTRA_IMAGE_PATH)
            )
        }
        return START_STICKY
    }
    override fun onDestroy() { pendingVoiceCapture?.cancel(); pendingVoiceCapture=null; serviceJob.cancel(); uiHandler.removeCallbacksAndMessages(null); hidePanel(); bubble?.let { runCatching { windowManager.removeView(it) } }; bubble=null; bubbleParams=null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val CHANNEL="one_ai_bubble"
        const val NOTIFICATION_ID=7104
        const val ACTION_VOICE_CAPTURE_RESULT="com.oneai.app.action.VOICE_CAPTURE_RESULT"
        const val EXTRA_VOICE_SUCCESS="voice_success"
        const val EXTRA_VOICE_TEXT="voice_text"
    }
}
