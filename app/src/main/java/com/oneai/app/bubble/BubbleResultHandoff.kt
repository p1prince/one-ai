package com.oneai.app.bubble

/** Validated text-result contract for trusted feature modules to return results to the Bubble. */
data class BubbleHandoffResult(val title: String, val content: String)

object BubbleResultHandoff {
    const val ACTION_SHOW_RESULT = "com.oneai.app.bubble.SHOW_RESULT"
    const val EXTRA_TITLE = "com.oneai.app.bubble.extra.TITLE"
    const val EXTRA_CONTENT = "com.oneai.app.bubble.extra.CONTENT"
    const val MAX_TITLE_LENGTH = 100
    const val MAX_CONTENT_LENGTH = 12_000

    fun validate(title: String?, content: String?): BubbleHandoffResult? {
        val safeTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val safeContent = content?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (safeTitle.length > MAX_TITLE_LENGTH || safeContent.length > MAX_CONTENT_LENGTH) return null
        return BubbleHandoffResult(safeTitle, safeContent)
    }
}
