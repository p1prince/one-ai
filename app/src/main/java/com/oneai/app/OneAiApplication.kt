package com.oneai.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restore only previously enabled, still-trusted external plugins off the UI thread.
 * The readiness gate is released even on failure; optional extensions can never prevent
 * the core shell from starting or leave plugin-dependent callers suspended indefinitely.
 */
class OneAiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val services = AppServices.get(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            services.restorePlugins()
        }
    }
}
