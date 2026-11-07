package com.oneai.app.security

import com.oneai.app.core.PluginRecord

sealed interface ActionGateResult {
    data class Permitted(val authorization: ActionAuthorization) : ActionGateResult
    data class AwaitingUserApproval(val authorization: ActionAuthorization) : ActionGateResult
    data class Rejected(val authorization: ActionAuthorization) : ActionGateResult
}

interface PluginActionApprovalGateway {
    fun check(request: ActionRequest, approvalTicket: String? = null): ActionGateResult
    fun confirmUserApproval(request: ActionRequest): String
    fun revokePendingApprovals()
    fun recordExecutionResult(pluginId: String, actionId: String, outcome: String)
}

/** Mandatory policy boundary for future plugin action adapters. */
class PluginActionGateway(
    private val findPlugin: (String) -> PluginRecord?,
    private val audit: ActionAuditLogger,
    private val approvals: OneTimeApprovalGate = OneTimeApprovalGate()
) : PluginActionApprovalGateway {
    override fun check(request: ActionRequest, approvalTicket: String?): ActionGateResult {
        val plugin = findPlugin(request.pluginId)
        if (plugin == null) {
            val rejected = ActionAuthorization(false, false, ActionRisk.BLOCKED, "Plugin is not installed")
            audit.record("Action rejected: plugin missing (${request.pluginId}/${request.actionId})")
            return ActionGateResult.Rejected(rejected)
        }
        val authorization = CapabilityPolicy.evaluate(request, plugin.enabled, plugin.capabilities.toSet())
        if (!authorization.allowed) {
            audit.record("Action rejected: ${authorization.reason} (${plugin.id}/${request.actionId})")
            return ActionGateResult.Rejected(authorization)
        }
        if (!authorization.requiresUserApproval) {
            audit.record("Low-risk action permitted: ${plugin.id}/${request.actionId}")
            return ActionGateResult.Permitted(authorization)
        }
        if (approvalTicket == null) {
            audit.record("Sensitive action awaiting approval: ${plugin.id}/${request.actionId}")
            return ActionGateResult.AwaitingUserApproval(authorization)
        }
        if (!approvals.consume(approvalTicket, request)) {
            audit.record("Sensitive action rejected: invalid, expired, or reused approval (${plugin.id}/${request.actionId})")
            return ActionGateResult.Rejected(authorization.copy(allowed = false, reason = "A valid one-time approval is required"))
        }
        audit.record("Sensitive action approved: ${plugin.id}/${request.actionId}")
        return ActionGateResult.Permitted(authorization)
    }

    /** Call only from the affirmative branch of a real, visible consent UI. */
    override fun confirmUserApproval(request: ActionRequest): String {
        val plugin = findPlugin(request.pluginId) ?: throw IllegalStateException("Plugin is not installed")
        val authorization = CapabilityPolicy.evaluate(request, plugin.enabled, plugin.capabilities.toSet())
        kotlin.check(authorization.allowed) { authorization.reason }
        kotlin.check(authorization.requiresUserApproval) { "This action does not require a sensitive-action approval ticket" }
        audit.record("User approved sensitive action: ${plugin.id}/${request.actionId}")
        return approvals.issueAfterUserApproval(request)
    }

    /** Records only a bounded outcome label; never pass secrets or plugin payloads. */
    override fun recordExecutionResult(pluginId: String, actionId: String, outcome: String) {
        require(outcome in setOf("completed", "failed", "cancelled"))
        audit.record("Plugin action $outcome: $pluginId/$actionId")
    }

    override fun revokePendingApprovals() = approvals.revokeAll()
}
