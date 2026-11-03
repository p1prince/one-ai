package com.oneai.app.plugins

import com.oneai.app.core.PluginRecord
import com.oneai.app.security.ActionAuditLogger
import com.oneai.app.security.ActionRequest
import com.oneai.app.security.PluginActionGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PluginRuntimeTest {
    private val manifest = PluginManifest(
        id = "dev.example.tools",
        name = "Example Tools",
        version = "1.0.0",
        description = "Test adapter",
        capabilities = listOf("commands", "device_actions")
    )

    private fun plugin(
        enabled: Boolean = true,
        capabilities: List<String> = manifest.capabilities,
        name: String = manifest.name,
        version: String = manifest.version,
        description: String = manifest.description,
        dependencies: List<String> = manifest.dependencies
    ) = PluginRecord(manifest.id, name, version, description,
        capabilities, enabled, manifestVersion = manifest.manifestVersion, dependencies = dependencies)

    private class TestAdapter(
        override val manifest: PluginManifest,
        private val action: suspend (PluginActionInput) -> PluginActionOutput
    ) : PluginAdapter {
        var calls = 0
        override suspend fun execute(input: PluginActionInput): PluginActionOutput {
            calls++
            return action(input)
        }
    }

    @Test fun registrationRequiresInstalledMatchingManifest() {
        val adapter = TestAdapter(manifest) { PluginActionOutput("ok", "done") }
        val missing = PluginRuntime({ null }, PluginActionGateway({ null }, ActionAuditLogger {}))
        assertTrue(missing.register(adapter).isFailure)

        val mismatch = PluginRuntime(
            { plugin(capabilities = listOf("commands")) },
            PluginActionGateway({ plugin(capabilities = listOf("commands")) }, ActionAuditLogger {})
        )
        assertTrue(mismatch.register(adapter).isFailure)
    }

    @Test fun registrationRejectsAnyInstalledDeclarationMismatch() {
        val mismatches = listOf(
            plugin(name = "Different name"),
            plugin(version = "2.0.0"),
            plugin(description = "Different description"),
            plugin(dependencies = listOf("dev.example.base"))
        )
        mismatches.forEach { installed ->
            val runtime = PluginRuntime({ installed },
                PluginActionGateway({ installed }, ActionAuditLogger {}))
            assertTrue(runtime.register(TestAdapter(manifest) {
                PluginActionOutput("ok", "done")
            }).isFailure)
        }
    }

    @Test fun lowRiskActionExecutesOnlyThroughRegisteredEnabledAdapter() = runBlocking {
        val installed = plugin()
        val adapter = TestAdapter(manifest) { PluginActionOutput("Command", "Prepared") }
        val gateway = PluginActionGateway({ installed }, ActionAuditLogger {})
        val runtime = PluginRuntime({ installed }, gateway)
        assertTrue(runtime.register(adapter).isSuccess)

        val result = runtime.invoke(manifest.id,
            PluginActionInput("prepare", "commands", "Prepare a command"))
        assertEquals(PluginInvocationResult.Completed(PluginActionOutput("Command", "Prepared")), result)
        assertEquals(1, adapter.calls)
    }

    @Test fun sensitiveActionCannotRunUntilOneTimeApprovalIsProvided() = runBlocking {
        val installed = plugin()
        val adapter = TestAdapter(manifest) { PluginActionOutput("Device", "Changed") }
        val gateway = PluginActionGateway({ installed }, ActionAuditLogger {})
        val runtime = PluginRuntime({ installed }, gateway)
        assertTrue(runtime.register(adapter).isSuccess)
        val input = PluginActionInput("wifi.toggle", "device_actions", "Turn Wi-Fi off")

        assertTrue(runtime.invoke(manifest.id, input) is PluginInvocationResult.AwaitingApproval)
        assertEquals(0, adapter.calls)

        val ticket = runtime.approveSensitiveAction(manifest.id, input).getOrThrow()
        assertTrue(runtime.invoke(manifest.id, input, ticket) is PluginInvocationResult.Completed)
        assertEquals(1, adapter.calls)
    }

    @Test fun approvalTicketCannotBeIssuedWithoutEnabledRegisteredAdapter() {
        val installed = plugin()
        val runtime = PluginRuntime({ installed },
            PluginActionGateway({ installed }, ActionAuditLogger {}))
        val input = PluginActionInput("wifi.toggle", "device_actions", "Turn Wi-Fi off")
        assertTrue(runtime.approveSensitiveAction(manifest.id, input).isFailure)

        val disabled = plugin(enabled = false)
        val disabledRuntime = PluginRuntime({ disabled },
            PluginActionGateway({ disabled }, ActionAuditLogger {}))
        assertTrue(disabledRuntime.register(TestAdapter(manifest) {
            PluginActionOutput("Device", "Changed")
        }).isSuccess)
        assertTrue(disabledRuntime.approveSensitiveAction(manifest.id, input).isFailure)
    }

    @Test fun disabledPluginAndUnknownAdapterAreRejected() = runBlocking {
        val disabled = plugin(enabled = false)
        val runtime = PluginRuntime({ disabled },
            PluginActionGateway({ disabled }, ActionAuditLogger {}))
        val adapter = TestAdapter(manifest) { PluginActionOutput("x", "y") }
        assertTrue(runtime.register(adapter).isSuccess)
        assertTrue(runtime.invoke(manifest.id,
            PluginActionInput("prepare", "commands", "Prepare")).let { it is PluginInvocationResult.Rejected })
        assertTrue(runtime.invoke("dev.unknown.plugin",
            PluginActionInput("prepare", "commands", "Prepare")).let { it is PluginInvocationResult.Rejected })
        assertEquals(0, adapter.calls)
    }

    @Test fun malformedAndOversizedInputIsRejectedBeforeAdapterRuns() = runBlocking {
        val installed = plugin()
        val adapter = TestAdapter(manifest) { PluginActionOutput("x", "y") }
        val runtime = PluginRuntime({ installed },
            PluginActionGateway({ installed }, ActionAuditLogger {}))
        assertTrue(runtime.register(adapter).isSuccess)
        val invalid = listOf(
            PluginActionInput(" ", "commands", "Prepare"),
            PluginActionInput("prepare", "unknown", "Prepare"),
            PluginActionInput("prepare", "commands", "x".repeat(501)),
            PluginActionInput("prepare", "commands", "Prepare", mapOf("payload" to "x".repeat(4_001))),
            PluginActionInput("prepare", "commands", "Prepare", (0..32).associate { "k$it" to "v" })
        )
        invalid.forEach { input ->
            assertTrue(runtime.invoke(manifest.id, input) is PluginInvocationResult.Rejected)
        }
        assertEquals(0, adapter.calls)
    }

    @Test fun oversizedAdapterOutputIsNotReturnedToCoreUi() = runBlocking {
        val installed = plugin()
        val adapter = TestAdapter(manifest) { PluginActionOutput("Result", "x".repeat(20_001)) }
        val runtime = PluginRuntime({ installed },
            PluginActionGateway({ installed }, ActionAuditLogger {}))
        assertTrue(runtime.register(adapter).isSuccess)
        assertTrue(runtime.invoke(manifest.id,
            PluginActionInput("prepare", "commands", "Prepare")) is PluginInvocationResult.Failed)
    }

    @Test fun pluginRemovedBeforeInvocationCannotExecute() = runBlocking {
        var installed: PluginRecord? = plugin()
        val gateway = PluginActionGateway({ installed }, ActionAuditLogger {})
        val runtime = PluginRuntime({ installed }, gateway)
        val adapter = TestAdapter(manifest) { PluginActionOutput("x", "y") }
        assertTrue(runtime.register(adapter).isSuccess)
        installed = null
        assertTrue(runtime.invoke(manifest.id,
            PluginActionInput("prepare", "commands", "Prepare")) is PluginInvocationResult.Rejected)
        assertEquals(0, adapter.calls)
    }
}
