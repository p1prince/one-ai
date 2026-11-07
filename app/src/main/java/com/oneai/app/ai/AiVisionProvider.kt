package com.oneai.app.ai

/** Provider-neutral image understanding contract used only by optional vision plugins. */
interface AiVisionProvider {
    suspend fun analyzeImage(prompt: String, imageBytes: ByteArray, mimeType: String): AiCompletion
}
