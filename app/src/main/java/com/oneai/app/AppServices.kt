package com.oneai.app

import android.app.Application
import android.content.Context
import com.oneai.app.bubble.BubbleHost
import com.oneai.app.bubble.BubbleProviders
import com.oneai.app.bubble.BubbleUiPolicy
import com.oneai.app.bubble.ExternalBubbleProvider
import com.oneai.app.ai.AiOrchestrator
import com.oneai.app.ai.AiProvider
import com.oneai.app.ai.AiProviders
import com.oneai.app.ai.ActiveAiProvider
import com.oneai.app.ai.ProviderProfiles
import com.oneai.app.ai.VaultProfileSecrets
import com.oneai.app.ai.RetryingAiProvider
import com.oneai.app.commands.CommandException
import com.oneai.app.commands.CommandExecutor
import com.oneai.app.commands.CommandOutput
import com.oneai.app.commands.CommandRegistry
import com.oneai.app.commands.CommandRouter
import com.oneai.app.core.ActivityKind
import com.oneai.app.core.CoreReservedNames
import com.oneai.app.core.OneAiStore
import com.oneai.app.plugins.AndroidPackageSignatureSource
import com.oneai.app.plugins.BoundPluginChannelFactory
import com.oneai.app.plugins.CatalogueRefreshGate
import com.oneai.app.plugins.CatalogueSources
import com.oneai.app.plugins.ExternalPluginCenter
import com.oneai.app.plugins.ExternalPluginRegistrar
import com.oneai.app.plugins.HttpsCatalogueTransport
import com.oneai.app.plugins.InAppPluginController
import com.oneai.app.plugins.PluginConfiguration
import com.oneai.app.plugins.PluginDiscovery
import com.oneai.app.plugins.PluginLaunchApprovalBroker
import com.oneai.app.plugins.PluginLaunchApprovalController
import com.oneai.app.plugins.PluginManager
import com.oneai.app.plugins.PluginTrustRegistry
import com.oneai.app.plugins.SharedPrefsTrustStore
import com.oneai.app.plugins.TrustCheck
import com.oneai.app.security.SecretVault
import kotlinx.coroutines.CoroutineScope

/**
 * Process-wide composition of the plugin system. One instance serves the app screens and the Floating
 * Bubble service, so every part sees the same plugins, commands, trust pins and approvals.
 *
 * It starts nothing on its own: with no plugins installed or imported it holds an empty command registry
 * (apart from core commands), an empty trust store and no Bubble providers for external plugins.
 */
class AppServices private constructor(private val app: Application) {
    val store = OneAiStore(app)
    /** Plugin-dependent callers suspend here until the startup restore pass has finished. */
    val pluginRestoration = PluginRestorationGate()

    val commandRegistry = CommandRegistry { pluginId -> store.plugins().any { it.id == pluginId && it.enabled } }

    /** Production host composition; the same type is exercised with an empty catalogue in JVM tests. */
    val pluginHost = PluginHostComposition(
        store, emptyList(), BubbleProviders.registry,
        onPluginRemoved = { id -> center.onRemoved(id); commandRegistry.unregisterOwner(id) },
        bubbleBindings = { center.bubbleBindings() },
        configStore = store
    )
    val pluginExecution = pluginHost.execution
    val pluginRuntime = pluginExecution.runtime
    val approvalGateway = pluginExecution.approvalGateway

    /** AI and saved-command services are process-scoped and shared by Home and Floating Bubble. */
    val vault = SecretVault(app)
    private val profileSecrets = VaultProfileSecrets(vault)
    private val aiRegistry = AiProviders.registry(profileSecrets)
    /** Saved AI service profiles and the active one (decision D-V / V1). Home, Bubble and Settings share this. */
    val providerProfiles = ProviderProfiles(store, profileSecrets, aiRegistry::kinds) { store.log(it) }
    val aiProvider: AiProvider = ActiveAiProvider(providerProfiles, aiRegistry)
    val aiOrchestrator = AiOrchestrator(RetryingAiProvider(aiProvider), store)
    val commandRouter = CommandRouter(store, aiOrchestrator)
    val commandExecutor = CommandExecutor(
        commandRegistry,
        { pluginId, input, ticket -> pluginExecution.executor.invoke(pluginId, input, ticket) },
        { store.log(it) }
    )

    private val packageSignatures = AndroidPackageSignatureSource(app.packageManager)
    val trust = PluginTrustRegistry(SharedPrefsTrustStore(app), packageSignatures) { store.log(it) }
    /** Process-scoped, host-only queue; Stage 9.4c connects it to the Bubble confirmation surface. */
    val pluginLaunchApprovals = PluginLaunchApprovalBroker()
    val pluginLaunchApprovalController = PluginLaunchApprovalController(app, pluginLaunchApprovals, trust, store)

    /** Stage 9.6b: host-owned per-plugin settings. Only a currently trusted plugin has configurable settings. */
    val pluginConfiguration = PluginConfiguration(
        store,
        schemaOf = { pluginId -> (trust.peek(pluginId) as? TrustCheck.Trusted)?.entry?.trustedManifest?.settings },
        log = { store.log(it) }
    )

    val registrar = ExternalPluginRegistrar(
        store, pluginRuntime, pluginRuntime, trust, commandRegistry, BoundPluginChannelFactory(app, launchBroker = pluginLaunchApprovals),
        reservedIds = CoreReservedNames.pluginIds,
        onBubbleService = { manifest, viewSource ->
            // A refused registration (reserved or duplicate service name) simply leaves the plugin out of the Bubble.
            BubbleProviders.registry.register(ExternalBubbleProvider(manifest, viewSource))
        },
        onBubbleRemoved = { pluginId ->
            pluginLaunchApprovals.clearPlugin(pluginId)
            BubbleProviders.registry.unregisterPlugin(pluginId)
        },
        configuration = pluginConfiguration
    )

    /**
     * Stage 9.7c: the user's catalogue sources (none by default). Construction and adding or removing a source
     * never touch the network; only an explicit Refresh does, gated by [catalogueRefreshGate].
     */
    val catalogueSources = CatalogueSources(store, HttpsCatalogueTransport()) { store.log(it) }
    val catalogueRefreshGate = CatalogueRefreshGate()

    lateinit var center: ExternalPluginCenter
        private set

    val plugins: PluginManager = pluginHost.manager

    init {
        center = ExternalPluginCenter(
            discover = PluginDiscovery(app)::discover, trust = trust, registrar = registrar, manager = plugins,
            reservedBubbleServices = CoreReservedNames.bubbleServices + BubbleUiPolicy.COMMAND_MANAGER,
            configuration = pluginConfiguration,
            installedSigners = packageSignatures::currentSigners,
            onUpdateCheck = { report -> store.log("Update check: ${report.summary()}", ActivityKind.PLUGIN, null) }
        )
        commandRegistry.registerCore("run_saved", "Run saved command", "Runs one of your saved commands through the AI engine.") { args ->
            val id = args["id"] ?: throw CommandException("INVALID_ARGUMENT", "A command ID is required.")
            CommandOutput("Command result", commandRouter.run(id).content)
        }
    }

    /**
     * Slice C3 (decision D12): the controller for one plugin's screen inside the app. It uses the plugin's own provider
     * view and the shared executor, exactly as the Bubble does, and refuses a plugin that is not currently openable
     * (trusted, Enabled, declares a screen, settings complete). Bubble access is never consulted. Null means "not
     * openable right now".
     */
    fun inAppPluginController(pluginId: String, scope: CoroutineScope): InAppPluginController? {
        if (center.inAppPlugins().none { it.pluginId == pluginId }) return null
        val provider = BubbleProviders.registry.providers().firstOrNull { it.pluginId == pluginId } ?: return null
        val host = object : BubbleHost {
            override val context: Context get() = app
            override fun hideBubble() = Unit
        }
        return InAppPluginController(
            pluginId = pluginId,
            scope = scope,
            executor = pluginExecution.executor,
            loadView = { provider.loadView(host) },
            prepare = { pluginRestoration.awaitReady(); provider.ensureReady(host) },
            onOpened = { presenter -> pluginLaunchApprovalController.attachInApp(pluginId, presenter) },
            onClosed = { pluginLaunchApprovalController.detachInApp() }
        )
    }

    /**
     * Brings back imported plugins that are still trusted and takes every other one out of service.
     * The readiness barrier is always released, including when restoration fails, so the core shell
     * remains usable and plugin callers never wait forever.
     */
    fun restorePlugins() {
        try {
            runCatching { registrar.restoreAll() }
                .onFailure { store.log("Could not restore plugins: ${it.javaClass.simpleName}") }
        } finally {
            pluginRestoration.complete()
        }
    }

    companion object {
        @Volatile private var instance: AppServices? = null

        fun get(app: Application): AppServices =
            instance ?: synchronized(this) { instance ?: AppServices(app).also { instance = it } }
    }
}
