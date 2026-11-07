package com.oneai.app.plugins

import android.content.Context
import com.oneai.app.ai.AiVisionProvider
import com.oneai.app.bubble.ScreenCaptureArtifactPolicy
import com.oneai.app.bubble.ScreenCaptureArtifactStore
import java.io.File

/** Trusted optional adapter: only consumes the private, one-time selected-screen artifact. */
class ScreenIntelligenceAdapter(
    private val context: Context,
    private val provider: AiVisionProvider
) : PluginAdapter {
    override val manifest = PluginManifest(
        id = PLUGIN_ID,
        name = "Screen Intelligence",
        version = "1.0.0",
        description = "A permission-gated screen understanding integration.",
        capabilities = listOf("screen_capture", "internet")
    )

    override suspend fun execute(input: PluginActionInput): PluginActionOutput {
        require(input.actionId == ANALYZE_ACTION && input.capability == "internet") {
            "Unsupported Screen Intelligence action."
        }
        val expectedPath = input.values["image_path"]
            ?: throw IllegalArgumentException("No selected image was provided.")
        val artifact = ScreenCaptureArtifactStore.consumeLatest(context)
            ?: throw IllegalStateException("The selected image expired or was already used.")
        try {
            val root = File(context.cacheDir, "one-ai-selected")
            require(artifact.absolutePath == File(expectedPath).canonicalPath &&
                ScreenCaptureArtifactPolicy.isValidFile(root, artifact)) {
                "The selected image is no longer valid."
            }
            val bytes = artifact.readBytes()
            val mime = ScreenIntelligenceImagePolicy.mimeType(bytes)
                ?: throw IllegalArgumentException("The selected image format is not supported.")
            val completion = provider.analyzeImage(
                "Describe the selected screen region clearly. Identify visible text and relevant interface elements. Do not assume or perform actions.",
                bytes,
                mime
            )
            return PluginActionOutput("Screen analysis", completion.content)
        } finally {
            artifact.delete()
        }
    }

    companion object {
        const val PLUGIN_ID = "one.screen.intelligence"
        const val ANALYZE_ACTION = "analyze_selected_image"
    }
}
