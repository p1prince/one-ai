package com.oneai.app.commands

import android.content.Context
import com.oneai.app.core.OneAiStore

class CommandRouter(private val context: Context, private val store: OneAiStore) {
    fun run(commandId: String): String {
        val command = store.commands().firstOrNull { it.id == commandId && it.enabled }
            ?: return "Command is unavailable or disabled."
        store.log("Command submitted: ${command.title}")
        // Command text is passed to the AI layer; no arbitrary shell or code execution is permitted.
        return command.prompt
    }

    fun add(title: String, prompt: String): Result<Unit> {
        if (title.isBlank() || prompt.isBlank()) return Result.failure(IllegalArgumentException("Both a command name and instruction are required."))
        val id = "cmd_" + java.util.UUID.randomUUID().toString()
        store.saveCommand(com.oneai.app.core.CommandRecord(id, title.trim(), prompt.trim()))
        store.log("Created command: ${title.trim()}")
        return Result.success(Unit)
    }
}
