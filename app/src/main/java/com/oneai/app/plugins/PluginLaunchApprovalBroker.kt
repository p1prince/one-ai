package com.oneai.app.plugins

/**
 * A plugin may request a screen, but it never receives permission to open it directly.
 * This process-scoped broker holds at most one untrusted request until the host UI explicitly
 * approves or rejects it. The payload is opaque here so policy tests do not need Android.
 */
data class PluginLaunchRequest(
    val requestId: String,
    val pluginPackage: String,
    val label: String,
    val payload: Any
)

object PluginLaunchIdentityPolicy {
    /**
     * The PendingIntent must have been created by the installed plugin package and UID.
     * On Android 12+, mutable PendingIntents are rejected. On older releases mutability cannot
     * be queried, so the SDK contract's FLAG_IMMUTABLE requirement remains authoritative.
     */
    fun accepts(
        expectedPackage: String,
        expectedUid: Int,
        creatorPackage: String?,
        creatorUid: Int,
        immutable: Boolean?
    ): Boolean =
        expectedPackage.isNotBlank() &&
            expectedUid >= 0 &&
            creatorPackage == expectedPackage &&
            creatorUid == expectedUid &&
            immutable != false
}

class PluginLaunchApprovalBroker {
    private var pending: PluginLaunchRequest? = null

    /** Stores one well-formed request. A second request cannot replace one awaiting the user. */
    @Synchronized
    fun offer(request: PluginLaunchRequest): Boolean {
        val packagePattern = Regex("[a-z][a-z0-9]*(\\.[a-z0-9]+)+")
        val label = request.label.filterNot { it.isISOControl() }.trim().take(MAX_LABEL_LENGTH)
        if (request.requestId.isBlank() || request.requestId.length > MAX_REQUEST_ID_LENGTH) return false
        if (!packagePattern.matches(request.pluginPackage)) return false
        if (label.isBlank() || pending != null) return false
        pending = request.copy(label = label)
        return true
    }

    @Synchronized
    fun current(): PluginLaunchRequest? = pending

    /** Consumes the request only when the matching host UI confirms and the plugin remains trusted. */
    @Synchronized
    fun approve(requestId: String, stillTrusted: (String) -> Boolean): Any? {
        val request = pending?.takeIf { it.requestId == requestId } ?: return null
        pending = null
        return request.payload.takeIf { stillTrusted(request.pluginPackage) }
    }

    @Synchronized
    fun reject(requestId: String): Boolean {
        if (pending?.requestId != requestId) return false
        pending = null
        return true
    }

    @Synchronized
    fun clearPlugin(pluginPackage: String): Boolean {
        if (pending?.pluginPackage != pluginPackage) return false
        pending = null
        return true
    }

    private companion object {
        const val MAX_REQUEST_ID_LENGTH = 80
        const val MAX_LABEL_LENGTH = 120
    }
}
