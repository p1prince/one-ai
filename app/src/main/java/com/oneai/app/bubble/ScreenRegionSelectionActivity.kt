package com.oneai.app.bubble

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream

/** Full-screen selector shown outside the Bubble; the screenshot is never rendered in Bubble. */
class ScreenRegionSelectionActivity : Activity() {
    private lateinit var bitmap: Bitmap
    private lateinit var selection: RegionOverlay
    private lateinit var image: ImageView
    private lateinit var sourceFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        val path = intent.getStringExtra(EXTRA_SCREEN_PATH)
        sourceFile = path?.let(::File) ?: run { finish(); return }
        if (!sourceFile.isFile || !sourceFile.canonicalPath.startsWith(cacheDir.canonicalPath + File.separator)) {
            finish(); return
        }
        bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath) ?: run { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101114.toInt())
        }
        val preview = FrameLayout(this)
        image = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        preview.addView(image, FrameLayout.LayoutParams(-1, -1))
        selection = RegionOverlay(this)
        preview.addView(selection, FrameLayout.LayoutParams(-1, -1))
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 10, 16, 12)
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF1A1C20.toInt())
        }
        controls.addView(TextView(this).apply {
            text = "Drag around the area you want ONE AI to inspect."
            setTextColor(0xFFF5F3EE.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 10)
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        buttons.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener {
                BubbleCaptureFeedback.send(this@ScreenRegionSelectionActivity, "Selection cancelled", "No screen region was selected.")
                finishAndRelease()
            }
        })
        buttons.addView(Button(this).apply {
            text = "Use selected area"
            setOnClickListener { saveSelection() }
        })
        controls.addView(buttons)
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun saveSelection() {
        val region = selection.region ?: run {
            android.widget.Toast.makeText(this, "Drag to select an area first.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val source = ScreenRegionPolicy.mapToSource(
            region, selection.width, selection.height, bitmap.width, bitmap.height
        ) ?: run {
            android.widget.Toast.makeText(this, "That selection is too small.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val crop = Bitmap.createBitmap(bitmap, source.left, source.top, source.width, source.height)
        val outputDir = File(cacheDir, "one-ai-selected").apply { mkdirs() }
        val output = File(outputDir, "region-${System.currentTimeMillis()}.png")
        FileOutputStream(output).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        crop.recycle()
        BubbleCaptureFeedback.send(
            this,
            "Screen region selected",
            "Your selected image is saved for the Screen Intelligence integration. Image interpretation is the next integration step.",
            output.absolutePath
        )
        finishAndRelease()
    }

    private fun finishAndRelease() {
        if (::bitmap.isInitialized && !bitmap.isRecycled) bitmap.recycle()
        runCatching { sourceFile.delete() }
        finish()
    }

    override fun onDestroy() {
        if (::bitmap.isInitialized && !bitmap.isRecycled) bitmap.recycle()
        super.onDestroy()
    }

    private inner class RegionOverlay(context: android.content.Context) : View(context) {
        private val shade = Paint().apply { color = 0x55000000 }
        private val border = Paint().apply {
            color = 0xFFFFC857.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val fill = Paint().apply { color = 0x33FFC857 }
        private var startX = 0f
        private var startY = 0f
        private var endX = 0f
        private var endY = 0f
        private var dragging = false
        var region: ScreenRegion? = null
            private set

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
            val current = region
            if (dragging || current != null) {
                val rect = if (dragging) RectF(startX, startY, endX, endY).apply { sort() }
                else RectF(current!!.left.toFloat(), current.top.toFloat(), current.right.toFloat(), current.bottom.toFloat())
                canvas.drawRect(rect, fill)
                canvas.drawRect(rect, border)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x.coerceIn(0f, width.toFloat())
                    startY = event.y.coerceIn(0f, height.toFloat())
                    endX = startX; endY = startY
                    dragging = true; region = null; invalidate(); return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return false
                    endX = event.x.coerceIn(0f, width.toFloat())
                    endY = event.y.coerceIn(0f, height.toFloat())
                    invalidate(); return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) return false
                    endX = event.x.coerceIn(0f, width.toFloat())
                    endY = event.y.coerceIn(0f, height.toFloat())
                    region = ScreenRegionPolicy.normalize(startX, startY, endX, endY, width, height)
                    dragging = false; invalidate(); return true
                }
            }
            return true
        }
    }

    companion object { const val EXTRA_SCREEN_PATH = "com.oneai.capture.SCREEN_PATH" }
}
