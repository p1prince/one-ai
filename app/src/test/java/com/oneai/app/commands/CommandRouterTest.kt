package com.oneai.app.commands

import com.oneai.app.ai.AiActivityLogger
import com.oneai.app.ai.AiCompletion
import com.oneai.app.ai.AiMessage
import com.oneai.app.ai.AiOrchestrator
import com.oneai.app.ai.AiProvider
import com.oneai.app.core.CommandRecord
import com.oneai.app.core.PluginInvocationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRouterTest {
    private class MemoryCommands : CommandRepository, AiActivityLogger {
        val records = mutableListOf<CommandRecord>()
        val events = mutableListOf<String>()
        override fun commands() = records.toList()
        override fun saveCommand(command: CommandRecord) {
            records.removeAll { it.id == command.id }
            records += command
        }
        override fun removeCommand(id: String) { records.removeAll { it.id == id } }
        override fun log(message: String) { events += message }
    }

    private class FakeProvider : AiProvider {
        var lastPrompt: String? = null
        override suspend fun complete(messages: List<AiMessage>): AiCompletion {
            lastPrompt = messages.last().content
            return AiCompletion("done", "fake", "test")
        }
    }

    @Test
    fun newCommandsAreSavedDisabled() {
        val repository = MemoryCommands()
        val router = CommandRouter(repository, AiOrchestrator(FakeProvider(), repository))

        val created = router.add("  Daily summary  ", "  Summarize today's notes  ").getOrThrow()

        assertEquals("Daily summary", created.title)
        assertEquals("Summarize today's notes", created.prompt)
        assertFalse(created.enabled)
        assertFalse(repository.commands().single().enabled)
    }

    @Test
    fun rejectsBlankOrOversizedCommands() {
        val repository = MemoryCommands()
        val router = CommandRouter(repository, AiOrchestrator(FakeProvider(), repository))

        assertTrue(router.add(" ", "instruction").isFailure)
        assertTrue(router.add("Name", " ").isFailure)
        assertTrue(router.add("x".repeat(81), "instruction").isFailure)
        assertTrue(router.add("Name", "x".repeat(20_001)).isFailure)
        assertTrue(repository.commands().isEmpty())
    }

    @Test
    fun disabledCommandCannotRunAndEnabledCommandUsesAiOrchestrator() = runBlocking {
        val repository = MemoryCommands()
        val provider = FakeProvider()
        val router = CommandRouter(repository, AiOrchestrator(provider, repository))
        val command = router.add("Test", "Do the task").getOrThrow()

        assertThrows(CommandException::class.java) { runBlocking { router.run(command.id) } }
        assertTrue(router.setEnabled(command.id, true))
        val result = router.run(command.id)

        assertEquals("done", result.content)
        assertEquals("Do the task", provider.lastPrompt)
        assertTrue(repository.events.any { it.contains("Command completed") })
    }

    @Test
    fun savedPromptRunsThroughTheCoreCommandExecutorHandler() = runBlocking {
        val repository = MemoryCommands()
        val provider = FakeProvider()
        val router = CommandRouter(repository, AiOrchestrator(provider, repository))
        val saved = router.add("Shared route", "Use the shared engine").getOrThrow()
        router.setEnabled(saved.id, true)
        val registry = CommandRegistry()
        registry.registerCore("run_saved", "Run saved command", "") { args ->
            val id = args["id"] ?: throw CommandException("INVALID_ARGUMENT", "Missing command ID.")
            val completion = router.run(id)
            CommandOutput("Command result", completion.content)
        }
        val executor = CommandExecutor(registry, { _, _, _ ->
            PluginInvocationResult.Failed("Core command must not call plugin runtime.")
        })
        val result = executor.execute("core/run_saved", mapOf("id" to saved.id))
        assertEquals(CommandStatus.COMPLETED, result.status)
        assertEquals("done", result.content)
        assertEquals("Use the shared engine", provider.lastPrompt)
    }

    @Test
    fun removeDeletesCommand() {
        val repository = MemoryCommands()
        val router = CommandRouter(repository, AiOrchestrator(FakeProvider(), repository))
        val command = router.add("Delete me", "No-op").getOrThrow()

        assertTrue(router.remove(command.id))
        assertTrue(repository.commands().isEmpty())
        assertFalse(router.remove(command.id))
    }
}
