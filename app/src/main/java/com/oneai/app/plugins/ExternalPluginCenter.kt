package com.oneai.app.plugins

import com.oneai.app.core.PluginRecord

/** A plugin app found on the device. Nothing about it is trusted until the user imports it. */
data class DiscoveredPlugin(
    val packageName: String,
    val appLabel: String,
    val serviceClass: String,
    val manifest: Result<ExternalPluginManifest>
)

/** A plugin app found on the device, and whether the user has already imported (trusted) it. */
data class PluginCandidate(val plugin: DiscoveredPlugin, val imported: Boolean)

/** The trust state of an imported plugin as the Plugins screen shows it. */
sealed interface ExternalStatus {
    data object Ready : ExternalStatus
    /** An installed update asked for more powers; the plugin is off until the user approves it. */
    data class UpdatePending(val reasons: List<String>) : ExternalStatus
    /** Signed with a different key than the one the user trusted. It stays blocked. */
    data object SignatureChanged : ExternalStatus
    data object NotInstalled : ExternalStatus
}

/**
 * One-shot gate for explicit update checks. A screen redraw cannot replay a consumed request.
 * Kept as a small pure state holder so the event semantics are covered by JVM tests.
 */
class PluginUpdateCheckGate {
    private var pending = false

    @Synchronized fun request() { pending = true }

    @Synchronized fun consume(): Boolean {
        if (!pending) return false
        pending = false
        return true
    }
}

/** Everything the Plugins screen draws, computed together so it can be built off the main thread. */
data class PluginCenterSnapshot(
    val candidates: List<PluginCandidate>,
    val statuses: Map<String, ExternalStatus>,
    val pendingUpdates: Map<String, PendingUpdate>
) {
    companion object { val EMPTY = PluginCenterSnapshot(emptyList(), emptyMap(), emptyMap()) }
}

/**
 * The one place the app talks to about plugins that are separate APKs: finding them, importing them with
 * the fingerprint the user confirmed, turning them on and off, reviewing updates and removing them. Pure
 * logic over [PluginTrustRegistry], [ExternalPluginRegistrar] and [PluginManager], so it is JVM-testable.
 *
 * Importing never enables a plugin and never gives it Bubble access; both stay separate user decisions.
 */
class ExternalPluginCenter(
    private val discover: () -> List<DiscoveredPlugin>,
    private val trust: PluginTrustRegistry,
    private val registrar: ExternalPluginRegistrar,
    private val manager: PluginManager,
    /** Bubble service names that already belong to the core or to built-in plugins; no other plugin may take them. */
    private val reservedBubbleServices: Set<String> = emptySet(),
    /** Per-plugin configuration (Stage 9.6b). Null means this center applies no configuration rules. */
    private val configuration: PluginConfiguration? = null
) {
    /** Read-only view: rendering must never mutate plugin lifecycle. */
    fun snapshot(): PluginCenterSnapshot {
        val found = discover()
        val entries = trust.trusted()
        val importedIds = entries.map { it.packageName }.toSet()
        return PluginCenterSnapshot(
            candidates = found.map { PluginCandidate(it, it.packageName in importedIds) },
            statuses = entries.mapNotNull { entry -> statusOf(trust.peek(entry.packageName))?.let { entry.packageName to it } }.toMap(),
            pendingUpdates = entries.mapNotNull { entry -> entry.pendingUpdate?.let { entry.packageName to it } }.toMap()
        )
    }

    /** Explicit user-initiated update check; opening or redrawing Plugin Centre is read-only. */
    fun checkForUpdates() { applyUpdates(discover()) }

    /** An installed update is judged by the trust rules: same or fewer powers is accepted, more waits for the user. */
    private fun applyUpdates(found: List<DiscoveredPlugin>) {
        found.forEach { discovered ->
            val installed = discovered.manifest.getOrNull() ?: return@forEach
            val entry = trust.trusted().firstOrNull { it.packageName == discovered.packageName } ?: return@forEach
            if (installed == entry.trustedManifest || installed == entry.pendingUpdate?.manifest) return@forEach
            if (registrar.applyUpdate(installed) == UpdateDecision.Accepted) reconcileConfiguration(discovered.packageName)
        }
    }

    /** After the trusted declaration changed, keep, reset or drop stored values to match the new schema. */
    private fun reconcileConfiguration(packageName: String) {
        val config = configuration ?: return
        val entry = trust.trusted().firstOrNull { it.packageName == packageName } ?: return
        config.reconcile(packageName, entry.trustedManifest.settings)
    }

    /** The certificate fingerprint to show the user before they decide. */
    fun fingerprint(packageName: String): Result<String> = trust.fingerprintForImport(packageName)

    /**
     * Imports the plugin the user reviewed. The plugin is read again now and must still be the version that
     * was shown; [confirmedFingerprint] must match the installed certificate. A plugin that cannot be
     * registered is not left half-imported: its pin is removed again.
     */
    fun import(packageName: String, reviewedVersionCode: Long, confirmedFingerprint: String): Result<PluginRecord> {
        val found = discover().firstOrNull { it.packageName == packageName }
            ?: return Result.failure(IllegalStateException("The plugin app is no longer installed"))
        val manifest = found.manifest.getOrElse { return Result.failure(it) }
        if (manifest.versionCode != reviewedVersionCode) {
            return Result.failure(IllegalStateException("The plugin changed while you were reviewing it. Review it again."))
        }
        val entry = trust.import(manifest, confirmedFingerprint).getOrElse { return Result.failure(it) }
        return registrar.register(entry).onFailure { trust.forget(entry.packageName) }
    }

    /**
     * Turns a plugin on or off. An external plugin is taken fully out of service when turned off, so turning
     * it on re-checks its signature and registers it again; it never gets Bubble access by being enabled.
     */
    fun setEnabled(id: String, enabled: Boolean): Result<Unit> {
        if (!isExternal(id)) return manager.setEnabled(id, enabled)
        if (!enabled) {
            manager.setEnabled(id, false).onFailure { return Result.failure(it) }
            registrar.deactivate(id, "turned off by you")
            return Result.success(Unit)
        }
        val check = trust.check(id)
        val entry = (check as? TrustCheck.Trusted)?.entry ?: return Result.failure(IllegalStateException(blockedReason(check)))
        // D-C: a plugin with unmet required settings cannot be enabled. Nothing is registered or changed.
        val missing = configuration?.missingRequired(id).orEmpty()
        if (missing.isNotEmpty()) {
            return Result.failure(IllegalStateException("Set the required settings first: ${missing.joinToString { it.title }}"))
        }
        registrar.register(entry).onFailure { return Result.failure(it) }
        manager.setEnabled(id, true).onFailure {
            registrar.deactivate(id, "could not be turned on")
            return Result.failure(it)
        }
        registrar.activate(id).onFailure {
            manager.setEnabled(id, false)
            registrar.deactivate(id, "activation failed")
            return Result.failure(it)
        }
        return Result.success(Unit)
    }

    /** The user approved the declaration they were shown. The plugin stays off until they turn it on. */
    fun approveUpdate(id: String): Result<PluginRecord> =
        registrar.approvePendingUpdate(id).onSuccess { reconcileConfiguration(id) }

    fun isExternal(id: String): Boolean = trust.trusted().any { it.packageName == id }

    /**
     * Bubble services of imported plugins, known from what the user trusted and not from what is registered right
     * now. A service name that is reserved, or claimed by more than one plugin, is given to nobody: allowing one
     * plugin into the Bubble must never open another plugin's service.
     */
    fun bubbleBindings(): Map<String, String> {
        val claims = trust.trusted().mapNotNull { entry -> entry.trustedManifest.bubble?.let { entry.packageName to it.service } }
        return claims.filter { (_, service) -> service !in reservedBubbleServices && claims.count { it.second == service } == 1 }.toMap()
    }

    /** True when the plugin asks for a Bubble service name that is already taken, so it would not appear in the Bubble. */
    fun bubbleServiceConflict(manifest: ExternalPluginManifest): Boolean {
        val service = manifest.bubble?.service ?: return false
        return service in reservedBubbleServices ||
            trust.trusted().any { it.packageName != manifest.packageName && it.trustedManifest.bubble?.service == service }
    }

    /** Hook for [PluginManager]'s removal. Returns false for a plugin this center does not manage. */
    fun onRemoved(id: String): Boolean {
        if (!isExternal(id)) return false
        registrar.onRemoved(id)
        return true
    }

    private fun statusOf(check: TrustCheck): ExternalStatus? = when (check) {
        is TrustCheck.Trusted -> ExternalStatus.Ready
        is TrustCheck.UpdatePending -> ExternalStatus.UpdatePending(check.reasons)
        TrustCheck.SignatureMismatch -> ExternalStatus.SignatureChanged
        TrustCheck.NotInstalled -> ExternalStatus.NotInstalled
        TrustCheck.NotImported -> null
    }

    private fun blockedReason(check: TrustCheck): String = when (check) {
        is TrustCheck.Trusted -> "The plugin is trusted."
        is TrustCheck.UpdatePending -> "Review the plugin's update before turning it on."
        TrustCheck.SignatureMismatch -> "This plugin is signed with a different certificate than the one you trusted, so it stays blocked."
        TrustCheck.NotInstalled -> "The plugin app is not installed."
        TrustCheck.NotImported -> "Import the plugin first."
    }
}
