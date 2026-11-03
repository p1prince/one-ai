package com.oneai.app.bubble

/**
 * Platform-independent geometry rules for selecting a screen region.
 * Coordinates are expressed in the displayed screenshot's pixel space.
 */
data class ScreenRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object ScreenRegionPolicy {
    const val MIN_REGION_SIZE_PX = 8

    fun normalize(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        imageWidth: Int,
        imageHeight: Int
    ): ScreenRegion? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val left = minOf(startX, endX).toInt().coerceIn(0, imageWidth)
        val top = minOf(startY, endY).toInt().coerceIn(0, imageHeight)
        val right = maxOf(startX, endX).toInt().coerceIn(0, imageWidth)
        val bottom = maxOf(startY, endY).toInt().coerceIn(0, imageHeight)
        if (right - left < MIN_REGION_SIZE_PX || bottom - top < MIN_REGION_SIZE_PX) return null
        return ScreenRegion(left, top, right, bottom)
    }

    /** Maps a selection drawn in a scaled ImageView into source bitmap coordinates. */
    fun mapToSource(
        displayed: ScreenRegion,
        displayedWidth: Int,
        displayedHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): ScreenRegion? {
        if (displayedWidth <= 0 || displayedHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return null
        val left = (displayed.left.toFloat() * sourceWidth / displayedWidth).toInt().coerceIn(0, sourceWidth)
        val top = (displayed.top.toFloat() * sourceHeight / displayedHeight).toInt().coerceIn(0, sourceHeight)
        val right = (displayed.right.toFloat() * sourceWidth / displayedWidth).toInt().coerceIn(0, sourceWidth)
        val bottom = (displayed.bottom.toFloat() * sourceHeight / displayedHeight).toInt().coerceIn(0, sourceHeight)
        if (right <= left || bottom <= top) return null
        return ScreenRegion(left, top, right, bottom)
    }
}
