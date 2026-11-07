package com.oneai.app

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRestorationGateTest {
    @Test fun pluginWorkWaitsUntilRestorationCompletes() = runBlocking {
        val gate = PluginRestorationGate()
        var operationStarted = false
        val operation = async {
            gate.awaitReady()
            operationStarted = true
        }

        assertFalse(gate.isReady)
        assertFalse(operation.isCompleted)
        assertFalse(operationStarted)

        gate.complete()
        operation.await()

        assertTrue(gate.isReady)
        assertTrue(operationStarted)
    }

    @Test fun restorationFailureStillReleasesWaitingPluginWork() = runBlocking {
        val gate = PluginRestorationGate()
        var operationStarted = false
        val operation = async {
            gate.awaitReady()
            operationStarted = true
        }

        try {
            throw IllegalStateException("optional plugin restore failed")
        } catch (_: IllegalStateException) {
            // The application contains and logs restore failures.
        } finally {
            gate.complete()
        }

        operation.await()
        assertTrue(gate.isReady)
        assertTrue(operationStarted)
    }

    @Test fun completingTheGateMoreThanOnceIsSafe() {
        val gate = PluginRestorationGate()
        gate.complete()
        gate.complete()
        assertTrue(gate.isReady)
    }
}
