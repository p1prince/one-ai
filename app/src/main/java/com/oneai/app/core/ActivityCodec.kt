package com.oneai.app.core

import org.json.JSONObject

/**
 * Stored form of an [ActivityEntry] (Stage 10, slice 10.2). Kept apart from [OneAiStore] so it can be tested on the JVM.
 *
 * Writing adds `kind` and `plugin` only when the entry has them. Reading accepts every earlier entry (just `time` and
 * `message`) unchanged: a missing or unknown `kind` becomes null and a missing or empty `plugin` becomes null. Nothing
 * stored is migrated or rewritten.
 */
object ActivityCodec {
    fun encode(entry: ActivityEntry): JSONObject {
        val item = JSONObject().put("time", entry.timestamp).put("message", entry.message)
        entry.kind?.let { item.put("kind", it.stored) }
        entry.plugin?.let { item.put("plugin", it) }
        return item
    }

    fun decode(item: JSONObject): ActivityEntry = ActivityEntry(
        item.optLong("time", 0L),
        item.optString("message"),
        ActivityKind.parse(item.optString("kind", "").ifEmpty { null }),
        item.optString("plugin", "").ifEmpty { null }
    )
}
