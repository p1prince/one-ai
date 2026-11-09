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
import android.view.ScaleGestureDetector
import android.view.GestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.oneai.app.AppServices
import com.oneai.app.core.PluginInvocationResult
import com.oneai.app.core.PluginActionExecutor
import com.oneai.app.security.PluginActionApprovalGateway
import com.oneai.app.security.ActionGateResult
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
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelMaxHeightPx: Int = 0
    private val uiHandler = Handler(Looper.getMainLooper())
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val registry get() = BubbleProviders.registry
    private var panelToken = 0
    private val runs = BubbleRunState()

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
        AppServices.get(application).pluginLaunchApprovalController.attach(approvalPresenter)
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
        BubbleUiPolicy.serviceLabel(service, registry.provider(service)?.title)

    private fun showPanel() {
        val services = AppServices.get(application)
        if (services.pluginRestoration.isReady) {
            showPanelWhenReady(services)
            return
        }
        val loading = basePanel()
        addLabel(loading, "ONE AI  •  QUICK PANEL", 16f)
        addLabel(loading, "Restoring trusted plugins…")
        addButton(loading, "Close panel") { hidePanel() }
        attachPanel(loading)
        val loadingPanelToken = panelToken
        serviceScope.launch {
            services.pluginRestoration.awaitReady()
            if (serviceJob.isActive) uiHandler.post {
                if (serviceJob.isActive && panel != null && panelToken == loadingPanelToken) {
                    showPanelWhenReady(services)
                }
            }
        }
    }

    private fun showPanelWhenReady(services: AppServices) {
        if (services.pluginLaunchApprovalController.presentPending()) return
        val store = services.store
        val enabledPlugins = store.plugins().filter { it.enabled }.map { it.id }.toSet()
        // Both plugin enablement and its separate Bubble-access toggle are required.
        val enabled = BubbleUiPolicy.panelServices(enabledPlugins, store.bubbleServices(), registry.bindings(), store.bubbleServiceOrder())
        val box = basePanel()
        addLabel(box, "ONE AI  •  QUICK PANEL", 16f)
        if (enabled.isEmpty()) {
            addLabel(box, "No services enabled. Choose services in ONE AI Settings.")
        } else {
            enabled.forEach { service ->
                addButton(box, titleFor(service)) { showService(service) }
            }
        }
        addButton(box, "Hide bubble for 1 minute") { hideBubbleTemporarily() }
        addButton(box, "Close panel") { hidePanel() }
        attachPanel(box)
    }

    /** Service views are rendered inside the overlay; they never redirect to MainActivity. */
    private fun showService(service: String, loaded: Result<BubbleView>? = null) {
        val box = basePanel()
        addLabel(box, "ONE AI  •  " + titleFor(service), 16f)
        if (service == BubbleUiPolicy.COMMAND_MANAGER) {
            renderCommandManager(box)
        } else {
            val provider = registry.provider(service)
            when {
                provider == null -> addLabel(box, "This service is enabled, but no compatible provider is connected yet.")
                provider.isAsync && loaded == null -> {
                    addLabel(box, "Loading…")
                    loadAsync(service, provider)
                }
                provider.isAsync -> loaded!!.fold(
                    { renderView(box, provider, it) },
                    { addLabel(box, "This service could not be loaded: " + (it.message ?: "unknown error").take(200)) }
                )
                else -> renderView(box, provider, provider.view(host))
            }
        }
        addButton(box, "Back to services") { showPanel() }
        addButton(box, "Close panel") { hidePanel() }
        attachPanel(box)
    }

    /** Fetches a view that lives in another app, then redraws the panel if the user is still looking at it. */
    private fun loadAsync(service: String, provider: BubbleServiceProvider) {
        val expected = panelToken + 1 // the token the panel being built right now will receive
        serviceScope.launch {
            val loaded = runCatching { provider.loadView(host) }
            if (!serviceJob.isActive) return@launch
            uiHandler.post {
                if (serviceJob.isActive && panel != null && panelToken == expected) showService(service, loaded)
            }
        }
    }

    private fun renderCommandManager(box: LinearLayout) {
        val services = AppServices.get(application)
        val saved = services.store.commands().filter { it.enabled }
        val bubbleEnabledServices = services.store.bubbleServices()
        val bubbleEnabledOwners = BubbleProviders.registry.bindings()
            .filterValues { serviceId -> serviceId in bubbleEnabledServices }.keys
        val extensions = services.commandRegistry.list().filter { !it.isCore && it.owner in bubbleEnabledOwners }
        if (saved.isEmpty() && extensions.isEmpty()) {
            addLabel(box, "No enabled commands are available. Create commands or enable an extension that contributes commands.")
        }
        saved.forEach { command ->
            addButton(box, "Run: ${command.title}") {
                runRegisteredCommand(box, "core/run_saved", command.title, mapOf("id" to command.id))
            }
        }
        extensions.forEach { command ->
            addButton(box, "${command.title}  •  ${command.owner}") {
                runRegisteredCommand(box, command.id, command.title)
            }
        }
    }

    /** Home and Bubble share the same process-wide registry, executor and host security boundary. */
    private fun runRegisteredCommand(box: LinearLayout, commandId: String, title: String, args: Map<String, String> = emptyMap()) {
        val services = AppServices.get(application)
        val output = addLabel(box, "Running $title…", 12f)
        serviceScope.launch {
            services.pluginRestoration.awaitReady()
            val result = services.commandExecutor.execute(commandId, args)
            if (!serviceJob.isActive) return@launch
            if (result.status == com.oneai.app.commands.CommandStatus.AWAITING_APPROVAL) {
                val definition = services.commandRegistry.definition(commandId)
                val input = services.commandExecutor.actionFor(commandId, args)
                uiHandler.post {
                    if (serviceJob.isActive && definition != null && input != null) showCommandApproval(commandId, definition.owner, input, title)
                    else if (serviceJob.isActive) showHandoffResult("Command unavailable", "ONE AI could not prepare the required approval.")
                }
            } else {
                val message = if (result.status == com.oneai.app.commands.CommandStatus.COMPLETED) result.content
                else result.error?.message ?: result.content.ifBlank { "The command could not be completed." }
                uiHandler.post { if (serviceJob.isActive) output.text = message }
            }
        }
    }

    private fun showCommandApproval(commandId: String, pluginId: String, input: com.oneai.app.core.PluginActionInput, title: String) {
        val services = AppServices.get(application)
        val box = basePanel()
        addLabel(box, "Approval required", 16f)
        addLabel(box, "Allow $title from $pluginId to run?")
        addButton(box, "Cancel") { showService(BubbleUiPolicy.COMMAND_MANAGER) }
        addButton(box, "Approve and run") {
            val ticket = services.pluginExecution.executor.approveSensitiveAction(pluginId, input).getOrElse {
                showHandoffResult("Approval unavailable", "ONE AI could not verify approval for this command.")
                return@addButton
            }
            launchRun(title, commandId, { services.commandExecutor.execute(commandId, input.values, ticket) }) { result ->
                showHandoffResult(
                    result.title.ifBlank { title },
                    if (result.status == com.oneai.app.commands.CommandStatus.COMPLETED) result.content
                    else result.error?.message ?: result.content.ifBlank { "The command could not be completed." }
                )
            }
        }
        attachPanel(box)
    }

    /**
     * Runs one approved call behind the host-drawn "Running" panel with Cancel (decision D11 / D-T).
     * The panel text is the command title only, never plugin text. Cancel cancels the job (the adapter then
     * cancels the request in the plugin); hiding the panel does not cancel. A result that arrives after
     * Cancel is dropped, and a failure is shown instead of crashing the service.
     */
    private fun <T> launchRun(title: String?, fallback: String, work: suspend () -> T, onResult: (T) -> Unit) {
        val id = runs.begin()
        showRunning(id, title, fallback)
        val job = serviceScope.launch {
            val outcome: Result<T> = try {
                Result.success(work())
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure<T>(error)
            }
            if (!serviceJob.isActive) return@launch
            uiHandler.post {
                if (!serviceJob.isActive || !runs.complete(id)) return@post
                outcome.fold(
                    onSuccess = onResult,
                    onFailure = { showHandoffResult("Command failed", "The command could not be completed.") }
                )
            }
        }
        runs.attach(id, job)
    }

    private fun showRunning(id: Long, title: String?, fallback: String) {
        val box = basePanel()
        addLabel(box, "ONE AI  •  RUNNING", 16f)
        addLabel(box, BubbleRunning.heading(title, fallback), 14f)
        addLabel(box, BubbleRunning.HIDDEN_NOTE, 11f)
        addButton(box, "Cancel") {
            if (runs.cancel(id)) showHandoffResult(BubbleRunning.CANCELLED_TITLE, BubbleRunning.CANCELLED_NOTE)
        }
        addButton(box, "Close panel") { hidePanel() }
        attachPanel(box)
    }

    /** Generic rendering only; plugin launch policy lives in the host plugin layer. */
    private val approvalPresenter = object : BubbleHostApprovalPresenter {
        override fun showApproval(title: String, details: String, warning: String, approve: () -> String, reject: () -> Unit) {
            uiHandler.post {
                if (!serviceJob.isActive) return@post
                val box = basePanel()
                addLabel(box, title, 16f)
                addLabel(box, details)
                addLabel(box, warning, 12f)
                addButton(box, "Reject") {
                    reject()
                    showHandoffResult("Request rejected", "ONE AI did not perform the requested action.")
                }
                addButton(box, "Allow once") {
                    val result = approve()
                    showHandoffResult("Host approval", result)
                }
                addButton(box, "Close panel") { hidePanel() }
                attachPanel(box)
            }
        }

        override fun showResult(title: String, message: String) {
            uiHandler.post { if (serviceJob.isActive) showHandoffResult(title, message) }
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
        val services = AppServices.get(application)
        val executor: PluginActionExecutor = services.pluginExecution.executor
        launchRun(call.runTitle, call.input.actionId, {
            services.pluginRestoration.awaitReady()
            provider.ensureReady(host).map { executor.invoke(call.pluginId, call.input) }
        }) { prepared ->
            prepared.fold(
                onSuccess = { decision ->
                    if (decision is PluginInvocationResult.AwaitingApproval) showApproval(call, executor)
                    else show(call.outcomes.present(decision))
                },
                onFailure = { showHandoffResult(call.outcomes.unavailableTitle, it.message ?: "Could not start this service.") }
            )
        }
    }

    private fun showApproval(call: BubbleAction.PluginCall, runtime: PluginActionExecutor) {
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
            launchRun(call.runTitle, call.input.actionId, { runtime.invoke(call.pluginId, call.input, ticket) }) { result ->
                show(call.outcomes.present(result))
            }
        }
        attachPanel(box)
    }

    /** The gateway issues and consumes the approval; the contributor then performs the action. */
    private fun runGatedHostAction(action: BubbleAction.GatedHostAction) {
        val gateway: PluginActionApprovalGateway = AppServices.get(application).approvalGateway
        val request = com.oneai.app.security.ActionRequest(action.request.pluginId, action.request.actionId, action.request.capability, action.request.description)
        when (val decision = gateway.check(request)) {
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
                val ticket = gateway.confirmUserApproval(request)
                when (val approved = gateway.check(request, ticket)) {
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

    /**
     * The expanded interface is a separate overlay, but its position is anchored to the
     * floating icon. Its dimensions are independent of the icon and can be changed by pinching.
     */
    private fun attachPanel(box: LinearLayout) {
        hidePanel()
        panelToken++
        val prefs = getSharedPreferences("one_ai_bubble_ui", MODE_PRIVATE)
        val density = resources.displayMetrics.density
        val screenWidthDp = (resources.displayMetrics.widthPixels / density).toInt()
        val screenHeightDp = (resources.displayMetrics.heightPixels / density).toInt()
        val widthDp = BubbleUiPolicy.safePanelWidthDp(
            prefs.getInt("panel_width_dp", (screenWidthDp * 0.84f).toInt()), screenWidthDp
        )
        val heightDp = BubbleUiPolicy.safePanelHeightDp(
            prefs.getInt("panel_height_dp", (screenHeightDp * 0.72f).toInt()), screenHeightDp
        )
        val widthPx = (widthDp * density).toInt()
        panelMaxHeightPx = (heightDp * density).toInt()
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(panelMaxHeightPx, MeasureSpec.AT_MOST))
            }
        }.apply {
            isFillViewport = false
            addView(box, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val icon = bubbleParams
        val anchoredX = if (icon == null) dp(16) else
            if (icon.x + icon.width + widthPx <= resources.displayMetrics.widthPixels)
                icon.x + icon.width + dp(8)
            else icon.x - widthPx - dp(8)
        val anchoredY = icon?.y ?: dp(80)
        val savedPosition = if (prefs.contains("panel_x") && prefs.contains("panel_y")) {
            BubblePosition(prefs.getInt("panel_x", anchoredX), prefs.getInt("panel_y", anchoredY))
        } else BubblePosition(anchoredX, anchoredY)
        val position = BubbleUiPolicy.clampPosition(
            savedPosition.x, savedPosition.y, widthPx, panelMaxHeightPx,
            resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels
        )
        val params = WindowManager.LayoutParams(
            widthPx, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = position.x; y = position.y
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        // Pinch anywhere on the panel to resize both dimensions. One-finger gestures still
        // scroll normally; double-tap restores the default panel dimensions.
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = panelParams ?: return false
                val nextWidthDp = BubbleUiPolicy.safePanelWidthDp(
                    (current.width / density * detector.scaleFactor).toInt(), screenWidthDp
                )
                val nextHeightDp = BubbleUiPolicy.safePanelHeightDp(
                    (panelMaxHeightPx / density * detector.scaleFactor).toInt(), screenHeightDp
                )
                current.width = (nextWidthDp * density).toInt()
                panelMaxHeightPx = (nextHeightDp * density).toInt()
                val safe = BubbleUiPolicy.clampPosition(
                    current.x, current.y, current.width, panelMaxHeightPx,
                    resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels
                )
                current.x = safe.x; current.y = safe.y
                prefs.edit().putInt("panel_width_dp", nextWidthDp)
                    .putInt("panel_height_dp", nextHeightDp)
                    .putInt("panel_x", current.x).putInt("panel_y", current.y).apply()
                scroll.requestLayout()
                runCatching { windowManager.updateViewLayout(scroll, current) }
                return true
            }
        })
        val doubleTapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val current = panelParams ?: return false
                val defaultWidth = BubbleUiPolicy.safePanelWidthDp((screenWidthDp * 0.84f).toInt(), screenWidthDp)
                val defaultHeight = BubbleUiPolicy.safePanelHeightDp((screenHeightDp * 0.72f).toInt(), screenHeightDp)
                current.width = (defaultWidth * density).toInt()
                panelMaxHeightPx = (defaultHeight * density).toInt()
                val safe = BubbleUiPolicy.clampPosition(
                    current.x, current.y, current.width, panelMaxHeightPx,
                    resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels
                )
                current.x = safe.x; current.y = safe.y
                prefs.edit().putInt("panel_width_dp", defaultWidth).putInt("panel_height_dp", defaultHeight)
                    .putInt("panel_x", current.x).putInt("panel_y", current.y).apply()
                scroll.requestLayout()
                runCatching { windowManager.updateViewLayout(scroll, current) }
                return true
            }
        })
        scroll.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            doubleTapDetector.onTouchEvent(event)
            scaleDetector.isInProgress
        }
        // Drag the title/header to reposition the open panel without moving the icon.
        (box.getChildAt(0) as? TextView)?.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX; dragStartY = event.rawY
                    panelStartX = params.x; panelStartY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val safe = BubbleUiPolicy.clampPosition(
                        panelStartX + (event.rawX - dragStartX).toInt(),
                        panelStartY + (event.rawY - dragStartY).toInt(),
                        params.width, panelMaxHeightPx,
                        resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels
                    )
                    params.x = safe.x; params.y = safe.y
                    runCatching { windowManager.updateViewLayout(scroll, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putInt("panel_x", params.x).putInt("panel_y", params.y).apply()
                    true
                }
                else -> false
            }
        }
        runCatching { windowManager.addView(scroll, params) }.onSuccess {
            panel = scroll
            panelParams = params
        }
    }

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var panelStartX = 0
    private var panelStartY = 0

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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

    private fun hidePanel() { panel?.let { runCatching { windowManager.removeView(it) } }; panel=null; panelParams=null }
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
        AppServices.get(application).pluginLaunchApprovalController.detach()
        registry.providers().forEach { runCatching { it.onHostStopped() } }
        runCatching {
            registry.providers().forEach { runCatching { it.releaseReady() } }
        }
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
