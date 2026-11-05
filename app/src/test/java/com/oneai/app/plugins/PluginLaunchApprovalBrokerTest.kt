package com.oneai.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginLaunchApprovalBrokerTest {
    @Test fun identityRequiresTheTrustedPackageAndUidAndRejectsMutablePendingIntents() {
        assertTrue(PluginLaunchIdentityPolicy.accepts("dev.example.notes", 12001, "dev.example.notes", 12001, true))
        assertTrue("pre-Android-12 mutability cannot be queried", PluginLaunchIdentityPolicy.accepts("dev.example.notes", 12001, "dev.example.notes", 12001, null))
        assertFalse(PluginLaunchIdentityPolicy.accepts("dev.example.notes", 12001, "dev.other.app", 12001, true))
        assertFalse(PluginLaunchIdentityPolicy.accepts("dev.example.notes", 12001, "dev.example.notes", 12002, true))
        assertFalse(PluginLaunchIdentityPolicy.accepts("dev.example.notes", 12001, "dev.example.notes", 12001, false))
    }

    @Test fun onlyOneRequestCanWaitAndTheHostMustApproveTheMatchingRequest() {
        val broker = PluginLaunchApprovalBroker()
        val payload = Any()
        assertTrue(broker.offer(PluginLaunchRequest("request-1", "dev.example.notes", " Open Notes ", payload)))
        assertEquals("Open Notes", broker.current()?.label)
        assertFalse(broker.offer(PluginLaunchRequest("request-2", "dev.example.other", "Open Other", Any())))
        assertNull(broker.approve("wrong-request") { true })
        assertSame(payload, broker.approve("request-1") { it == "dev.example.notes" })
        assertNull(broker.current())
    }

    @Test fun approvalRechecksTrustAndConsumesTheRequestEvenWhenTrustWasRevoked() {
        val broker = PluginLaunchApprovalBroker()
        assertTrue(broker.offer(PluginLaunchRequest("request-1", "dev.example.notes", "Open Notes", "pending-intent")))
        assertNull(broker.approve("request-1") { false })
        assertNull(broker.current())
    }

    @Test fun rejectionAndPluginRemovalClearOnlyTheMatchingRequest() {
        val broker = PluginLaunchApprovalBroker()
        broker.offer(PluginLaunchRequest("request-1", "dev.example.notes", "Open Notes", Any()))
        assertFalse(broker.clearPlugin("dev.example.other"))
        assertTrue(broker.clearPlugin("dev.example.notes"))
        assertNull(broker.current())
        broker.offer(PluginLaunchRequest("request-2", "dev.example.notes", "Open Notes", Any()))
        assertTrue(broker.reject("request-2"))
        assertFalse(broker.reject("request-2"))
    }

    @Test fun malformedIdentityAndEmptyLabelsAreRefusedAndLabelsAreBounded() {
        val broker = PluginLaunchApprovalBroker()
        assertFalse(broker.offer(PluginLaunchRequest("request", "bad package", "Open", Any())))
        assertFalse(broker.offer(PluginLaunchRequest("request", "dev.example.notes", "   ", Any())))
        assertTrue(broker.offer(PluginLaunchRequest("request", "dev.example.notes", "x".repeat(200), Any())))
        assertEquals(120, broker.current()?.label?.length)
    }

    @Test fun observerReceivesExistingAndNewRequestsAndClearEvents() {
        val broker = PluginLaunchApprovalBroker()
        val events = mutableListOf<String?>()
        broker.offer(PluginLaunchRequest("request-1", "dev.example.notes", "Open Notes", Any()))
        broker.setObserver { events += it?.requestId }
        assertEquals(listOf("request-1"), events)
        assertTrue(broker.reject("request-1"))
        assertEquals(listOf("request-1", null), events)
        assertTrue(broker.offer(PluginLaunchRequest("request-2", "dev.example.notes", "Open Notes", Any())))
        assertEquals(listOf("request-1", null, "request-2"), events)
        assertTrue(broker.approve("request-2") { true } != null)
        assertEquals(listOf("request-1", null, "request-2", null), events)
    }
}
