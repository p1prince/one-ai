package com.oneai.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Background-operation infrastructure. Tasks are isolated: a failing task is reported and
 * never cancels its siblings, and [shutdown] cancels everything. Cancellation is not an error.
 */
class BackgroundTasks(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val report: (taskName: String, error: CoreError) -> Unit = { _, _ -> }
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)

    val isActive: Boolean get() = job.isActive

    /** Returns null when already shut down. */
    fun launch(name: String, block: suspend () -> Unit): Job? {
        if (!job.isActive) return null
        return scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                report(name, CoreErrors.from(e))
            }
        }
    }

    fun shutdown() { job.cancel() }
}
