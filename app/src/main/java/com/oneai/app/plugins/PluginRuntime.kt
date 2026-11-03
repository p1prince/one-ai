package com.oneai.app.plugins

import com.oneai.app.security.ActionGateResult
import com.oneai.app.security.ActionRequest
import com.oneai.app.security.PluginActionGateway
import com.oneai.app.core.PluginRecord
import kotlinx.coroutines.CancellationException

/** Input delivered to a trusted, registered plugin adapter after core policy checks. */
data class PluginActionInput(
    val actionId: String,
    val capability: String,
    val description: String,
    val values: Map<String, String> = emptyMap()
)

/** Normalized output returned by a plugin; the shell does not interpret plugin-specific payloads. */
data class PluginActionOutput(
    val title: String,
    val content: String,
    val metadata: Map<String, String> = emptyMap()
)

/** A trusted in-app or explicitly permissioned external adapter for one declared plugin. */
interface PluginAdapter {
    val manifest: PluginManifest
    suspend fun execute(input: PluginActionInput): PluginActionOutput
}

sealed interface PluginInvocationResult {
    data class Completed(val output: PluginActionOutput) : PluginInvocationResult
    data class AwaitingApproval(val reason: String) : PluginInvocationResult
    data class Rejected(val reason: String) : PluginInvocationResult
    data class Failed(val message: String) : PluginInvocationResult
}

/**
 * Core-owned execution boundary. It registers trusted adapters, checks that their
 * manifest matches the installed plugin record, and routes every action through
 * PluginActionGateway immediately before invoking plugin code.
 *
 * This is deliberately not a downloaded-code loader. Registration is performed
 * by trusted app integration code; declarative manifests alone cannot execute.
 */
class PluginRuntime(
    private val findInstalledPlugin: (String) -> PluginRecord?,
    private val actionGateway: PluginActionGateway
) {
    private val adapters = linkedMapOf<String, PluginAdapter>()

    @Synchronized
    fun register(adapter: PluginAdapter): Result<Unit> {
        val manifest = PluginManifestValidator.validate(adapter.manifest)
            .getOrElse { return Result.failure(it) }
        if (manifest.id in adapters) {
            return Result.failure(IllegalStateException("An adapter is already registered for ${manifest.id}"))
        }
        val installed = findInstalledPlugin(manifest.id)
            ?: return Result.failure(IllegalStateException("Install the plugin before registering its adapter"))
        if (installed.manifestVersion != manifest.manifestVersion ||
            installed.capabilities.toSet() != manifest.capabilities.toSet()) {
            return Result.failure(IllegalStateException("Adapter manifest does not match the installed plugin declaration"))
        }
        adapters[manifest.id] = adapter
        return Result.success(Unit)
    }

    @Synchronized
    fun unregister(pluginId: String): Boolean = adapters.remove(pluginId) != null

    @Synchronized
    fun registeredPluginIds(): Set<String> = adapters.keys.toSet()

    suspend fun invoke(
        pluginId: String,
        input: PluginActionInput,
        approvalTicket: String? = null
    ): PluginInvocationResult {
        val adapter = synchronized(this) { adapters[pluginId] }
            ?: return PluginInvocationResult.Rejected("No trusted adapter is registered for this plugin")
        val installed = findInstalledPlugin(pluginId)
            ?: return PluginInvocationResult.Rejected("Plugin is not installed")
        if (!installed.enabled) return PluginInvocationResult.Rejected("Plugin is disabled")
        if (input.values.size > 32 || input.values.any { (key, value) ->
                key.isBlank() || key.length > 80 || value.length > 4_000
            }) {
            return PluginInvocationResult.Rejected("Plugin input exceeds the supported limits")
        }

        val action = ActionRequest(pluginId, input.actionId, input.capability, input.description)
        when (val gate = actionGateway.check(action, approvalTicket)) {
            is ActionGateResult.AwaitingUserApproval ->
                return PluginInvocationResult.AwaitingApproval(gate.authorization.reason)
            is ActionGateResult.Rejected ->
                return PluginInvocationResult.Rejected(gate.authorization.reason)
            is ActionGateResult.Permitted -> Unit
        }

        return try {
            PluginInvocationResult.Completed(adapter.execute(input))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PluginInvocationResult.Failed("The plugin action failed. Check its activity entry for details.")
        }
    }
}
