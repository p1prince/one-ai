package com.oneai.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restore only previously enabled, still-trusted external plugins off the UI thread.
 * AppServices.restorePlugins contains failures, so an optional extension can never prevent
 * the core shell from starting.
 */
class OneAiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            AppServices.get(this@OneAiApplication).restorePlugins()
        }
    }
}
