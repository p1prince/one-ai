package com.oneai.app.security

import java.util.UUID

enum class ActionRisk { LOW, SENSITIVE, BLOCKED }

data class ActionRequest(
    val pluginId: String,
    val actionId: String,
    val capability: String,
    val description: String
)

data class ActionAuthorization(
    val allowed: Boolean,
    val requiresUserApproval: Boolean,
    val risk: ActionRisk,
    val reason: String
)

object CapabilityPolicy {
    private val known = setOf(
        "commands", "internet", "screen_capture", "microphone", "audio",
        "device_actions", "accessibility", "automation", "files", "app_launch"
    )
    private val approvalRequired = setOf(
        "screen_capture", "microphone", "device_actions", "accessibility",
        "automation", "files", "internet", "app_launch"
    )

    fun evaluate(request: ActionRequest, pluginEnabled: Boolean, declaredCapabilities: Set<String>): ActionAuthorization {
        if (!pluginEnabled) return ActionAuthorization(false, false, ActionRisk.BLOCKED, "Plugin is disabled")
        if (request.pluginId.isBlank() || request.pluginId.length > 100 ||
            request.actionId.isBlank() || request.actionId.length > 100 ||
            request.description.isBlank() || request.description.length > 500 ||
            request.pluginId != request.pluginId.trim() || request.actionId != request.actionId.trim() ||
            request.description != request.description.trim()) {
            return ActionAuthorization(false, false, ActionRisk.BLOCKED, "Action details are incomplete")
        }
        if (request.capability !in known) {
            return ActionAuthorization(false, false, ActionRisk.BLOCKED, "Capability is not recognized")
        }
        if (request.capability !in declaredCapabilities) {
            return ActionAuthorization(false, false, ActionRisk.BLOCKED, "Plugin did not declare this capability")
        }
        val sensitive = request.capability in approvalRequired
        return ActionAuthorization(
            allowed = true,
            requiresUserApproval = sensitive,
            risk = if (sensitive) ActionRisk.SENSITIVE else ActionRisk.LOW,
            reason = if (sensitive) "This capability requires just-in-time user approval" else "No additional confirmation is required by the capability policy"
        )
    }

    fun androidPermissionGuidance(capability: String): String = when (capability) {
        "screen_capture" -> "Request Android MediaProjection consent for each capture session."
        "microphone" -> "Request the Android RECORD_AUDIO runtime permission before microphone access."
        "audio" -> "Use the relevant Android audio APIs; request runtime permission only when required."
        "device_actions" -> "Use supported Android intents or APIs; some settings changes require the user to confirm in system UI."
        "app_launch" -> "Launch only a user-selected installed app through its exported launcher activity; no sign-in or private app data access is implied."
        "accessibility" -> "Accessibility access must be enabled by the user in Android Settings."
        "automation" -> "Explain each automation capability and obtain approval before running actions."
        "files" -> "Use the Android Storage Access Framework or scoped media permissions as appropriate."
        "internet" -> "Declare INTERNET in the manifest; this is a normal install-time permission."
        "commands" -> "No Android runtime permission is implied by this capability."
        else -> "Unknown capability; do not request permissions."
    }
}

/**
 * Process-local, one-use approval tickets. Tickets are bound to one exact action
 * and expire quickly; they are never persisted as standing permission grants.
 */
class OneTimeApprovalGate(
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val lifetimeMillis: Long = 60_000L
) {
    private data class Ticket(
        val pluginId: String,
        val actionId: String,
        val capability: String,
        val description: String,
        val expiresAt: Long
    )

    private val tickets = mutableMapOf<String, Ticket>()

    @Synchronized
    fun issueAfterUserApproval(request: ActionRequest): String {
        require(request.pluginId.isNotBlank() && request.actionId.isNotBlank() && request.capability.isNotBlank())
        require(lifetimeMillis in 1L..300_000L) { "Approval lifetime must be between 1 ms and 5 minutes" }
        val token = UUID.randomUUID().toString()
        tickets[token] = Ticket(request.pluginId, request.actionId, request.capability, request.description, nowMillis() + lifetimeMillis)
        return token
    }

    @Synchronized
    fun consume(token: String, request: ActionRequest): Boolean {
        val ticket = tickets.remove(token) ?: return false
        return ticket.expiresAt >= nowMillis() &&
            ticket.pluginId == request.pluginId &&
            ticket.actionId == request.actionId &&
            ticket.capability == request.capability &&
            ticket.description == request.description
    }

    @Synchronized
    fun revokeAll() {
        tickets.clear()
    }
}

fun interface ActionAuditLogger {
    fun record(event: String)
}
