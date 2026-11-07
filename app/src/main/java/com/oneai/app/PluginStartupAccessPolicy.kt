package com.oneai.app

/**
 * Keeps plugin-management controls unavailable until startup restoration has reconciled
 * persisted plugin state with the current trust registry.
 */
internal object PluginStartupAccessPolicy {
    fun canChangeBubbleAccess(restorationReady: Boolean, pluginEnabled: Boolean): Boolean =
        restorationReady && pluginEnabled

    fun canCreateDeveloperPlugin(
        restorationReady: Boolean,
        developerMode: Boolean,
        pluginId: String,
        pluginName: String
    ): Boolean = restorationReady && developerMode && pluginId.isNotBlank() && pluginName.isNotBlank()
}
