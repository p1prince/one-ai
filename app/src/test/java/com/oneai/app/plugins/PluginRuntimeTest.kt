package com.oneai.app.plugins

import com.oneai.app.core.PluginRecord
import com.oneai.app.security.ActionAuditLogger
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

    private fun plugin(enabled: Boolean = true, capabilities: List<String> = manifest.capabilities) =
        PluginRecord(manifest.id, manifest.name, manifest.version, manifest.description,
            capabilities, enabled, manifestVersion = manifest.manifestVersion)

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

        val ticket = gateway.confirmUserApproval(
            com.oneai.app.security.ActionRequest(manifest.id, input.actionId, input.capability, input.description)
        )
        assertTrue(runtime.invoke(manifest.id, input, ticket) is PluginInvocationResult.Completed)
        assertEquals(1, adapter.calls)
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

    @Test fun malformedOversizedInputIsRejectedBeforeAdapterRuns() = runBlocking {
        val installed = plugin()
        val adapter = TestAdapter(manifest) { PluginActionOutput("x", "y") }
        val runtime = PluginRuntime({ installed },
            PluginActionGateway({ installed }, ActionAuditLogger {}))
        assertTrue(runtime.register(adapter).isSuccess)
        val oversized = PluginActionInput("prepare", "commands", "Prepare",
            mapOf("payload" to "x".repeat(4_001)))
        assertTrue(runtime.invoke(manifest.id, oversized) is PluginInvocationResult.Rejected)
        assertEquals(0, adapter.calls)
    }
}
