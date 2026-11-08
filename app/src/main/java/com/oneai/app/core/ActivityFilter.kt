package com.oneai.app.core

/**
 * Display-only filter for the Activity screen (Stage 10, slice 10.3, decision D7/D-J).
 *
 * Filtering never changes, deletes or rewrites stored entries, and the selection is not persisted. [ALL] shows every
 * entry, including those stored before kinds existed (no kind). Every other filter shows only entries whose kind it
 * names, so an older entry without a kind appears under [ALL] only.
 */
enum class ActivityFilter(val label: String, private val kinds: Set<ActivityKind>?) {
    ALL("All", null),
    AI("AI", setOf(ActivityKind.AI)),
    COMMANDS("Commands", setOf(ActivityKind.COMMAND)),
    PLUGINS("Plugins", setOf(ActivityKind.PLUGIN)),
    APPROVALS("Approvals", setOf(ActivityKind.APPROVAL)),
    ERRORS("Errors", setOf(ActivityKind.ERROR));

    /** True when [entry] is shown under this filter. */
    fun accepts(entry: ActivityEntry): Boolean = kinds == null || (entry.kind != null && entry.kind in kinds)

    companion object {
        /** The entries to show for [filter], in the order given (the log is newest first). Returns a new list. */
        fun apply(entries: List<ActivityEntry>, filter: ActivityFilter): List<ActivityEntry> = entries.filter { filter.accepts(it) }
    }
}
