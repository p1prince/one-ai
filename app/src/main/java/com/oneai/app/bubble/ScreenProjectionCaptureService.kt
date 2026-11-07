package com.oneai.app.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/** Captures one frame only, after explicit system consent, then releases the projection. */
class ScreenProjectionCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var delivered = false
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val captureTimeout = Runnable {
        if (!delivered) {
            BubbleCaptureFeedback.send(this, "Screen capture timed out", "Android did not provide a screen frame in time. Please try again.")
            releaseCapture()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(ScreenCaptureConsentActivity.EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(ScreenCaptureConsentActivity.EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(ScreenCaptureConsentActivity.EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || resultData == null || projection != null) {
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching {
            startCaptureForeground()
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            beginSingleFrameCapture()
        }.onFailure {
            BubbleCaptureFeedback.send(this, "Screen capture failed", "ONE AI could not start the Android capture session.")
            releaseCapture()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCaptureForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "ONE AI Screen Capture", NotificationManager.IMPORTANCE_LOW))
            val notification = Notification.Builder(this, CHANNEL)
                .setContentTitle("ONE AI screen capture")
                .setContentText("Capturing one frame for your selected task.")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else startForeground(NOTIFICATION_ID, notification)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, Notification.Builder(this)
                .setContentTitle("ONE AI screen capture")
                .setSmallIcon(android.R.drawable.ic_menu_camera).build())
        }
    }

    private fun beginSingleFrameCapture() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi.coerceAtLeast(1)
        val activeProjection = projection ?: error("Missing projection")
        activeProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                releaseCapture()
                stopSelf()
            }
        }, null)
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ source ->
            if (delivered) {
                source.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            var padded: Bitmap? = null
            var bitmap: Bitmap? = null
            var partialFile: File? = null
            try {
                val plane = image.planes.first()
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val paddedWidth = rowStride / pixelStride
                val paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                padded = paddedBitmap
                val buffer: ByteBuffer = plane.buffer
                paddedBitmap.copyPixelsFromBuffer(buffer)
                val croppedBitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
                bitmap = croppedBitmap
                val output = File(cacheDir, "one-ai-capture").apply { mkdirs() }
                val file = File(output, "screen-${System.currentTimeMillis()}.png")
                partialFile = file
                FileOutputStream(file).use {
                    check(croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                        "Could not encode the captured screen frame"
                    }
                }
                val selector = Intent(this, ScreenRegionSelectionActivity::class.java).apply {
                    putExtra(ScreenRegionSelectionActivity.EXTRA_SCREEN_PATH, file.absolutePath)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(selector)
                partialFile = null
                delivered = true
                timeoutHandler.removeCallbacks(captureTimeout)
                releaseCapture()
                stopSelf()
            } catch (_: Exception) {
                partialFile?.let { runCatching { it.delete() } }
                BubbleCaptureFeedback.send(this, "Screen capture failed", "ONE AI could not prepare the captured frame.")
                releaseCapture()
                stopSelf()
            } finally {
                image.close()
                padded?.let { if (!it.isRecycled) it.recycle() }
                bitmap?.let { if (!it.isRecycled) it.recycle() }
            }
        }, null)
        display = activeProjection.createVirtualDisplay(
            "ONE AI single frame capture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )
        timeoutHandler.postDelayed(captureTimeout, 5_000L)
    }

    private fun releaseCapture() {
        timeoutHandler.removeCallbacks(captureTimeout)
        runCatching { display?.release() }; display = null
        runCatching { reader?.setOnImageAvailableListener(null, null); reader?.close() }; reader = null
        // Clear the reference before stop(): MediaProjection may deliver onStop while
        // this cleanup is running, and the callback must not recursively stop it.
        val activeProjection = projection
        projection = null
        runCatching { activeProjection?.stop() }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onDestroy() { timeoutHandler.removeCallbacksAndMessages(null); releaseCapture(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object { private const val CHANNEL = "one_ai_capture"; private const val NOTIFICATION_ID = 7105 }
}
