package com.oneai.app.commands

import com.oneai.app.ai.AiCompletion
import com.oneai.app.ai.AiOrchestrator
import com.oneai.app.core.CommandRecord
import com.oneai.app.core.OneAiStore
import java.util.UUID

/**
 * Validates and dispatches saved commands through the shared AI runtime.
 * It never interprets command text as shell code or executable Kotlin.
 */
class CommandRouter(
    private val store: OneAiStore,
    private val orchestrator: AiOrchestrator
) {
    suspend fun run(commandId: String): AiCompletion {
        val command = store.commands().firstOrNull { it.id == commandId && it.enabled }
            ?: throw CommandException("COMMAND_UNAVAILABLE", "This command is missing or disabled.")
        store.log("Command started: ${command.title}")
        return try {
            orchestrator.respond(command.prompt).also {
                store.log("Command completed: ${command.title}")
            }
        } catch (error: Exception) {
            store.log("Command failed: ${command.title}")
            throw error
        }
    }

    fun add(title: String, prompt: String): Result<CommandRecord> {
        val cleanTitle = title.trim()
        val cleanPrompt = prompt.trim()
        if (cleanTitle.isEmpty()) return Result.failure(CommandException("INVALID_TITLE", "Enter a command name."))
        if (cleanTitle.length > MAX_TITLE_LENGTH) return Result.failure(CommandException("INVALID_TITLE", "Command names must be 80 characters or fewer."))
        if (cleanPrompt.isEmpty()) return Result.failure(CommandException("INVALID_PROMPT", "Enter an instruction."))
        if (cleanPrompt.length > MAX_PROMPT_LENGTH) return Result.failure(CommandException("INVALID_PROMPT", "Command instructions must be 20,000 characters or fewer."))

        val record = CommandRecord(
            id = "cmd_${UUID.randomUUID()}",
            title = cleanTitle,
            prompt = cleanPrompt,
            enabled = false
        )
        store.saveCommand(record)
        store.log("Created command: ${record.title} (disabled by default)")
        return Result.success(record)
    }

    fun setEnabled(commandId: String, enabled: Boolean): Boolean {
        val existing = store.commands().firstOrNull { it.id == commandId } ?: return false
        store.saveCommand(existing.copy(enabled = enabled))
        store.log("${if (enabled) "Enabled" else "Disabled"} command: ${existing.title}")
        return true
    }

    fun remove(commandId: String): Boolean {
        val existing = store.commands().firstOrNull { it.id == commandId } ?: return false
        store.removeCommand(commandId)
        store.log("Removed command: ${existing.title}")
        return true
    }

    companion object {
        private const val MAX_TITLE_LENGTH = 80
        private const val MAX_PROMPT_LENGTH = 20_000
    }
}

class CommandException(
    val code: String,
    message: String
) : IllegalStateException(message)
