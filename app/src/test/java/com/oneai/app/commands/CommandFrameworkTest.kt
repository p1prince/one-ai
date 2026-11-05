package com.oneai.app.commands

import com.oneai.app.plugins.PluginActionInput
import com.oneai.app.plugins.PluginActionOutput
import com.oneai.app.core.PluginInvocationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandFrameworkTest {
    private val echo = CommandHandler { args -> CommandOutput("Echo", args["text"].orEmpty()) }

    private class Invoker(var next: PluginInvocationResult) {
        val calls = mutableListOf<Triple<String, PluginActionInput, String?>>()
        suspend fun invoke(id: String, input: PluginActionInput, ticket: String?): PluginInvocationResult {
            calls += Triple(id, input, ticket); return next
        }
    }

    private fun executor(registry: CommandRegistry, invoker: Invoker, logs: MutableList<String> = mutableListOf()) =
        CommandExecutor(registry, invoker::invoke) { logs += it }

    // ---- registration / identification ----
    @Test fun coreCommandsAreIdentifiedAsCoreSlashName() {
        val r = CommandRegistry()
        assertEquals("core/echo", r.registerCore("echo", "Echo", "Echoes", echo).getOrThrow().id)
        assertTrue(r.registerCore("echo", "Again", "", echo).isFailure)
        assertEquals(listOf("core/echo"), r.list().map { it.id })
    }

    @Test fun extensionCommandsAreNamespacedByPluginAndCannotShadowCore() {
        val r = CommandRegistry()
        r.registerCore("echo", "Echo", "", echo)
        val d = r.registerExtension("dev.example.notes", "echo", "Notes echo", "", "commands").getOrThrow()
        assertEquals("dev.example.notes/echo", d.id)
        assertFalse(d.isCore)
        assertEquals(setOf("core/echo", "dev.example.notes/echo"), r.list().map { it.id }.toSet())
        assertTrue(r.registerExtension("core", "x", "X", "", "commands").isFailure)
    }

    @Test fun invalidRegistrationsAreRejected() {
        val r = CommandRegistry()
        assertTrue(r.registerCore("Bad Name", "T", "", echo).isFailure)
        assertTrue(r.registerCore("ok", " ", "", echo).isFailure)
        assertTrue(r.registerCore("ok", "T".repeat(81), "", echo).isFailure)
        assertTrue(r.registerExtension("notaplugin", "x", "X", "", "commands").isFailure)
        assertTrue(r.registerExtension("dev.example.a", "x", "X", "", "root_everything").isFailure)
        assertTrue(r.list().isEmpty())
    }

    @Test fun extensionCommandCountIsBounded() {
        val r = CommandRegistry()
        repeat(50) { assertTrue(r.registerExtension("dev.example.a", "c$it", "C", "", "commands").isSuccess) }
        assertTrue(r.registerExtension("dev.example.a", "c50", "C", "", "commands").isFailure)
        assertTrue(r.registerExtension("dev.example.b", "c0", "C", "", "commands").isSuccess)
    }

    @Test fun commandsOfUnavailableOwnersAreHiddenAndUnregisterOwnerProtectsCore() {
        var enabled = true
        val r = CommandRegistry { enabled }
        r.registerCore("echo", "Echo", "", echo)
        r.registerExtension("dev.example.a", "do", "Do", "", "commands")
        assertEquals(2, r.list().size)
        enabled = false
        assertEquals(listOf("core/echo"), r.list().map { it.id })
        assertNull(r.definition("dev.example.a/do"))
        assertEquals(0, r.unregisterOwner(CORE_OWNER))
        assertEquals(1, r.unregisterOwner("dev.example.a"))
        enabled = true
        assertNull(r.definition("dev.example.a/do"))
    }

    @Test fun disabledExtensionCommandCannotReachTheHostRuntime() = runBlocking {
        var enabled = true
        val r = CommandRegistry { enabled }
        r.registerExtension("dev.example.notes", "read", "Read notes", "", "files")
        val inv = Invoker(PluginInvocationResult.Completed(PluginActionOutput("Done", "unexpected")))
        val ex = executor(r, inv)
        assertEquals(CommandStatus.COMPLETED, ex.execute("dev.example.notes/read").status)
        enabled = false
        assertEquals(CommandStatus.NOT_FOUND, ex.execute("dev.example.notes/read").status)
        assertEquals(1, inv.calls.size)
    }

    // ---- core execution ----
    @Test fun coreCommandsRunWithNoPluginsAndNeverTouchThePluginRuntime() = runBlocking {
        val r = CommandRegistry { false }
        r.registerCore("echo", "Echo", "", echo)
        val inv = Invoker(PluginInvocationResult.Failed("should not be called"))
        val res = executor(r, inv).execute("core/echo", mapOf("text" to "hi"))
        assertEquals(CommandStatus.COMPLETED, res.status); assertEquals("hi", res.content)
        assertTrue(inv.calls.isEmpty())
    }

    @Test fun unknownCommandReturnsNotFound() = runBlocking {
        val res = executor(CommandRegistry(), Invoker(PluginInvocationResult.Failed("x"))).execute("core/missing")
        assertEquals(CommandStatus.NOT_FOUND, res.status); assertNotNull(res.error)
    }

    @Test fun handlerFailuresAreContainedAndMessagesAreSafe() = runBlocking {
        val r = CommandRegistry()
        r.registerCore("boom", "Boom", "") { error("api-key=SECRET") }
        r.registerCore("typed", "Typed", "") { throw CommandException("COMMAND_UNAVAILABLE", "Missing or disabled.") }
        val ex = executor(r, Invoker(PluginInvocationResult.Failed("x")))
        val boom = ex.execute("core/boom")
        assertEquals(CommandStatus.FAILED, boom.status); assertFalse(boom.error!!.message.contains("SECRET"))
        val typed = ex.execute("core/typed")
        assertEquals("COMMAND_UNAVAILABLE", typed.error!!.code)
    }

    @Test fun cancellationPropagatesAndIsNotReportedAsAFailure() {
        val r = CommandRegistry()
        r.registerCore("cancel", "Cancel", "") { throw CancellationException("stop") }
        assertThrows(CancellationException::class.java) {
            runBlocking { executor(r, Invoker(PluginInvocationResult.Failed("x"))).execute("core/cancel") }
        }
    }

    @Test fun invalidArgumentsAndOversizedOutputAreRejected() = runBlocking {
        val r = CommandRegistry()
        r.registerCore("echo", "Echo", "", echo)
        r.registerCore("big", "Big", "") { CommandOutput("Big", "x".repeat(20_001)) }
        val ex = executor(r, Invoker(PluginInvocationResult.Failed("x")))
        assertEquals(CommandStatus.REJECTED, ex.execute("core/echo", mapOf("text" to "x".repeat(4_001))).status)
        assertEquals(CommandStatus.REJECTED, ex.execute("core/echo", mapOf(" " to "v")).status)
        assertEquals(CommandStatus.REJECTED, ex.execute("core/echo", (1..33).associate { "k$it" to "v" }).status)
        assertEquals(CommandStatus.FAILED, ex.execute("core/big").status)
    }

    // ---- extension execution through the plugin runtime ----
    @Test fun extensionCommandsGoThroughThePluginRuntimeWithExactActionDetails() = runBlocking {
        val r = CommandRegistry()
        r.registerExtension("dev.example.notes", "add_note", "Add note", "", "files")
        val inv = Invoker(PluginInvocationResult.Completed(PluginActionOutput("Saved", "ok")))
        val res = executor(r, inv).execute("dev.example.notes/add_note", mapOf("text" to "milk"))
        assertEquals(CommandStatus.COMPLETED, res.status); assertEquals("Saved", res.title)
        val (plugin, input, ticket) = inv.calls.single()
        assertEquals("dev.example.notes", plugin); assertEquals("add_note", input.actionId)
        assertEquals("files", input.capability); assertEquals(mapOf("text" to "milk"), input.values); assertNull(ticket)
    }

    @Test fun approvalFlowReturnsAwaitingThenRunsWithTheTicket() = runBlocking {
        val r = CommandRegistry()
        r.registerExtension("dev.example.notes", "add_note", "Add note", "", "files")
        val inv = Invoker(PluginInvocationResult.AwaitingApproval("approval needed"))
        val ex = executor(r, inv)
        assertEquals(CommandStatus.AWAITING_APPROVAL, ex.execute("dev.example.notes/add_note").status)
        assertNotNull(ex.actionFor("dev.example.notes/add_note"))
        inv.next = PluginInvocationResult.Completed(PluginActionOutput("Saved", "ok"))
        assertEquals(CommandStatus.COMPLETED, ex.execute("dev.example.notes/add_note", approvalTicket = "ticket-1").status)
        assertEquals("ticket-1", inv.calls.last().third)
    }

    @Test fun rejectedAndFailedPluginOutcomesAreMappedAndPluginCrashesAreContained() = runBlocking {
        val r = CommandRegistry()
        r.registerExtension("dev.example.notes", "x", "X", "", "files")
        val inv = Invoker(PluginInvocationResult.Rejected("Plugin is disabled"))
        val ex = executor(r, inv)
        assertEquals(CommandStatus.REJECTED, ex.execute("dev.example.notes/x").status)
        inv.next = PluginInvocationResult.Failed("The plugin action failed.")
        assertEquals(CommandStatus.FAILED, ex.execute("dev.example.notes/x").status)
        val crashing = CommandExecutor(r, { _, _, _ -> error("SECRET crash") })
        val res = crashing.execute("dev.example.notes/x")
        assertEquals(CommandStatus.FAILED, res.status); assertFalse(res.error!!.message.contains("SECRET"))
    }

    @Test fun activityLogNeverContainsArgumentsOrOutput() = runBlocking {
        val r = CommandRegistry()
        r.registerCore("echo", "Echo", "", echo)
        val logs = mutableListOf<String>()
        executor(r, Invoker(PluginInvocationResult.Failed("x")), logs).execute("core/echo", mapOf("text" to "private-text"))
        assertEquals(listOf("Command completed: core/echo"), logs)
    }
}
