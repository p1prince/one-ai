package com.oneai.app.security

import org.junit.Assert.*
import org.junit.Test

class ActionSecurityTest {
    private fun request(capability: String = "device_actions", action: String = "wifi.toggle") =
        ActionRequest("dev.example.device", action, capability, "Turn Wi-Fi off")

    @Test fun disabledPluginAndUnknownCapabilityAreBlocked() {
        assertFalse(CapabilityPolicy.evaluate(request(), false, setOf("device_actions")).allowed)
        assertFalse(CapabilityPolicy.evaluate(request("run_shell"), true, setOf("run_shell")).allowed)
    }

    @Test fun pluginCannotUseAnUndeclaredCapability() {
        val result = CapabilityPolicy.evaluate(request(), true, setOf("commands"))
        assertFalse(result.allowed)
        assertEquals(ActionRisk.BLOCKED, result.risk)
    }

    @Test fun sensitiveCapabilitiesRequireApproval() {
        val result = CapabilityPolicy.evaluate(request(), true, setOf("device_actions"))
        assertTrue(result.allowed)
        assertTrue(result.requiresUserApproval)
        assertEquals(ActionRisk.SENSITIVE, result.risk)
        val network = CapabilityPolicy.evaluate(request("internet"), true, setOf("internet"))
        assertTrue(network.requiresUserApproval)
        assertEquals(ActionRisk.SENSITIVE, network.risk)
    }

    @Test fun lowRiskCapabilityDoesNotRequireAdditionalApproval() {
        val result = CapabilityPolicy.evaluate(request("commands"), true, setOf("commands"))
        assertTrue(result.allowed)
        assertFalse(result.requiresUserApproval)
        assertEquals(ActionRisk.LOW, result.risk)
    }

    @Test fun approvalIsBoundToExactActionAndConsumedOnce() {
        val gate = OneTimeApprovalGate(nowMillis = { 1_000L })
        val original = request()
        val token = gate.issueAfterUserApproval(original)
        assertFalse(gate.consume(token, request(action = "bluetooth.toggle")))
        val secondToken = gate.issueAfterUserApproval(original)
        assertFalse(gate.consume(secondToken, original.copy(description = "Send all device data")))
        assertFalse(gate.consume(token, original))
    }

    @Test fun approvalExpiresAndCanBeRevoked() {
        var now = 10_000L
        val gate = OneTimeApprovalGate(nowMillis = { now }, lifetimeMillis = 500L)
        val expired = gate.issueAfterUserApproval(request())
        now += 501L
        assertFalse(gate.consume(expired, request()))

        now = 20_000L
        val revoked = gate.issueAfterUserApproval(request())
        gate.revokeAll()
        assertFalse(gate.consume(revoked, request()))
    }

    @Test fun permissionGuidanceDoesNotPretendToGrantPermission() {
        assertTrue(CapabilityPolicy.androidPermissionGuidance("screen_capture").contains("MediaProjection"))
        assertTrue(CapabilityPolicy.androidPermissionGuidance("accessibility").contains("user"))
    }
}
