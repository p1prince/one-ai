package com.oneai.app.bubble

import android.content.Context
import java.io.File

/**
 * Private, one-time hand-off for the selected image.
 * Artifacts live only in app cache and expire after ten minutes.
 */
object ScreenCaptureArtifactStore {
    private const val PREFS = "one_ai_capture_artifacts"
    private const val KEY_PATH = "latest_selected_image"
    private const val KEY_CREATED = "latest_selected_created"

    @Synchronized
    fun publish(context: Context, image: File): Boolean {
        val safe = validatedFile(context, image.absolutePath) ?: return false
        clear(context)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PATH, safe.absolutePath)
            .putLong(KEY_CREATED, System.currentTimeMillis())
            .commit()
    }

    /** Returns and consumes the latest valid artifact. It cannot be replayed. */
    @Synchronized
    fun consumeLatest(context: Context): File? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_PATH, null)
        val created = prefs.getLong(KEY_CREATED, 0L)
        prefs.edit().remove(KEY_PATH).remove(KEY_CREATED).commit()
        if (path == null || created <= 0L || !ScreenCaptureArtifactPolicy.isFresh(created, System.currentTimeMillis())) {
            path?.let { validatedFile(context, it)?.delete() }
            return null
        }
        return validatedFile(context, path)
    }

    @Synchronized
    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_PATH, null)?.let { validatedFile(context, it)?.delete() }
        prefs.edit().remove(KEY_PATH).remove(KEY_CREATED).commit()
    }

    private fun validatedFile(context: Context, path: String): File? {
        val root = File(context.cacheDir, "one-ai-selected")
        val file = runCatching { File(path) }.getOrNull() ?: return null
        return file.takeIf { ScreenCaptureArtifactPolicy.isValidFile(root, it) }
    }
}
