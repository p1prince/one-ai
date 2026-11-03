package com.oneai.app.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.oneai.app.core.OneAiStore
import com.oneai.app.ai.AiProviderClient
import com.oneai.app.ai.AiOrchestrator
import com.oneai.app.security.SecretVault
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FloatingBubbleService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private val uiHandler = Handler(Looper.getMainLooper())

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
        val size = prefs.getInt("bubble_size", 58)
        val params = WindowManager.LayoutParams(size, size, overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bubble_x", 24); y = prefs.getInt("bubble_y", 220)
        }
        view.setOnTouchListener(object : android.view.View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx=e.rawX; sy=e.rawY; ox=params.x; oy=params.y; moved=false; return true }
                    MotionEvent.ACTION_MOVE -> { val dx=(e.rawX-sx).toInt(); val dy=(e.rawY-sy).toInt(); if (kotlin.math.abs(dx)+kotlin.math.abs(dy)>8) moved=true; params.x=ox+dx; params.y=oy+dy; windowManager.updateViewLayout(view,params); return true }
                    MotionEvent.ACTION_UP -> {
                        getSharedPreferences("one_ai_bubble_ui", MODE_PRIVATE).edit().putInt("bubble_x", params.x).putInt("bubble_y", params.y).apply()
                        if (!moved) v.performClick()
                        return true
                    }
                }
                return false
            }
        })
        windowManager.addView(view, params); bubble = view; bubbleParams = params
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
        val enabled = store.bubbleServices()
        val box = basePanel()
        addLabel(box, "ONE AI  •  QUICK PANEL", 16f)
        if (enabled.isEmpty()) {
            addLabel(box, "No services enabled. Choose services in ONE AI Settings.")
        } else {
            val order = store.bubbleServiceOrder().filter { it in enabled }
            (order + enabled.filterNot { it in order }.sorted()).forEach { service ->
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
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        val result = runCatching { AiOrchestrator(AiProviderClient(OneAiStore(this@FloatingBubbleService), SecretVault(this@FloatingBubbleService)), OneAiStore(this@FloatingBubbleService)).respond(command.prompt).content }
                            .getOrElse { "Error: ${it.message}" }
                        Handler(Looper.getMainLooper()).post { output.text = result }
                    }
                }
            }
            addButton(box, "Back to services") { showPanel() }
            addButton(box, "Close panel") { hidePanel() }
            attachPanel(box)
            return
        }
        val message = when (service) {
            "web_research" -> "Web Research is enabled in this bubble. Connect a research provider in the Plugin Centre before running searches."
            "screen_intelligence" -> "Screen Intelligence requires a user-started screen-capture session. Start capture from its dedicated control when that integration is installed."
            "voice_interface" -> "Voice Interface is enabled. Microphone access and a voice provider are required before voice actions can run."
            "automation" -> "Automation is enabled. Install and explicitly enable a compatible automation plugin before running device workflows."
            else -> "This service is enabled, but no compatible provider is connected yet."
        }
        addLabel(box, message)
        addButton(box, "Back to services") { showPanel() }
        addButton(box, "Close panel") { hidePanel() }
        attachPanel(box)
    }

    private fun attachPanel(box: LinearLayout) {
        hidePanel()
        val params = WindowManager.LayoutParams((resources.displayMetrics.widthPixels*0.84f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.CENTER; x=0; y=0
        }
        windowManager.addView(box,params); panel=box
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

    private fun hidePanel() { panel?.let { runCatching { windowManager.removeView(it) } }; panel=null }
    private fun overlayType() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onDestroy() { uiHandler.removeCallbacksAndMessages(null); hidePanel(); bubble?.let { runCatching { windowManager.removeView(it) } }; bubble=null; bubbleParams=null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { const val CHANNEL="one_ai_bubble"; const val NOTIFICATION_ID=7104 }
}
