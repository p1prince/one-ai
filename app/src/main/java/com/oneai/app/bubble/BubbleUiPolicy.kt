package com.oneai.app.bubble

/** Pure policy for Bubble preferences and layout; kept Android-free for deterministic tests. */
data class BubblePosition(val x: Int, val y: Int)

object BubbleUiPolicy {
    const val MIN_SIZE_DP = 40
    const val MAX_SIZE_DP = 96
    const val DEFAULT_SIZE_DP = 58
    const val MIN_PANEL_WIDTH_DP = 180
    const val MAX_PANEL_WIDTH_DP = 560
    const val MIN_PANEL_HEIGHT_DP = 180

    fun safeSize(savedSize: Int): Int = savedSize.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)

    fun safePanelWidthDp(savedWidth: Int, screenWidthDp: Int): Int {
        val maxWidth = (screenWidthDp - 24).coerceAtLeast(MIN_PANEL_WIDTH_DP)
        return savedWidth.coerceIn(MIN_PANEL_WIDTH_DP, minOf(MAX_PANEL_WIDTH_DP, maxWidth))
    }

    fun safePanelHeightDp(savedHeight: Int, screenHeightDp: Int): Int {
        val maxHeight = (screenHeightDp - 48).coerceAtLeast(MIN_PANEL_HEIGHT_DP)
        return savedHeight.coerceIn(MIN_PANEL_HEIGHT_DP, maxHeight)
    }

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

    const val COMMAND_MANAGER = "command_manager"

    /** Name shown for a Bubble service: the provider's own title, else a capitalised form of its ID ("Command Manager" for the core service). */
    fun serviceLabel(serviceId: String, providerTitle: String?): String = when {
        providerTitle != null -> providerTitle
        serviceId == COMMAND_MANAGER -> "Command Manager"
        else -> serviceId.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** Tallest the quick panel may be. The panel itself wraps its content and only scrolls past this height. */
    fun panelMaxHeightPx(screenHeightPx: Int): Int = (screenHeightPx * 0.78f).toInt().coerceAtLeast(320)

    /**
     * Core command service plus the Bubble service of every enabled plugin that has a binding.
     * [bindings] maps plugin ID to service ID; nothing about specific plugins is known here.
     */
    fun availableServices(enabledPluginIds: Set<String>, bindings: Map<String, String>): Set<String> = buildSet {
        add(COMMAND_MANAGER)
        bindings.forEach { (pluginId, service) -> if (pluginId in enabledPluginIds) add(service) }
    }

    fun visibleServices(savedServices: Set<String>, availableServices: Set<String>): Set<String> =
        savedServices.intersect(availableServices)

    /** Both plugin enablement and explicit Bubble access are required for a service to appear. */
    fun panelServices(
        enabledPluginIds: Set<String>,
        selectedBubbleServices: Set<String>,
        bindings: Map<String, String>,
        savedOrder: List<String> = emptyList()
    ): List<String> {
        val available = availableServices(enabledPluginIds, bindings)
        val visible = visibleServices(selectedBubbleServices, available)
        return orderedServices(savedOrder, visible)
    }

    fun orderedServices(savedOrder: List<String>, enabledServices: Set<String>): List<String> {
        val ordered = savedOrder.filter { it in enabledServices }.distinct()
        return ordered + (enabledServices - ordered.toSet()).sorted()
    }
}
