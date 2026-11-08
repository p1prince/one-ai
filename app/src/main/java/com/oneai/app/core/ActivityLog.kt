package com.oneai.app.core

/**
 * The bounded Activity log (Stage 10, A1). Appending is a read-modify-write of the whole stored list, so concurrent
 * appends from the UI, the Bubble service and plugin callbacks could overwrite each other and drop entries.
 *
 * Every operation here runs under one lock shared by all instances in the process. A lock per instance would not be
 * enough because more than one [OneAiStore] can exist and they all write the same preferences file. Storage is
 * supplied by the caller so this class stays pure Kotlin and can be tested on the JVM.
 *
 * Order is newest first and the list is cut to [MAX_ENTRIES], as before.
 */
class ActivityLog(
    private val load: () -> List<ActivityEntry>,
    private val save: (List<ActivityEntry>) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    /** Plain appends are recorded as [ActivityKind.SYSTEM] unless the call site gives a kind. */
    fun append(message: String, kind: ActivityKind = ActivityKind.SYSTEM, plugin: String? = null) = synchronized(LOCK) {
        save((listOf(ActivityEntry(now(), message, kind, plugin?.takeIf { it.isNotEmpty() })) + load()).take(MAX_ENTRIES))
    }

    fun entries(): List<ActivityEntry> = synchronized(LOCK) { load() }

    fun clear() = synchronized(LOCK) { save(emptyList()) }

    companion object {
        const val MAX_ENTRIES = 150

        /** Longest log line the Activity screen shows. Stored text is not changed. */
        const val DISPLAY_MAX = 300
        private val LOCK = Any()
    }
}
