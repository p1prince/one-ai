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
 * complete declaration matches the installed plugin record, and routes every action
 * through PluginActionGateway immediately before invoking plugin code.
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
        if (input.actionId.isBlank() || input.actionId.length > 100 ||
            input.actionId != input.actionId.trim() ||
            input.capability.isBlank() || input.description.isBlank() ||
            input.description.length > 500 || input.description != input.description.trim() ||
            input.capability !in installed.capabilities) {
            return PluginInvocationResult.Rejected("Plugin action details are invalid")
        }
        if (input.values.size > MAX_MAP_ENTRIES || input.values.any { (key, value) ->
                key.isBlank() || key.length > MAX_KEY_LENGTH || value.length > MAX_VALUE_LENGTH
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

        // Re-check lifecycle state after consent is consumed and immediately before
        // entering adapter code. This prevents stale registrations from starting work.
        val current = findInstalledPlugin(pluginId)
        val stillRegistered = synchronized(this) { adapters[pluginId] === adapter }
        if (current == null || !current.enabled || !stillRegistered) {
            return PluginInvocationResult.Rejected("Plugin was disabled or removed before execution")
        }

        return try {
            val output = adapter.execute(input)
            if (!output.isValidForCoreUi()) {
                PluginInvocationResult.Failed("The plugin returned data outside the supported limits.")
            } else {
                PluginInvocationResult.Completed(output)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PluginInvocationResult.Failed("The plugin action failed. Check its activity entry for details.")
        }
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
