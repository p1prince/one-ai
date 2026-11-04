package com.oneai.app.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.oneai.app.ai.AiOrchestrator
import com.oneai.app.ai.AiProviderClient
import com.oneai.app.ai.RetryingAiProvider
import com.oneai.app.commands.CommandRouter
import com.oneai.app.core.OneAiStore
import com.oneai.app.plugins.PluginInvocationResult
import com.oneai.app.plugins.PluginRuntime
import com.oneai.app.security.ActionGateResult
import com.oneai.app.security.SecretVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Floating Bubble shell. It owns the overlay, movement, preferences, the core command
 * service and the generic approval/result flow. Every other service is contributed through
 * [BubbleServiceProvider]; this class knows nothing about specific capabilities.
 */
class FloatingBubbleService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val registry get() = BubbleProviders.registry

    private val host = object : BubbleHost {
        override val context: Context get() = this@FloatingBubbleService
        override fun hideBubble() {
            hidePanel()
            bubble?.let { runCatching { windowManager.removeView(it) } }
            bubble = null
            bubbleParams = null
        }
    }

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

    private fun titleFor(service: String): String =
        registry.provider(service)?.title ?: service.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun showPanel() {
        val store = OneAiStore(this)
        val enabledPlugins = store.plugins().filter { it.enabled }.map { it.id }.toSet()
        val availableServices = BubbleUiPolicy.availableServices(enabledPlugins, registry.bindings())
        // Ignore stale saved toggles when a plugin is disabled or removed.
        val enabled = BubbleUiPolicy.visibleServices(store.bubbleServices(), availableServices)
        val box = basePanel()
        addLabel(box, "ONE AI  •  QUICK PANEL", 16f)
        if (enabled.isEmpty()) {
            addLabel(box, "No services enabled. Choose services in ONE AI Settings.")
        } else {
            BubbleUiPolicy.orderedServices(store.bubbleServiceOrder(), enabled).forEach { service ->
                addButton(box, titleFor(service)) { showService(service) }
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
        addLabel(box, "ONE AI  •  " + titleFor(service), 16f)
        if (service == BubbleUiPolicy.COMMAND_MANAGER) {
            renderCommandManager(box)
        } else {
            val provider = registry.provider(service)
            if (provider == null) {
                addLabel(box, "This service is enabled, but no compatible provider is connected yet.")
            } else {
                renderView(box, provider, provider.view(host))
            }
        }
        addButton(box, "Back to services") { showPanel() }
        addButton(box, "Close panel") { hidePanel() }
        attachPanel(box)
    }

    private fun renderCommandManager(box: LinearLayout) {
        val commands = OneAiStore(this).commands().filter { it.enabled }
        if (commands.isEmpty()) addLabel(box, "No enabled commands yet. Create commands in Command Manager.")
        commands.forEach { command ->
            addButton(box, "Run: ${command.title}") {
                val output = addLabel(box, "Running ${command.title}…", 12f)
                serviceScope.launch {
                    val result = runCatching {
                        val store = OneAiStore(this@FloatingBubbleService)
                        val provider = AiProviderClient(store, SecretVault(this@FloatingBubbleService))
                        val router = CommandRouter(store, AiOrchestrator(RetryingAiProvider(provider), store))
                        router.run(command.id).content
                    }.getOrElse { "Error: ${it.message}" }
                    if (serviceJob.isActive) {
                        uiHandler.post { if (serviceJob.isActive) output.text = result }
                    }
                }
            }
        }
    }

    private fun renderView(box: LinearLayout, provider: BubbleServiceProvider, view: BubbleView) {
        val inputs = mutableMapOf<String, EditText>()
        view.elements.forEach { element ->
            when (element) {
                is BubbleElement.Label -> addLabel(box, element.text, element.size)
                is BubbleElement.TextInput -> {
                    val field = EditText(this).apply {
                        hint = element.hint
                        setTextColor(Color.WHITE)
                        setHintTextColor(Color.LTGRAY)
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        minLines = 2
                    }
                    box.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    inputs[element.key] = field
                }
                is BubbleElement.Button -> addButton(box, element.label) {
                    val values = inputs.mapValues { it.value.text.toString() }
                    element.onPress(values)?.let { perform(it, provider) }
                }
            }
        }
    }

    private fun perform(action: BubbleAction, provider: BubbleServiceProvider) {
        when (action) {
            is BubbleAction.Message -> showHandoffResult(action.title, action.content)
            is BubbleAction.PluginCall -> runPluginCall(action, provider)
            is BubbleAction.GatedHostAction -> runGatedHostAction(action)
        }
    }

    private fun show(message: BubbleMessage?) {
        if (message != null) showHandoffResult(message.title, message.content)
    }

    /** One shared flow: invoke, ask for approval when required, invoke again with the one-use ticket. */
    private fun runPluginCall(call: BubbleAction.PluginCall, provider: BubbleServiceProvider) {
        val runtime: PluginRuntime = OneAiStore(this).pluginRuntime()
        provider.ensureReady(runtime, host).onFailure {
            showHandoffResult(call.outcomes.unavailableTitle, it.message ?: "Could not start this service.")
            return
        }
        serviceScope.launch {
            val decision = runtime.invoke(call.pluginId, call.input)
            if (!serviceJob.isActive) return@launch
            uiHandler.post {
                if (!serviceJob.isActive) return@post
                if (decision is PluginInvocationResult.AwaitingApproval) showApproval(call, runtime)
                else show(call.outcomes.present(decision))
            }
        }
    }

    private fun showApproval(call: BubbleAction.PluginCall, runtime: PluginRuntime) {
        val box = basePanel()
        addLabel(box, call.approval.title, 16f)
        addLabel(box, call.approval.message)
        call.approval.review?.let { addLabel(box, it, 12f) }
        addButton(box, "Cancel") { call.backTo?.let { showService(it) } ?: showPanel() }
        addButton(box, call.approval.confirmLabel) {
            val ticket = runtime.approveSensitiveAction(call.pluginId, call.input).getOrElse {
                showHandoffResult(call.outcomes.blockedTitle, "ONE AI could not verify approval for this action.")
                return@addButton
            }
            hidePanel()
            serviceScope.launch {
                val result = runtime.invoke(call.pluginId, call.input, ticket)
                if (!serviceJob.isActive) return@launch
                uiHandler.post { if (serviceJob.isActive) show(call.outcomes.present(result)) }
            }
        }
        attachPanel(box)
    }

    /** The gateway issues and consumes the approval; the contributor then performs the action. */
    private fun runGatedHostAction(action: BubbleAction.GatedHostAction) {
        val gateway = OneAiStore(this).pluginActionGateway()
        when (val decision = gateway.check(action.request)) {
            is ActionGateResult.Rejected -> {
                showHandoffResult(action.blockedTitle, decision.authorization.reason)
                return
            }
            is ActionGateResult.Permitted -> {
                showHandoffResult(action.blockedTitle, "This sensitive action unexpectedly bypassed its approval requirement.")
                return
            }
            is ActionGateResult.AwaitingUserApproval -> Unit
        }
        val box = basePanel()
        addLabel(box, action.approval.title, 16f)
        addLabel(box, action.approval.message)
        action.approval.review?.let { addLabel(box, it, 12f) }
        addButton(box, "Cancel") { showPanel() }
        addButton(box, action.approval.confirmLabel) {
            runCatching {
                val ticket = gateway.confirmUserApproval(action.request)
                when (val approved = gateway.check(action.request, ticket)) {
                    is ActionGateResult.Permitted -> {
                        hidePanel()
                        action.onApproved()
                    }
                    is ActionGateResult.AwaitingUserApproval -> showHandoffResult("Approval required", approved.authorization.reason)
                    is ActionGateResult.Rejected -> showHandoffResult(action.blockedTitle, approved.authorization.reason)
                }
            }.onFailure {
                showHandoffResult(action.blockedTitle, "ONE AI could not verify approval for this action.")
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
            registry.providers().firstNotNullOfOrNull { it.followUp(safeImage.absolutePath, host)?.let { followUp -> it to followUp } }
                ?.let { (provider, followUp) ->
                    addLabel(box, followUp.note, 11f)
                    addButton(box, followUp.label) { perform(followUp.action, provider) }
                }
        }
        addButton(box, "Back to services") { showPanel() }
        addButton(box, "Close panel") { hidePanel() }
        attachPanel(box)
    }

    private fun hidePanel() { panel?.let { runCatching { windowManager.removeView(it) } }; panel=null }
    private fun overlayType() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == BubbleResultHandoff.ACTION_SHOW_RESULT) {
            if (bubble == null) showBubble()
            showHandoffResult(
                intent.getStringExtra(BubbleResultHandoff.EXTRA_TITLE),
                intent.getStringExtra(BubbleResultHandoff.EXTRA_CONTENT),
                intent.getStringExtra(BubbleResultHandoff.EXTRA_IMAGE_PATH)
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        registry.providers().forEach { runCatching { it.onHostStopped() } }
        serviceJob.cancel()
        uiHandler.removeCallbacksAndMessages(null)
        hidePanel()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        bubbleParams = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "one_ai_bubble"
        const val NOTIFICATION_ID = 7104
    }
}
