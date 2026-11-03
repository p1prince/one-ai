package com.oneai.app.bubble

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * The system-owned MediaProjection prompt is always shown before capture.
 * A fresh consent token is requested for every capture session.
 */
class ScreenCaptureConsentActivity : ComponentActivity() {
    private val consent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val service = Intent(this, ScreenProjectionCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, result.resultCode)
                putExtra(EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        } else {
            BubbleCaptureFeedback.send(this, "Screen capture cancelled", "Android screen-capture permission was not granted. Nothing was captured.")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        consent.launch(manager.createScreenCaptureIntent())
    }

    companion object {
        const val EXTRA_RESULT_CODE = "com.oneai.capture.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.oneai.capture.RESULT_DATA"
        fun launch(context: Context) {
            context.startActivity(Intent(context, ScreenCaptureConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

internal object BubbleCaptureFeedback {
    fun send(context: Context, title: String, content: String, imagePath: String? = null) {
        val intent = Intent(context, FloatingBubbleService::class.java).apply {
            action = BubbleResultHandoff.ACTION_SHOW_RESULT
            putExtra(BubbleResultHandoff.EXTRA_TITLE, title)
            putExtra(BubbleResultHandoff.EXTRA_CONTENT, content)
            if (imagePath != null) putExtra(BubbleResultHandoff.EXTRA_IMAGE_PATH, imagePath)
        }
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }
}
