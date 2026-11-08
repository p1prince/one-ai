package com.oneai.app.plugins

import com.oneai.app.core.ActivityEntry

/**
 * Stage 9.9: picks the Activity entries that are about one plugin, for the Details view.
 *
 * The activity log stores a timestamp and free text only, and changing that format is a Stage 10 decision. Every
 * plugin-attributed message already names the plugin by its exact id (`[id] ...` from the adapter and registrar,
 * `id/actionId` from the security gateway), so an entry is "about" a plugin when its text contains that id as a whole
 * identifier. A longer id that merely starts or ends with it (`com.example.notes.extras` versus `com.example.notes`)
 * never matches. This is a read-only view over the existing log; it records and changes nothing. Pure Kotlin.
 */
object PluginActivityView {
    const val DEFAULT_LIMIT = 10

    private fun isIdChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-'

    /** True when [pluginId] appears in [message] as a whole identifier, not as part of a longer one. */
    fun mentions(message: String, pluginId: String): Boolean {
        if (pluginId.isEmpty()) return false
        var from = 0
        while (true) {
            val at = message.indexOf(pluginId, from)
            if (at < 0) return false
            val before = message.getOrNull(at - 1)
            val after = message.getOrNull(at + pluginId.length)
            val afterNext = message.getOrNull(at + pluginId.length + 1)
            val startsClean = before == null || !(isIdChar(before) || before == '.')
            val endsClean = after == null || !(isIdChar(after) || (after == '.' && afterNext != null && isIdChar(afterNext)))
            if (startsClean && endsClean) return true
            from = at + 1
        }
    }

    /** The newest [limit] entries about [pluginId], in the order given (the log is newest first). */
    fun forPlugin(entries: List<ActivityEntry>, pluginId: String, limit: Int = DEFAULT_LIMIT): List<ActivityEntry> =
        entries.filter { mentions(it.message, pluginId) }.take(limit.coerceAtLeast(0))
}
