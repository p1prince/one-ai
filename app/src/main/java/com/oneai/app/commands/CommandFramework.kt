package com.oneai.app.commands

import com.oneai.app.core.CoreError
import com.oneai.app.core.CoreErrors
import com.oneai.app.plugins.PluginActionInput
import com.oneai.app.plugins.PluginInvocationResult
import com.oneai.app.plugins.PluginManifestValidator
import kotlinx.coroutines.CancellationException

const val CORE_OWNER = "core"

/**
 * A command known to ONE AI. IDs are `owner/name`: `core/<name>` for built-in commands and
 * `<pluginId>/<name>` for commands an extension registers. An extension can therefore never
 * shadow a core command or another plugin's command.
 */
data class CommandDefinition(
    val id: String,
    val owner: String,
    val name: String,
    val title: String,
    val description: String,
    /** Capability the command needs. Required for extension commands; null for core commands. */
    val capability: String? = null
) {
    val isCore: Boolean get() = owner == CORE_OWNER
}

data class CommandOutput(val title: String, val content: String)

/** Handler for a core command. Extension commands are executed by their plugin adapter instead. */
fun interface CommandHandler {
    suspend fun execute(args: Map<String, String>): CommandOutput
}

enum class CommandStatus { COMPLETED, FAILED, REJECTED, AWAITING_APPROVAL, NOT_FOUND }

data class CommandResult(
    val status: CommandStatus,
    val commandId: String,
    val title: String = "",
    val content: String = "",
    val error: CoreError? = null
)

/**
 * Registration and identification of commands. [ownerAvailable] reports whether an extension is
 * currently installed and enabled; commands of unavailable owners are hidden and cannot run.
 */
class CommandRegistry(private val ownerAvailable: (String) -> Boolean = { true }) {
    private val definitions = linkedMapOf<String, CommandDefinition>()
    private val handlers = hashMapOf<String, CommandHandler>()

    @Synchronized
    fun registerCore(name: String, title: String, description: String, handler: CommandHandler): Result<CommandDefinition> {
        val definition = build(CORE_OWNER, name, title, description, null).getOrElse { return Result.failure(it) }
        if (definition.id in definitions) return Result.failure(alreadyRegistered(definition.id))
        definitions[definition.id] = definition
        handlers[definition.id] = handler
        return Result.success(definition)
    }

    /** Called on behalf of a plugin. [capability] must be one the plugin declares in its manifest. */
    @Synchronized
    fun registerExtension(pluginId: String, name: String, title: String, description: String, capability: String): Result<CommandDefinition> {
        if (!PLUGIN_ID.matches(pluginId) || pluginId.length > 100) {
            return Result.failure(CommandException("INVALID_OWNER", "Extension commands need a valid reverse-domain plugin ID."))
        }
        if (capability !in PluginManifestValidator.supportedCapabilities) {
            return Result.failure(CommandException("INVALID_CAPABILITY", "Extension commands must declare a supported capability."))
        }
        val definition = build(pluginId, name, title, description, capability).getOrElse { return Result.failure(it) }
        if (definition.id in definitions) return Result.failure(alreadyRegistered(definition.id))
        if (definitions.values.count { it.owner == pluginId } >= MAX_COMMANDS_PER_EXTENSION) {
            return Result.failure(CommandException("TOO_MANY_COMMANDS", "An extension may register at most $MAX_COMMANDS_PER_EXTENSION commands."))
        }
        definitions[definition.id] = definition
        return Result.success(definition)
    }

    /** Removes every command of an extension (for example when the plugin is removed). Core is protected. */
    @Synchronized
    fun unregisterOwner(owner: String): Int {
        if (owner == CORE_OWNER) return 0
        val ids = definitions.values.filter { it.owner == owner }.map { it.id }
        ids.forEach { definitions.remove(it); handlers.remove(it) }
        return ids.size
    }

    @Synchronized fun definition(id: String): CommandDefinition? = definitions[id]?.takeIf(::available)
    @Synchronized fun handler(id: String): CommandHandler? = handlers[id]
    @Synchronized fun list(): List<CommandDefinition> = definitions.values.filter(::available)
    @Synchronized fun listByOwner(owner: String): List<CommandDefinition> = list().filter { it.owner == owner }

    private fun available(definition: CommandDefinition) = definition.isCore || ownerAvailable(definition.owner)

    private fun alreadyRegistered(id: String) = CommandException("DUPLICATE_COMMAND", "A command with ID '$id' is already registered.")

    private fun build(owner: String, name: String, title: String, description: String, capability: String?): Result<CommandDefinition> {
        if (!NAME.matches(name)) {
            return Result.failure(CommandException("INVALID_NAME", "Command names use lowercase letters, digits and underscores (max 40)."))
        }
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty() || cleanTitle.length > MAX_TITLE) {
            return Result.failure(CommandException("INVALID_TITLE", "Command titles must be 1–$MAX_TITLE characters."))
        }
        val cleanDescription = description.trim()
        if (cleanDescription.length > MAX_DESCRIPTION) {
            return Result.failure(CommandException("INVALID_DESCRIPTION", "Command descriptions must be $MAX_DESCRIPTION characters or fewer."))
        }
        return Result.success(CommandDefinition("$owner/$name", owner, name, cleanTitle, cleanDescription, capability))
    }

    private companion object {
        val NAME = Regex("[a-z][a-z0-9_]{0,39}")
        val PLUGIN_ID = Regex("[a-z][a-z0-9]*(\\.[a-z0-9]+)+")
        const val MAX_TITLE = 80
        const val MAX_DESCRIPTION = 300
        const val MAX_COMMANDS_PER_EXTENSION = 50
    }
}

/**
 * Routes and runs commands. Core commands run their handler directly. Extension commands are
 * delegated to [invokeExtension] (the plugin runtime), so the central security gateway, user
 * approval and plugin failure isolation apply exactly as for any other plugin action.
 * Argument values and outputs are never written to the activity log.
 */
class CommandExecutor(
    private val registry: CommandRegistry,
    private val invokeExtension: suspend (pluginId: String, input: PluginActionInput, approvalTicket: String?) -> PluginInvocationResult,
    private val log: (String) -> Unit = {}
) {
    /** The exact plugin action an extension command would run; use it to request user approval. */
    fun actionFor(commandId: String, args: Map<String, String> = emptyMap()): PluginActionInput? {
        val definition = registry.definition(commandId) ?: return null
        val capability = definition.capability ?: return null
        return PluginActionInput(definition.name, capability, "Run command: ${definition.title}".take(MAX_DESCRIPTION).trim(), args)
    }

    suspend fun execute(commandId: String, args: Map<String, String> = emptyMap(), approvalTicket: String? = null): CommandResult {
        val definition = registry.definition(commandId)
            ?: return CommandResult(CommandStatus.NOT_FOUND, commandId, error = CoreErrors.command("COMMAND_NOT_FOUND", "That command is not available."))
        if (args.size > MAX_ARGS || args.any { (k, v) -> k.isBlank() || k.length > MAX_KEY || v.length > MAX_VALUE }) {
            return finish(definition, CommandResult(CommandStatus.REJECTED, commandId, error = CoreErrors.command("INVALID_ARGUMENTS", "The command input is invalid.")))
        }
        val result = try {
            if (definition.isCore) runCore(definition, args) else runExtension(definition, args, approvalTicket)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CommandResult(CommandStatus.FAILED, commandId, error = CoreErrors.from(error))
        }
        return finish(definition, result)
    }

    private suspend fun runCore(definition: CommandDefinition, args: Map<String, String>): CommandResult {
        val handler = registry.handler(definition.id)
            ?: return CommandResult(CommandStatus.NOT_FOUND, definition.id, error = CoreErrors.command("COMMAND_NOT_FOUND", "That command is not available."))
        val output = handler.execute(args)
        if (output.title.isBlank() || output.title.length > MAX_OUTPUT_TITLE || output.content.length > MAX_OUTPUT) {
            return CommandResult(CommandStatus.FAILED, definition.id, error = CoreErrors.command("INVALID_OUTPUT", "The command returned data outside the supported limits."))
        }
        return CommandResult(CommandStatus.COMPLETED, definition.id, output.title, output.content)
    }

    private suspend fun runExtension(definition: CommandDefinition, args: Map<String, String>, ticket: String?): CommandResult {
        val input = actionFor(definition.id, args)
            ?: return CommandResult(CommandStatus.REJECTED, definition.id, error = CoreErrors.command("INVALID_COMMAND", "The command is not runnable."))
        return when (val outcome = invokeExtension(definition.owner, input, ticket)) {
            is PluginInvocationResult.Completed -> CommandResult(CommandStatus.COMPLETED, definition.id, outcome.output.title, outcome.output.content)
            is PluginInvocationResult.AwaitingApproval -> CommandResult(CommandStatus.AWAITING_APPROVAL, definition.id, content = outcome.reason)
            is PluginInvocationResult.Rejected -> CommandResult(CommandStatus.REJECTED, definition.id, error = CoreErrors.plugin("REJECTED", outcome.reason))
            is PluginInvocationResult.Failed -> CommandResult(CommandStatus.FAILED, definition.id, error = CoreErrors.plugin("PLUGIN_FAILED", outcome.message))
        }
    }

    private fun finish(definition: CommandDefinition, result: CommandResult): CommandResult {
        log("Command ${result.status.name.lowercase().replace('_', ' ')}: ${definition.id}")
        return result
    }

    private companion object {
        const val MAX_ARGS = 32
        const val MAX_KEY = 80
        const val MAX_VALUE = 4_000
        const val MAX_DESCRIPTION = 300
        const val MAX_OUTPUT_TITLE = 120
        const val MAX_OUTPUT = 20_000
    }
}
