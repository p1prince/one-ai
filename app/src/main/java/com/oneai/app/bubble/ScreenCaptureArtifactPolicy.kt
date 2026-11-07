package com.oneai.app.bubble

import java.io.File

/** Pure validation rules for private selected-screen artifacts. */
object ScreenCaptureArtifactPolicy {
    const val MAX_ARTIFACT_BYTES = 20L * 1024L * 1024L
    const val MAX_AGE_MS = 10 * 60 * 1000L

    fun isValidFile(rootDirectory: File, candidate: File): Boolean = runCatching {
        val root = rootDirectory.canonicalFile
        val file = candidate.canonicalFile
        file.isFile &&
            file.canonicalPath.startsWith(root.canonicalPath + File.separator) &&
            file.length() in 1..MAX_ARTIFACT_BYTES
    }.getOrDefault(false)

    fun isFresh(createdAt: Long, now: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean =
        createdAt > 0L && now >= createdAt && maxAgeMs >= 0L && now - createdAt <= maxAgeMs
}
