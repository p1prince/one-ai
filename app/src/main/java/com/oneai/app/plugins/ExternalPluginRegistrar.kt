package com.oneai.app.plugins

import com.oneai.app.commands.CommandRegistry
import com.oneai.app.core.PluginRecord
import com.oneai.app.core.PluginStore
import com.oneai.app.core.PluginLifecycleController

/**
 * Connects trusted external plugins to the existing core: a plugin record, an adapter in the
 * [PluginRuntime] (so the gateway, approvals and failure isolation apply) and its declared commands in
 * the [CommandRegistry]. No plugin-specific code is needed in the core. Plugins start disabled; enabling
 * stays a separate user decision (and so does Bubble access).
 */
class ExternalPluginRegistrar(
    private val store: PluginStore,
    private val runtime: PluginAdapterRegistrar,
    private val lifecycle: PluginLifecycleController,
    private val trust: PluginTrustRegistry,
    private val commands: CommandRegistry,
    private val channels: PluginChannelFactory,
    /** IDs that belong to built-in plugins; an APK using one is refused so it can never impersonate them. */
    private val reservedIds: Set<String> = emptySet(),
    private val timeoutMs: Long = ExternalPluginAdapter.DEFAULT_TIMEOUT_MS,
    /** Called when a plugin with a Bubble declaration is (re)registered; receives a view fetcher for its service. */
    private val onBubbleService: (ExternalPluginManifest, suspend (String) -> String?) -> Unit = { _, _ -> },
    /** Called whenever a plugin's Bubble service must disappear (deactivation, removal, failed registration). */
    private val onBubbleRemoved: (String) -> Unit = {},
    /** Source of the user's settings values (Stage 9.6c). Without it a plugin still gets its declared defaults. */
    private val configuration: PluginConfiguration? = null
) {
    /** Persist trusted metadata. Disabled plugins have no runtime adapter, commands or Bubble provider. */
    @Synchronized
    fun register(entry: TrustEntry): Result<PluginRecord> {
        val manifest = entry.trustedManifest
        val id = manifest.id
        if (id in reservedIds) return Result.failure(IllegalStateException("That plugin ID is reserved"))
        val existing = store.plugins().firstOrNull { it.id == id }
        if (existing?.developerCreated == true) return Result.failure(IllegalStateException("That plugin ID is already used by a developer plugin"))
        val record = PluginRecord(id, manifest.name, manifest.versionName, manifest.description, manifest.capabilities,
            enabled = existing?.enabled ?: false, developerCreated = false,
            manifestVersion = PluginManifest.CURRENT_VERSION, dependencies = manifest.dependencies)
        clearRuntime(id)
        store.savePlugin(record)
        if (!record.enabled) {
            store.log("Imported external plugin metadata: ${manifest.name} (disabled)")
            return Result.success(record)
        }
        val activated = registerRuntime(manifest)
        if (activated.isFailure) {
            store.savePlugin(record.copy(enabled = false))
            if (existing == null) store.removePlugin(id)
            return Result.failure(activated.exceptionOrNull()!!)
        }
        store.log("Registered enabled external plugin: ${manifest.name} ${manifest.versionName}")
        return Result.success(record)
    }

    /** Runtime activation is permitted only after the stored record has been explicitly enabled. */
    @Synchronized
    fun activate(pluginId: String): Result<Unit> {
        val record = store.plugins().firstOrNull { it.id == pluginId }
            ?: return Result.failure(IllegalStateException("Plugin metadata is missing"))
        if (!record.enabled) return Result.failure(IllegalStateException("Enable the plugin before activating it"))
        val entry = (trust.check(pluginId) as? TrustCheck.Trusted)?.entry
            ?: return Result.failure(IllegalStateException("The plugin is no longer trusted"))
        if (entry.trustedManifest.id != pluginId) return Result.failure(IllegalStateException("Plugin identity mismatch"))
        return registerRuntime(entry.trustedManifest)
    }

    private fun registerRuntime(manifest: ExternalPluginManifest): Result<Unit> {
        val id = manifest.id
        clearRuntime(id)
        val adapter = ExternalPluginAdapter(
            manifest, trust, channels, timeoutMs,
            report = { pluginId, message -> store.log("[$pluginId] $message") },
            settings = { settingsSnapshot(manifest) },
            missingRequired = { missingRequiredTitles(manifest) }
        )
        val failure = runtime.register(adapter).exceptionOrNull() ?: registerCommands(manifest)
        if (failure != null) {
            clearRuntime(id)
            return Result.failure(failure)
        }
        if (manifest.bubble != null) onBubbleService(manifest) { serviceId -> adapter.view(serviceId) }
        return Result.success(Unit)
    }

    /** What to send a plugin on bind: its effective settings, or null when it declares none. Read fresh on every bind. */
    private fun settingsSnapshot(manifest: ExternalPluginManifest): PluginConfigSnapshot? {
        val schema = manifest.settings ?: return null
        val values = configuration?.effective(manifest.id) ?: PluginSettingsValidator.effectiveValues(schema, emptyMap())
        return PluginConfigSnapshot(schema.version, values)
    }

    /** Titles of required settings with no usable value; read fresh on every call so a Save takes effect at once. */
    private fun missingRequiredTitles(manifest: ExternalPluginManifest): List<String> {
        val schema = manifest.settings ?: return emptyList()
        val configured = configuration
        if (configured != null) return configured.missingRequired(manifest.id).map { it.title }
        val missing = PluginSettingsValidator.missingRequired(schema, emptyMap()).toSet()
        return schema.settings.filter { it.key in missing }.map { it.title }
    }

    /** Register only the commands declared by this trusted manifest. */
    private fun registerCommands(manifest: ExternalPluginManifest): Throwable? {
        manifest.commands.forEach { command ->
            val result = commands.registerExtension(
                manifest.id, command.name, command.title, command.description, command.capability
            )
            result.exceptionOrNull()?.let { return it }
        }
        return null
    }

    private fun clearRuntime(pluginId: String) {
        runtime.unregister(pluginId)
        commands.unregisterOwner(pluginId)
        onBubbleRemoved(pluginId)
    }

    /** Takes a plugin out of service (adapter, commands, pending approvals) and disables it. The pin is kept. */
    @Synchronized
    fun deactivate(pluginId: String, reason: String) {
        clearRuntime(pluginId)
        lifecycle.revokePendingApprovals()
        store.plugins().firstOrNull { it.id == pluginId }?.takeIf { it.enabled }?.let { store.savePlugin(it.copy(enabled = false)) }
        store.log("Deactivated external plugin $pluginId: $reason")
    }

    /** Call from the app's plugin-removal hook: drops commands and the trust pin. */
    @Synchronized
    fun onRemoved(pluginId: String) {
        commands.unregisterOwner(pluginId)
        onBubbleRemoved(pluginId)
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
                is TrustCheck.Trusted -> {
                    val record = store.plugins().firstOrNull { it.id == entry.packageName }
                    if (record?.enabled == true) register(check.entry) else clearRuntime(entry.packageName)
                }
                is TrustCheck.UpdatePending -> deactivate(entry.packageName, "update needs your approval")
                TrustCheck.SignatureMismatch -> deactivate(entry.packageName, "signing certificate changed")
                TrustCheck.NotInstalled -> deactivate(entry.packageName, "the plugin app is not installed")
                TrustCheck.NotImported -> Unit
            }
        }
    }
}
