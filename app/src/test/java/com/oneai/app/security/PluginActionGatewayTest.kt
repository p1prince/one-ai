package com.oneai.app.security

import com.oneai.app.core.PluginRecord
import org.junit.Assert.*
import org.junit.Test

class PluginActionGatewayTest {
    private val sensitiveRequest = ActionRequest(
        "dev.example.device", "wifi.toggle", "device_actions", "Turn Wi-Fi off"
    )
    private fun plugin(enabled: Boolean = true) = PluginRecord(
        id = "dev.example.device",
        name = "Device plugin",
        version = "1.0.0",
        description = "Test",
        capabilities = listOf("device_actions"),
        enabled = enabled
    )

    @Test fun sensitiveActionWaitsForApprovalThenPermitsExactlyOnce() {
        val events = mutableListOf<String>()
        val gateway = PluginActionGateway({ plugin() }, ActionAuditLogger { event -> events.add(event); Unit })
        assertTrue(gateway.check(sensitiveRequest) is ActionGateResult.AwaitingUserApproval)
        val ticket = gateway.confirmUserApproval(sensitiveRequest)
        assertTrue(gateway.check(sensitiveRequest, ticket) is ActionGateResult.Permitted)
        assertTrue(gateway.check(sensitiveRequest, ticket) is ActionGateResult.Rejected)
        assertTrue(events.any { it.contains("User approved") })
        assertTrue(events.any { it.contains("Sensitive action approved") })
    }

    @Test fun disabledOrMissingPluginCannotObtainApproval() {
        val disabled = PluginActionGateway({ plugin(false) }, ActionAuditLogger {})
        assertTrue(disabled.check(sensitiveRequest) is ActionGateResult.Rejected)
        assertThrows(IllegalStateException::class.java) {
            disabled.confirmUserApproval(sensitiveRequest)
        }
        val missing = PluginActionGateway({ null }, ActionAuditLogger {})
        assertTrue(missing.check(sensitiveRequest) is ActionGateResult.Rejected)
    }

    @Test fun undeclaredCapabilityIsRejectedAndAudited() {
        val events = mutableListOf<String>()
        val limited = plugin().copy(capabilities = listOf("commands"))
        val gateway = PluginActionGateway({ limited }, ActionAuditLogger { event -> events.add(event); Unit })
        assertTrue(gateway.check(sensitiveRequest) is ActionGateResult.Rejected)
        assertTrue(events.any { it.contains("did not declare") })
    }

    @Test fun lowRiskActionDoesNotNeedApprovalTicket() {
        val lowRisk = plugin().copy(capabilities = listOf("internet"))
        val request = ActionRequest(lowRisk.id, "research.search", "internet", "Search the web")
        val gateway = PluginActionGateway({ lowRisk }, ActionAuditLogger {})
        assertTrue(gateway.check(request) is ActionGateResult.Permitted)
    }
}
