package com.oneai.app.plugins

import com.oneai.app.bubble.BubbleOutcomes
import com.oneai.app.bubble.BubbleRunning
import com.oneai.app.core.PluginInvocationResult

/** How a finished plugin call ended, for the in-app screen to style the result. */
enum class PluginCallOutcome { SUCCESS, BLOCKED, FAILED }

/** What the in-app plugin screen shows for one plugin call (decision D12, slice C1). */
sealed interface PluginCallState {
    data object Idle : PluginCallState

    /** Waiting for the user to approve the exact action; [review] is bounded, cleaned text. */
    data class AwaitingApproval(val heading: String, val review: String?) : PluginCallState

    /** [heading] is "Running <command title>..." built from the command title only. */
    data class Running(val heading: String) : PluginCallState

    data class Done(val title: String, val content: String, val outcome: PluginCallOutcome) : PluginCallState

    data object Cancelled : PluginCallState
}

/**
 * The state machine for one plugin call shown in the app: idle, awaiting approval, running, done, cancelled.
 * Pure Kotlin with no Android types. It depends only on whether a plugin is Enabled, which the caller decides
 * before it asks; it never reads or changes Bubble access. One call at a time: [request] is refused while a
 * call is awaiting approval or running. A result that arrives after Cancel, or for an older run, is dropped.
 * Plugin text is cleaned and bounded here, and the screen must still show it as plugin-supplied text.
 */
class PluginCallFlow {
    private var counter = 0L
    private var current = 0L
    private var approvedRun = 0L
    private var state: PluginCallState = PluginCallState.Idle

    @Synchronized fun state(): PluginCallState = state

    /**
     * Starts a call. Returns its run id, or null when another call is still awaiting approval or running.
     * With [needsApproval] the state becomes AwaitingApproval, otherwise Running straight away.
     */
    @Synchronized fun request(title: String?, fallback: String, needsApproval: Boolean, review: String? = null): Long? {
        if (state is PluginCallState.AwaitingApproval || state is PluginCallState.Running) return null
        counter += 1
        current = counter
        val heading = BubbleRunning.heading(title, fallback)
        state = if (needsApproval) {
            PluginCallState.AwaitingApproval(heading, review?.let { cleanText(it, MAX_REVIEW) })
        } else {
            PluginCallState.Running(heading)
        }
        return current
    }

    /**
     * The executor answered that run [runId] needs the user's approval, so a running call moves to awaiting
     * approval (the Bubble's real flow: invoke first, then ask). True only for the current running call, and
     * only until that run has been approved once: an approved run can never go back to asking.
     */
    @Synchronized fun needApproval(runId: Long, review: String? = null): Boolean {
        val running = state as? PluginCallState.Running ?: return false
        if (runId != current || runId == approvedRun) return false
        state = PluginCallState.AwaitingApproval(running.heading, review?.let { cleanText(it, MAX_REVIEW) })
        return true
    }

    /** The user approved run [runId]. True only if it was awaiting approval; it is then Running. */
    @Synchronized fun approve(runId: Long): Boolean {
        val waiting = state as? PluginCallState.AwaitingApproval ?: return false
        if (runId != current) return false
        approvedRun = runId
        state = PluginCallState.Running(waiting.heading)
        return true
    }

    /** The user rejected run [runId] at the approval step. The call ends and the screen returns to Idle. */
    @Synchronized fun reject(runId: Long): Boolean {
        if (state !is PluginCallState.AwaitingApproval || runId != current) return false
        state = PluginCallState.Idle
        return true
    }

    /**
     * Cancel pressed. A running call becomes Cancelled and its late result is dropped; a call still awaiting
     * approval simply returns to Idle. True when something changed.
     */
    @Synchronized fun cancel(): Boolean = when (state) {
        is PluginCallState.Running -> { state = PluginCallState.Cancelled; true }
        is PluginCallState.AwaitingApproval -> { state = PluginCallState.Idle; true }
        else -> false
    }

    /**
     * Records the result of run [runId]. It is shown only if that run is still the running one, so a result
     * after Cancel or for an older run changes nothing and this returns false. When [outcomes] chooses to show
     * nothing for a success, the screen returns to Idle.
     */
    @Synchronized fun finish(runId: Long, result: PluginInvocationResult, outcomes: BubbleOutcomes): Boolean {
        if (state !is PluginCallState.Running || runId != current) return false
        val message = outcomes.present(result)
        state = if (message == null) {
            PluginCallState.Idle
        } else {
            PluginCallState.Done(
                title = cleanText(message.title, MAX_TITLE).ifBlank { "Result" },
                content = cleanText(message.content, MAX_CONTENT),
                outcome = outcomeOf(result)
            )
        }
        return true
    }

    /** A call failed without a result (for example an unexpected error). Same guard as [finish]. */
    @Synchronized fun fail(runId: Long, title: String, message: String): Boolean {
        if (state !is PluginCallState.Running || runId != current) return false
        state = PluginCallState.Done(cleanText(title, MAX_TITLE).ifBlank { "Failed" }, cleanText(message, MAX_CONTENT), PluginCallOutcome.FAILED)
        return true
    }

    /** Clears a shown result or a Cancelled note. Does nothing while a call is awaiting approval or running. */
    @Synchronized fun dismiss(): Boolean {
        if (state !is PluginCallState.Done && state !is PluginCallState.Cancelled) return false
        state = PluginCallState.Idle
        return true
    }

    private fun outcomeOf(result: PluginInvocationResult): PluginCallOutcome = when (result) {
        is PluginInvocationResult.Completed -> PluginCallOutcome.SUCCESS
        is PluginInvocationResult.Failed -> PluginCallOutcome.FAILED
        is PluginInvocationResult.Rejected, is PluginInvocationResult.AwaitingApproval -> PluginCallOutcome.BLOCKED
    }

    /** Cleans each line (hidden and control characters removed) but keeps line breaks, then bounds the total. */
    private fun cleanText(text: String, max: Int): String =
        text.split('\n').joinToString("\n") { CatalogueView.clean(it, max) }.trim().take(max)

    companion object {
        const val MAX_TITLE = 60
        const val MAX_REVIEW = 500
        const val MAX_CONTENT = 4000
    }
}
