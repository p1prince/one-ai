package com.oneai.app.core

/**
 * What an Activity entry is about (Stage 10, slice 10.2, decision D7/D-J).
 *
 * The kind is chosen only by Core call sites, never by a plugin. [stored] is the value written to preferences.
 * Entries written before this field existed have no kind (`null` on [ActivityEntry]) and are shown as earlier activity.
 */
enum class ActivityKind(val stored: String, val label: String) {
    AI("ai", "AI"),
    COMMAND("command", "Commands"),
    PLUGIN("plugin", "Plugins"),
    APPROVAL("approval", "Approvals"),
    ERROR("error", "Errors"),
    SYSTEM("system", "System");

    companion object {
        /** The kind for a stored value, or null when it is absent or not a known kind. */
        fun parse(value: String?): ActivityKind? = values().firstOrNull { it.stored == value }
    }
}
