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

class FloatingBubbleService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "ONE AI Bubble", NotificationManager.IMPORTANCE_LOW))
        return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
            .setContentTitle("ONE AI bubble active").setContentText("Tap the floating ONE icon to open quick actions.")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build()
        else @Suppress("DEPRECATION") Notification.Builder(this).setContentTitle("ONE AI bubble active")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build()
    }

    private fun showBubble() {
        val view = TextView(this).apply {
            text = "1"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.BLACK)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(214,174,88)) }
            setOnClickListener { if (panel == null) showPanel() else hidePanel() }
        }
        val params = WindowManager.LayoutParams(58, 58, overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = 24; y = 220
        }
        view.setOnTouchListener(object : android.view.View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx=e.rawX; sy=e.rawY; ox=params.x; oy=params.y; moved=false; return true }
                    MotionEvent.ACTION_MOVE -> { val dx=(e.rawX-sx).toInt(); val dy=(e.rawY-sy).toInt(); if (kotlin.math.abs(dx)+kotlin.math.abs(dy)>8) moved=true; params.x=ox+dx; params.y=oy+dy; windowManager.updateViewLayout(view,params); return true }
                    MotionEvent.ACTION_UP -> { if (!moved) v.performClick(); return true }
                }; return false
            }
        })
        windowManager.addView(view, params); bubble = view
    }

    private fun showPanel() {
        val store = OneAiStore(this)
        val enabled = store.bubbleServices()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(22,18,22,18)
            background = GradientDrawable().apply { setColor(Color.rgb(26,28,32)); cornerRadius=28f; setStroke(2,Color.rgb(214,174,88)) }
        }
        box.addView(TextView(this).apply { text="ONE AI  •  QUICK PANEL"; setTextColor(Color.rgb(214,174,88)); textSize=16f })
        if (enabled.isEmpty()) box.addView(TextView(this).apply { text="No services enabled. Enable bubble services in ONE AI Settings."; setTextColor(Color.WHITE); textSize=13f; setPadding(0,18,0,12) })
        enabled.forEach { service ->
            box.addView(Button(this).apply {
                text = service.replace('_',' ').replaceFirstChar { it.uppercase() }
                setOnClickListener {
                    val launch = packageManager.getLaunchIntentForPackage(packageName)
                    launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    launch?.putExtra("one_ai_bubble_service", service)
                    if (launch != null) startActivity(launch)
                }
            })
        }
        box.addView(Button(this).apply { text="Close panel"; setOnClickListener { hidePanel() } })
        val params = WindowManager.LayoutParams((resources.displayMetrics.widthPixels*0.82f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.CENTER; x=0; y=0
        }
        windowManager.addView(box,params); panel=box; panelParams=params
    }

    private fun hidePanel() { panel?.let { runCatching { windowManager.removeView(it) } }; panel=null; panelParams=null }
    private fun overlayType() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onDestroy() { hidePanel(); bubble?.let { runCatching { windowManager.removeView(it) } }; bubble=null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { const val CHANNEL="one_ai_bubble"; const val NOTIFICATION_ID=7104 }
}
