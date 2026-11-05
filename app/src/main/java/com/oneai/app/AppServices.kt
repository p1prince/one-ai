package com.oneai.app

import android.app.Application
import com.oneai.app.bubble.BubbleProviders
import com.oneai.app.bubble.BubbleUiPolicy
import com.oneai.app.bubble.ExternalBubbleProvider
import com.oneai.app.commands.CommandException
import com.oneai.app.commands.CommandOutput
import com.oneai.app.commands.CommandRegistry
import com.oneai.app.commands.CommandRouter
import com.oneai.app.core.OneAiStore
import com.oneai.app.plugins.AndroidPackageSignatureSource
import com.oneai.app.plugins.BoundPluginChannelFactory
import com.oneai.app.plugins.BuiltInCatalogue
import com.oneai.app.plugins.ExternalPluginCenter
import com.oneai.app.plugins.ExternalPluginRegistrar
import com.oneai.app.plugins.PluginDiscovery
import com.oneai.app.plugins.PluginManager
import com.oneai.app.plugins.PluginTrustRegistry
import com.oneai.app.plugins.SharedPrefsTrustStore

/**
 * Process-wide composition of the plugin system. One instance serves the app screens and the Floating
 * Bubble service, so every part sees the same plugins, commands, trust pins and approvals.
 *
 * It starts nothing on its own: with no plugins installed or imported it holds an empty command registry
 * (apart from core commands), an empty trust store and no Bubble providers for external plugins.
 */
class AppServices private constructor(app: Application) {
    val store = OneAiStore(app)

    val commandRegistry = CommandRegistry { pluginId -> store.plugins().any { it.id == pluginId && it.enabled } }

    /** Production host composition; the same type is exercised with an empty catalogue in JVM tests. */
    val pluginHost = PluginHostComposition(
        store, BuiltInCatalogue.templates, BubbleProviders.registry,
        onPluginRemoved = { id -> center.onRemoved(id); commandRegistry.unregisterOwner(id) },
        bubbleBindings = { center.bubbleBindings() }
    )
    val pluginExecution = pluginHost.execution
    val pluginRuntime = pluginExecution.runtime
    val approvalGateway = pluginExecution.approvalGateway

    /** Set by the main screen; core command `core/run_saved` runs through it. Null until a screen exists. */
    @Volatile var commandRouter: CommandRouter? = null

    val trust = PluginTrustRegistry(SharedPrefsTrustStore(app), AndroidPackageSignatureSource(app.packageManager)) { store.log(it) }

    val registrar = ExternalPluginRegistrar(
        store, pluginRuntime, pluginRuntime, trust, commandRegistry, BoundPluginChannelFactory(app),
        reservedIds = BuiltInCatalogue.templates.map { it.id }.toSet(),
        onBubbleService = { manifest, viewSource ->
            // A refused registration (reserved or duplicate service name) simply leaves the plugin out of the Bubble.
            BubbleProviders.registry.register(ExternalBubbleProvider(manifest, viewSource))
        },
        onBubbleRemoved = { pluginId -> BubbleProviders.registry.unregisterPlugin(pluginId) }
    )

    lateinit var center: ExternalPluginCenter
        private set

    val plugins: PluginManager = pluginHost.manager

    init {
        center = ExternalPluginCenter(
            discover = PluginDiscovery(app)::discover, trust = trust, registrar = registrar, manager = plugins,
            reservedBubbleServices = BuiltInCatalogue.bubbleBindings.values.toSet() + BubbleUiPolicy.COMMAND_MANAGER
        )
        commandRegistry.registerCore("run_saved", "Run saved command", "Runs one of your saved commands through the AI engine.") { args ->
            val id = args["id"] ?: throw CommandException("INVALID_ARGUMENT", "A command ID is required.")
            val router = commandRouter ?: throw CommandException("UNAVAILABLE", "Open ONE AI to run saved commands.")
            CommandOutput("Command result", router.run(id).content)
        }
    }

    /**
     * Brings back imported plugins that are still trusted and takes every other one out of service. A failure
     * here must never stop the app from starting, so it is contained and reported in Activity.
     */
    fun restorePlugins() {
        runCatching { registrar.restoreAll() }.onFailure { store.log("Could not restore plugins: ${it.javaClass.simpleName}") }
    }

    companion object {
        @Volatile private var instance: AppServices? = null

        fun get(app: Application): AppServices =
            instance ?: synchronized(this) { instance ?: AppServices(app).also { instance = it } }
    }
}
