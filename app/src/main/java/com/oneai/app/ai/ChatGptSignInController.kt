package com.oneai.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What Settings shows while a ChatGPT sign-in runs or after it ends. [message] never contains a token. */
data class ChatGptSignInState(val inProgress: Boolean = false, val message: String? = null)

/**
 * Runs the ChatGPT sign-in in a process-level scope, so it survives the Settings screen being recomposed or the activity
 * being recreated while the user is in the browser. One sign-in at a time; it can be cancelled; disconnecting cancels any
 * attempt in progress and removes the stored session.
 */
class ChatGptSignInController(
    private val account: ChatGptAccount,
    private val scope: CoroutineScope,
    private val receiverFactory: () -> LoopbackReceiver = { LoopbackReceiver.bind(ChatGptAccount.CALLBACK_PORTS) }
) {
    private val mutableState = MutableStateFlow(ChatGptSignInState())
    val state: StateFlow<ChatGptSignInState> = mutableState
    private var job: Job? = null

    @Synchronized
    fun start(openBrowser: (String) -> Unit) {
        if (job?.isActive == true) return
        mutableState.value = ChatGptSignInState(true, "Finish signing in in your browser, then return to ONE AI.")
        job = scope.launch {
            try {
                account.signIn(openBrowser, receiverFactory)
                mutableState.value = ChatGptSignInState(false, "ChatGPT account connected.")
            } catch (cancelled: CancellationException) {
                mutableState.value = ChatGptSignInState(false, "Sign-in cancelled.")
                throw cancelled
            } catch (error: AiProviderException) {
                mutableState.value = ChatGptSignInState(false, error.message ?: "ChatGPT sign-in failed.")
            } catch (error: Exception) {
                // Unknown failure: say so without echoing anything that could name a token or a code.
                mutableState.value = ChatGptSignInState(false, "ChatGPT sign-in failed. Please try again.")
            }
        }
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
    }

    @Synchronized
    fun disconnect() {
        job?.cancel()
        job = null
        account.disconnect()
        mutableState.value = ChatGptSignInState(false, "ChatGPT account disconnected.")
    }

    fun clearMessage() {
        if (!mutableState.value.inProgress) mutableState.value = ChatGptSignInState()
    }
}
