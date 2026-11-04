package com.oneai.app.plugins

import com.oneai.app.commands.CommandRegistry
import com.oneai.app.core.PluginRecord
import com.oneai.app.core.PluginStore

/**
 * Connects trusted external plugins to the existing core: a plugin record, an adapter in the
 * [PluginRuntime] (so the gateway, approvals and failure isolation apply) and its declared commands in
 * the [CommandRegistry]. No plugin-specific code is needed in the core. Plugins start disabled; enabling
 * stays a separate user decision (and so does Bubble access).
 */
class ExternalPluginRegistrar(
    private val store: PluginStore,
    private val trust: PluginTrustRegistry,
    private val commands: CommandRegistry,
    private val channels: PluginChannelFactory,
    /** IDs that belong to built-in plugins; an APK using one is refused so it can never impersonate them. */
    private val reservedIds: Set<String> = emptySet(),
    private val timeoutMs: Long = ExternalPluginAdapter.DEFAULT_TIMEOUT_MS
) {
    /** Registers (or refreshes) a trusted plugin. An existing enabled flag is kept. */
    @Synchronized
    fun register(entry: TrustEntry): Result<PluginRecord> {
        val manifest = entry.trustedManifest
        val id = manifest.id
        if (id in reservedIds) return Result.failure(IllegalStateException("That plugin ID is reserved"))
        val existing = store.plugins().firstOrNull { it.id == id }
        if (existing?.developerCreated == true) return Result.failure(IllegalStateException("That plugin ID is already used by a developer plugin"))
        val runtime = store.pluginRuntime()
        val record = PluginRecord(
            id, manifest.name, manifest.versionName, manifest.description, manifest.capabilities,
            enabled = existing?.enabled ?: false, developerCreated = false,
            manifestVersion = PluginManifest.CURRENT_VERSION, dependencies = manifest.dependencies
        )
        runtime.unregister(id)
        commands.unregisterOwner(id)
        store.savePlugin(record)
        val adapter = ExternalPluginAdapter(manifest, trust, channels, timeoutMs, { pluginId, message -> store.log("[$pluginId] $message") })
        val failure = runtime.register(adapter).exceptionOrNull() ?: registerCommands(manifest)
        if (failure != null) {
            runtime.unregister(id)
            commands.unregisterOwner(id)
            if (existing == null) store.removePlugin(id)
            return Result.failure(failure)
        }
        store.log("Registered external plugin: ${manifest.name} ${manifest.versionName}")
        return Result.success(record)
    }

    private fun registerCommands(manifest: ExternalPluginManifest): Throwable? {
        manifest.commands.forEach { command ->
            commands.registerExtension(manifest.id, command.name, command.title, command.description, command.capability)
                .exceptionOrNull()?.let { return it }
        }
        return null
    }

    /** Takes a plugin out of service (adapter, commands, pending approvals) and disables it. The pin is kept. */
    @Synchronized
    fun deactivate(pluginId: String, reason: String) {
        store.pluginRuntime().unregister(pluginId)
        commands.unregisterOwner(pluginId)
        store.pluginActionGateway().revokePendingApprovals()
        store.plugins().firstOrNull { it.id == pluginId }?.takeIf { it.enabled }?.let { store.savePlugin(it.copy(enabled = false)) }
        store.log("Deactivated external plugin $pluginId: $reason")
    }

    /** Call from the app's plugin-removal hook: drops commands and the trust pin. */
    @Synchronized
    fun onRemoved(pluginId: String) {
        commands.unregisterOwner(pluginId)
        trust.forget(pluginId)
    }

    /** Handles a freshly read manifest of an installed update (design §3: more power needs fresh consent). */
    @Synchronized
    fun applyUpdate(updated: ExternalPluginManifest): UpdateDecision {
        val decision = trust.evaluateUpdate(updated)
        val id = updated.packageName.trim()
        when (decision) {
            UpdateDecision.Accepted -> trust.trusted().firstOrNull { it.packageName == id }?.let { register(it) }
            is UpdateDecision.ConsentRequired -> deactivate(id, "update needs your approval")
            is UpdateDecision.Rejected -> if (trust.trusted().any { it.packageName == id }) deactivate(id, decision.reason)
        }
        return decision
    }

    /** The user approved the declaration shown to them. The plugin stays disabled until they enable it. */
    @Synchronized
    fun approvePendingUpdate(pluginId: String): Result<PluginRecord> =
        trust.approvePendingUpdate(pluginId).fold({ register(it) }, { Result.failure(it) })

    /** Run at app start: bring back plugins that are still trusted; take everything else out of service. */
    @Synchronized
    fun restoreAll() {
        trust.trusted().forEach { entry ->
            when (val check = trust.check(entry.packageName)) {
                is TrustCheck.Trusted -> register(check.entry)
                is TrustCheck.UpdatePending -> deactivate(entry.packageName, "update needs your approval")
                TrustCheck.SignatureMismatch -> deactivate(entry.packageName, "signing certificate changed")
                TrustCheck.NotInstalled -> deactivate(entry.packageName, "the plugin app is not installed")
                TrustCheck.NotImported -> Unit
            }
        }
    }
}
