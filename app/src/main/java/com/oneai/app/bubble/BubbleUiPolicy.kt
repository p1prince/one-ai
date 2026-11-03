package com.oneai.app.bubble

/** Pure policy for Bubble preferences and layout; kept Android-free for deterministic tests. */
data class BubblePosition(val x: Int, val y: Int)

object BubbleUiPolicy {
    const val MIN_SIZE_DP = 40
    const val MAX_SIZE_DP = 96
    const val DEFAULT_SIZE_DP = 58

    fun safeSize(savedSize: Int): Int = savedSize.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)

    fun clampPosition(
        x: Int,
        y: Int,
        bubbleWidth: Int,
        bubbleHeight: Int,
        screenWidth: Int,
        screenHeight: Int
    ): BubblePosition {
        val maxX = (screenWidth - bubbleWidth).coerceAtLeast(0)
        val maxY = (screenHeight - bubbleHeight).coerceAtLeast(0)
        return BubblePosition(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }

    fun availableServices(enabledPluginIds: Set<String>): Set<String> = buildSet {
        add("command_manager")
        if ("one.web.research" in enabledPluginIds) add("web_research")
        if ("one.screen.intelligence" in enabledPluginIds) add("screen_intelligence")
        if ("one.voice.interface" in enabledPluginIds) add("voice_interface")
        if ("one.automation.engine" in enabledPluginIds) add("automation")
    }

    fun visibleServices(savedServices: Set<String>, availableServices: Set<String>): Set<String> =
        savedServices.intersect(availableServices)

    fun orderedServices(savedOrder: List<String>, enabledServices: Set<String>): List<String> {
        val ordered = savedOrder.filter { it in enabledServices }.distinct()
        return ordered + (enabledServices - ordered.toSet()).sorted()
    }
}
