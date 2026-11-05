package com.oneai.app.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginActionContractsTest {
    private class StandaloneExecutor : PluginActionExecutor {
        override suspend fun invoke(
            pluginId: String,
            input: PluginActionInput,
            approvalTicket: String?
        ): PluginInvocationResult =
            if (approvalTicket == "approved-once") {
                PluginInvocationResult.Completed(PluginActionOutput("Done", "Core contract result"))
            } else {
                PluginInvocationResult.AwaitingApproval("User approval is required")
            }

        override fun approveSensitiveAction(pluginId: String, input: PluginActionInput): Result<String> =
            Result.success("approved-once")
    }

    @Test fun coreContractCanBeImplementedWithoutPluginRuntimeOrSecurityTypes() = runBlocking {
        val executor: PluginActionExecutor = StandaloneExecutor()
        val input = PluginActionInput("open", "app_launch", "Open the selected app")
        assertTrue(executor.invoke("example.plugin", input) is PluginInvocationResult.AwaitingApproval)
        val ticket = executor.approveSensitiveAction("example.plugin", input).getOrThrow()
        assertEquals(
            PluginInvocationResult.Completed(PluginActionOutput("Done", "Core contract result")),
            executor.invoke("example.plugin", input, ticket)
        )
    }
}
