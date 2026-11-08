package com.oneai.app.bubble

import com.oneai.app.plugins.CatalogueView
import kotlinx.coroutines.Job

/**
 * Tracks the command runs the Bubble is showing a "Running" panel for (decision D11 / D-T).
 *
 * A run is *running* from [begin] until it either completes or is cancelled. [complete] answers whether the
 * result should still be shown: it is false for a run the user cancelled (the Bubble has already said so) and
 * for an id it does not know, so a late result can never redraw over a cancelled run. [cancel] cancels the
 * attached job; the plugin adapter then cancels the request in the plugin. Pure Kotlin, no Android types.
 */
class BubbleRunState {
    private var counter = 0L
    private val running = HashMap<Long, Job?>()

    /** Starts tracking a new run. Runs are independent: starting one never cancels another. */
    @Synchronized fun begin(): Long {
        counter += 1
        running[counter] = null
        return counter
    }

    /** Hands the run its job. If the run was already cancelled or finished, the job is cancelled instead. */
    fun attach(id: Long, job: Job) {
        if (!register(id, job)) job.cancel()
    }

    /** Cancels a running run. Returns true only the first time, while it was still running. */
    fun cancel(id: Long): Boolean {
        val (wasRunning, job) = take(id)
        job?.cancel()
        return wasRunning
    }

    /** Ends a run. True means it was still running, so its result should be shown. */
    @Synchronized fun complete(id: Long): Boolean {
        val wasRunning = running.containsKey(id)
        running.remove(id)
        return wasRunning
    }

    @Synchronized fun isRunning(id: Long): Boolean = running.containsKey(id)

    @Synchronized private fun register(id: Long, job: Job): Boolean {
        if (!running.containsKey(id)) return false
        running[id] = job
        return true
    }

    @Synchronized private fun take(id: Long): Pair<Boolean, Job?> {
        val wasRunning = running.containsKey(id)
        val job = running.remove(id)
        return Pair(wasRunning, job)
    }
}

/** Text for the host-drawn "Running" panel. It is built from the command title only, never from plugin progress. */
object BubbleRunning {
    const val MAX_TITLE = 60
    const val HIDDEN_NOTE = "Hiding this panel does not stop the command; its result will appear when it finishes."
    const val CANCELLED_TITLE = "Cancelled"
    const val CANCELLED_NOTE = "The command was cancelled. If you had approved it, approve again to run it again."

    /** "Running <title>..." with the title cleaned and bounded; [fallback] is used when the title is blank. */
    fun heading(title: String?, fallback: String): String {
        val cleaned = CatalogueView.clean(title.orEmpty(), MAX_TITLE)
        val shown = cleaned.ifBlank { CatalogueView.clean(fallback, MAX_TITLE) }.ifBlank { "command" }
        return "Running $shown..."
    }
}
