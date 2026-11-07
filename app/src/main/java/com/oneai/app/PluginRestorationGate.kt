package com.oneai.app

import kotlinx.coroutines.CompletableDeferred

/**
 * Process-scoped barrier for plugin-dependent work during startup restoration.
 * Awaiting this never blocks the main thread; callers should suspend in their own coroutine.
 */
class PluginRestorationGate {
    private val ready = CompletableDeferred<Unit>()

    val isReady: Boolean
        get() = ready.isCompleted

    suspend fun awaitReady() {
        ready.await()
    }

    fun complete() {
        ready.complete(Unit)
    }
}
