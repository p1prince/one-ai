package com.oneai.app.plugins

import com.oneai.app.bubble.BubbleAction
import com.oneai.app.bubble.BubbleApproval
import com.oneai.app.bubble.BubbleMessage
import com.oneai.app.bubble.BubbleRunning
import com.oneai.app.bubble.BubbleView
import com.oneai.app.core.PluginActionExecutor
import com.oneai.app.core.PluginInvocationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Loading state of the plugin's declared screen. Failure text is host-written, never plugin text. */
sealed interface PluginViewState {
    data object Loading : PluginViewState
    data class Failed(val message: String) : PluginViewState
    data class Loaded(val view: BubbleView) : PluginViewState
}

/** Everything the in-app plugin screen draws. [approval] is set only while a call awaits approval. */
data class PluginScreenUi(
    val view: PluginViewState,
    val call: PluginCallState,
    val approval: BubbleApproval?,
    val notice: BubbleMessage?
)

/**
 * Runs one plugin's declared screen inside the app (decision D12, slice C2). It reuses the Bubble's provider
 * view and the same executor, approval ticket and result vocabulary, and drives [PluginCallFlow] for the call
 * phases. It is pure Kotlin: Android pieces (the Bubble host, restoration gate) arrive as lambdas.
 *
 * It depends on the plugin being Enabled only (the caller decides that before opening the screen) and never
 * reads Bubble access. It runs only calls that belong to its own plugin, and only plain plugin calls; a
 * host-gated Bubble action is not available here. Closing the screen cancels a running call.
 */
class InAppPluginController(
    private val pluginId: String,
    private val scope: CoroutineScope,
    private val executor: PluginActionExecutor,
    private val loadView: suspend () -> BubbleView,
    /** Waits for plugin restoration and prepares the adapter, as the Bubble does before its first call. */
    private val prepare: suspend () -> Result<Unit>
) {
    private data class Pending(val runId: Long, val call: BubbleAction.PluginCall)

    private val flow = PluginCallFlow()
    private val lock = Any()
    private var viewState: PluginViewState = PluginViewState.Loading
    private var notice: BubbleMessage? = null
    private var pending: Pending? = null
    private var job: Job? = null

    private val state = MutableStateFlow(snapshot())
    val ui: StateFlow<PluginScreenUi> get() = state

    /** Loads (or reloads) the plugin's screen. A failure shows a fixed message, not the exception text. */
    fun load() {
        synchronized(lock) { viewState = PluginViewState.Loading }
        publish()
        scope.launch {
            val next: PluginViewState = try {
                PluginViewState.Loaded(loadView())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                PluginViewState.Failed(LOAD_FAILED)
            }
            synchronized(lock) { viewState = next }
            publish()
        }
    }

    /** A button on the plugin's screen produced [action]. */
    fun press(action: BubbleAction?) {
        when (action) {
            null -> return
            is BubbleAction.Message -> setNotice(BubbleMessage(action.title, action.content))
            is BubbleAction.GatedHostAction -> setNotice(BubbleMessage("Not available here", GATED_NOTE))
            is BubbleAction.PluginCall -> startCall(action)
        }
    }

    private fun startCall(call: BubbleAction.PluginCall) {
        if (call.pluginId != pluginId) {
            setNotice(BubbleMessage("Blocked", "This screen can only run commands of its own plugin."))
            return
        }
        val runId = flow.request(call.runTitle, call.input.actionId, needsApproval = false)
        if (runId == null) {
            setNotice(BubbleMessage("Please wait", "Another command is still waiting or running."))
            return
        }
        synchronized(lock) { notice = null; pending = Pending(runId, call) }
        publish()
        launchInvoke(runId, call, ticket = null)
    }

    private fun launchInvoke(runId: Long, call: BubbleAction.PluginCall, ticket: String?) {
        val started = scope.launch {
            try {
                if (ticket == null) {
                    val ready = prepare()
                    if (ready.isFailure) {
                        flow.fail(runId, call.outcomes.unavailableTitle, ready.exceptionOrNull()?.message ?: NOT_STARTED)
                    } else {
                        val result = executor.invoke(call.pluginId, call.input)
                        if (result is PluginInvocationResult.AwaitingApproval) {
                            flow.needApproval(runId, call.approval.review)
                        } else {
                            flow.finish(runId, result, call.outcomes)
                        }
                    }
                } else {
                    flow.finish(runId, executor.invoke(call.pluginId, call.input, ticket), call.outcomes)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                flow.fail(runId, "Command failed", FAILED_NOTE)
            }
            publish()
        }
        synchronized(lock) { job = started }
    }

    /** The user pressed the approval button for the exact action shown. */
    fun approve() {
        val p = synchronized(lock) { pending } ?: return
        if (!flow.approve(p.runId)) return
        val ticket = executor.approveSensitiveAction(p.call.pluginId, p.call.input).getOrNull()
        if (ticket == null) {
            flow.fail(p.runId, p.call.outcomes.blockedTitle, UNVERIFIED_NOTE)
            publish()
            return
        }
        publish()
        launchInvoke(p.runId, p.call, ticket)
    }

    /** The user rejected the approval request. The one-use ticket is never issued. */
    fun reject() {
        val p = synchronized(lock) { pending } ?: return
        if (flow.reject(p.runId)) {
            synchronized(lock) { pending = null }
            publish()
        }
    }

    /** Cancel pressed while a call is running (or waiting for approval). */
    fun cancel() {
        if (flow.cancel()) {
            synchronized(lock) { job?.cancel(); job = null }
            publish()
        }
    }

    /** Clears a shown result, Cancelled note or notice. */
    fun dismiss() {
        flow.dismiss()
        synchronized(lock) { notice = null }
        publish()
    }

    /** The screen is closing: stop any running call. */
    fun close() = cancel()

    private fun setNotice(message: BubbleMessage) {
        synchronized(lock) { notice = message }
        publish()
    }

    private fun snapshot(): PluginScreenUi = synchronized(lock) {
        val call = flow.state()
        PluginScreenUi(
            view = viewState,
            call = call,
            approval = if (call is PluginCallState.AwaitingApproval) pending?.call?.approval else null,
            notice = notice
        )
    }

    private fun publish() { state.value = snapshot() }

    companion object {
        const val LOAD_FAILED = "This plugin's screen could not be loaded."
        const val GATED_NOTE = "This action is only available from the Floating Bubble."
        const val NOT_STARTED = "Could not start this plugin."
        const val FAILED_NOTE = "The command could not be completed."
        const val UNVERIFIED_NOTE = "ONE AI could not verify approval for this action."
        val CANCELLED_NOTE = BubbleRunning.CANCELLED_NOTE
    }
}
