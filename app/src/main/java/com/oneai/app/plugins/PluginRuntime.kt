package com.oneai.app.plugins

import com.oneai.app.security.ActionGateResult
import com.oneai.app.security.ActionRequest
import com.oneai.app.core.PluginRecord
import com.oneai.app.core.PluginActionExecutor
import com.oneai.app.core.PluginLifecycleController
import com.oneai.app.security.PluginActionApprovalGateway
import kotlinx.coroutines.CancellationException

/** A trusted in-app or explicitly permissioned external adapter for one declared plugin. */
interface PluginAdapter {
    val manifest: PluginManifest
    suspend fun execute(input: PluginActionInput): PluginActionOutput
}

/** Narrow registration surface used by Bubble contributors without exposing the runtime implementation. */
interface PluginAdapterRegistrar {
    fun register(adapter: PluginAdapter): Result<Unit>
    fun unregister(pluginId: String): Boolean
    fun isRegistered(pluginId: String): Boolean
}

/**
 * Core-owned execution boundary. It registers trusted adapters, checks that their
 * complete declaration matches the installed plugin record, and routes every action
 * through PluginActionGateway immediately before invoking plugin code.
 *
 * This is deliberately not a downloaded-code loader. Registration is performed
 * by trusted app integration code; declarative manifests alone cannot execute.
 */
class PluginRuntime(
    private val findInstalledPlugin: (String) -> PluginRecord?,
    private val actionGateway: PluginActionApprovalGateway
) : PluginActionExecutor, PluginLifecycleController, PluginAdapterRegistrar {
    private val adapters = linkedMapOf<String, PluginAdapter>()

    @Synchronized
    override fun register(adapter: PluginAdapter): Result<Unit> {
        val manifest = PluginManifestValidator.validate(adapter.manifest)
            .getOrElse { return Result.failure(it) }
        if (manifest.id in adapters) {
            return Result.failure(IllegalStateException("An adapter is already registered for ${manifest.id}"))
        }
        val installed = findInstalledPlugin(manifest.id)
            ?: return Result.failure(IllegalStateException("Install the plugin before registering its adapter"))
        if (installed.manifestVersion != manifest.manifestVersion ||
            installed.id != manifest.id ||
            installed.name != manifest.name ||
            installed.version != manifest.version ||
            installed.description != manifest.description ||
            installed.capabilities.toSet() != manifest.capabilities.toSet() ||
            installed.dependencies.toSet() != manifest.dependencies.toSet()) {
            return Result.failure(IllegalStateException("Adapter manifest does not match the installed plugin declaration"))
        }
        adapters[manifest.id] = adapter
        return Result.success(Unit)
    }

    @Synchronized
    override fun unregister(pluginId: String): Boolean = adapters.remove(pluginId) != null

    override fun revokePendingApprovals() = actionGateway.revokePendingApprovals()

    @Synchronized
    override fun isRegistered(pluginId: String): Boolean = pluginId in adapters

    /** Concrete-runtime diagnostic; intentionally absent from the narrow registrar interface. */
    @Synchronized
    fun registeredPluginIds(): Set<String> = adapters.keys.toSet()

    override suspend fun invoke(
        pluginId: String,
        input: PluginActionInput,
        approvalTicket: String?
    ): PluginInvocationResult {
        val adapter = synchronized(this) { adapters[pluginId] }
            ?: return com.oneai.app.core.PluginInvocationResult.Rejected("No trusted adapter is registered for this plugin")
        val installed = findInstalledPlugin(pluginId)
            ?: return com.oneai.app.core.PluginInvocationResult.Rejected("Plugin is not installed")
        if (!installed.enabled) return com.oneai.app.core.PluginInvocationResult.Rejected("Plugin is disabled")
        validateInput(input, installed)?.let { return com.oneai.app.core.PluginInvocationResult.Rejected(it) }

        val action = ActionRequest(pluginId, input.actionId, input.capability, input.description)
        when (val gate = actionGateway.check(action, approvalTicket)) {
            is ActionGateResult.AwaitingUserApproval ->
                return com.oneai.app.core.PluginInvocationResult.AwaitingApproval(gate.authorization.reason)
            is ActionGateResult.Rejected ->
                return com.oneai.app.core.PluginInvocationResult.Rejected(gate.authorization.reason)
            is ActionGateResult.Permitted -> Unit
        }

        // Re-check lifecycle state after consent is consumed and immediately before
        // entering adapter code. This prevents stale registrations from starting work.
        val current = findInstalledPlugin(pluginId)
        val stillRegistered = synchronized(this) { adapters[pluginId] === adapter }
        if (current == null || !current.enabled || !stillRegistered) {
            return com.oneai.app.core.PluginInvocationResult.Rejected("Plugin was disabled or removed before execution")
        }

        return try {
            val output = adapter.execute(input)
            if (!output.isValidForCoreUi()) {
                actionGateway.recordExecutionResult(pluginId, input.actionId, "failed")
                com.oneai.app.core.PluginInvocationResult.Failed("The plugin returned data outside the supported limits.")
            } else {
                actionGateway.recordExecutionResult(pluginId, input.actionId, "completed")
                com.oneai.app.core.PluginInvocationResult.Completed(output)
            }
        } catch (cancelled: CancellationException) {
            actionGateway.recordExecutionResult(pluginId, input.actionId, "cancelled")
            throw cancelled
        } catch (_: Exception) {
            actionGateway.recordExecutionResult(pluginId, input.actionId, "failed")
            com.oneai.app.core.PluginInvocationResult.Failed("The plugin action failed. Check its activity entry for details.")
        }
    }

    /**
     * Issues a one-use ticket only for a currently installed, enabled plugin with
     * a registered adapter and a valid action declaration. Call this only from
     * the affirmative branch of SensitiveActionApprovalDialog.
     */
    override fun approveSensitiveAction(pluginId: String, input: PluginActionInput): Result<String> {
        val adapter = synchronized(this) { adapters[pluginId] }
            ?: return Result.failure(IllegalStateException("No trusted adapter is registered for this plugin"))
        val installed = findInstalledPlugin(pluginId)
            ?: return Result.failure(IllegalStateException("Plugin is not installed"))
        if (!installed.enabled) {
            return Result.failure(IllegalStateException("Plugin is disabled"))
        }
        validateInput(input, installed)?.let {
            return Result.failure(IllegalArgumentException(it))
        }
        val action = ActionRequest(pluginId, input.actionId, input.capability, input.description)
        return runCatching {
            val current = findInstalledPlugin(pluginId)
            val stillRegistered = synchronized(this) { adapters[pluginId] === adapter }
            check(current != null && current == installed && current.enabled && stillRegistered) {
                "Plugin changed before approval could be issued"
            }
            actionGateway.confirmUserApproval(action)
        }
    }

    private fun validateInput(input: PluginActionInput, installed: PluginRecord): String? {
        if (input.actionId.isBlank() || input.actionId.length > 100 ||
            input.actionId != input.actionId.trim() ||
            input.capability.isBlank() || input.description.isBlank() ||
            input.description.length > 500 || input.description != input.description.trim() ||
            input.capability !in installed.capabilities) {
            return "Plugin action details are invalid"
        }
        if (input.values.size > MAX_MAP_ENTRIES || input.values.any { (key, value) ->
                key.isBlank() || key.length > MAX_KEY_LENGTH || value.length > MAX_VALUE_LENGTH
            }) {
            return "Plugin input exceeds the supported limits"
        }
        return null
    }

    private fun PluginActionOutput.isValidForCoreUi(): Boolean =
        title.isNotBlank() && title.length <= MAX_TITLE_LENGTH &&
            content.length <= MAX_OUTPUT_LENGTH &&
            metadata.size <= MAX_MAP_ENTRIES &&
            metadata.all { (key, value) ->
                key.isNotBlank() && key.length <= MAX_KEY_LENGTH && value.length <= MAX_VALUE_LENGTH
            }

    private companion object {
        const val MAX_MAP_ENTRIES = 32
        const val MAX_KEY_LENGTH = 80
        const val MAX_VALUE_LENGTH = 4_000
        const val MAX_TITLE_LENGTH = 120
        const val MAX_OUTPUT_LENGTH = 20_000
    }
}
