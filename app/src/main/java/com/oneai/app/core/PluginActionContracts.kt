package com.oneai.app.core

/** Input delivered across the core-owned plugin execution boundary after policy checks. */
data class PluginActionInput(
    val actionId: String,
    val capability: String,
    val description: String,
    val values: Map<String, String> = emptyMap()
)

/** Bounded, normalized output the shell may safely render without knowing plugin internals. */
data class PluginActionOutput(
    val title: String,
    val content: String,
    val metadata: Map<String, String> = emptyMap()
)

/** Result vocabulary shared by core UI and any implementation of the plugin execution contract. */
sealed interface PluginInvocationResult {
    data class Completed(val output: PluginActionOutput) : PluginInvocationResult
    data class AwaitingApproval(val reason: String) : PluginInvocationResult
    data class Rejected(val reason: String) : PluginInvocationResult
    data class Failed(val message: String) : PluginInvocationResult
}

/**
 * Core-owned SDK boundary consumed by UI/security surfaces.
 * Implementations own adapter registration, policy enforcement and execution.
 * This contract deliberately exposes no plugin-runtime or security implementation types.
 */
interface PluginActionExecutor {
    suspend fun invoke(
        pluginId: String,
        input: PluginActionInput,
        approvalTicket: String? = null
    ): PluginInvocationResult

    /** Issues a one-use approval ticket only after validating the installed plugin and action. */
    fun approveSensitiveAction(pluginId: String, input: PluginActionInput): Result<String>
}


/** Lifecycle operations required by core plugin management, independent of runtime implementation. */
interface PluginLifecycleController {
    fun unregister(pluginId: String): Boolean
    fun revokePendingApprovals()
}

/** Used only by isolated tests or a deliberately empty host; production injects its shared runtime. */
object NoOpPluginLifecycleController : PluginLifecycleController {
    override fun unregister(pluginId: String) = false
    override fun revokePendingApprovals() = Unit
}
