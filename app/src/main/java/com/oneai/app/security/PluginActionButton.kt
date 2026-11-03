package com.oneai.app.security

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.oneai.app.plugins.PluginActionInput
import com.oneai.app.plugins.PluginInvocationResult
import com.oneai.app.plugins.PluginRuntime
import kotlinx.coroutines.launch

/**
 * Reusable user-facing entry point for a trusted plugin action.
 *
 * Sensitive actions pause for ONE AI's exact-action approval dialog. Microphone
 * actions additionally request Android RECORD_AUDIO permission just in time.
 * Other platform capabilities (MediaProjection, Accessibility, file pickers,
 * and settings changes) must use their dedicated Android consent flows.
 */
@Composable
fun PluginActionButton(
    pluginId: String,
    input: PluginActionInput,
    runtime: PluginRuntime,
    label: String,
    modifier: Modifier = Modifier,
    onResult: (PluginInvocationResult) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var awaitingApproval by remember { mutableStateOf(false) }
    var waitingForMicrophonePermission by remember { mutableStateOf(false) }
    var pendingApprovalTicket by remember { mutableStateOf<String?>(null) }

    val execute: (String?) -> Unit = { ticket ->
        scope.launch {
            working = true
            awaitingApproval = false
            val result = runCatching { runtime.invoke(pluginId, input, ticket) }
                .getOrElse { PluginInvocationResult.Failed("The plugin action could not be completed.") }
            working = false
            if (result is PluginInvocationResult.AwaitingApproval) {
                awaitingApproval = true
            } else {
                onResult(result)
            }
        }
    }

    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        waitingForMicrophonePermission = false
        val ticket = pendingApprovalTicket
        pendingApprovalTicket = null
        if (granted && ticket != null) {
            execute(ticket)
        } else {
            onResult(PluginInvocationResult.Rejected(
                "Microphone access was not granted. No plugin action was started."
            ))
        }
    }

    Button(
        onClick = {
            if (working || waitingForMicrophonePermission) return@Button
            execute(null)
        },
        enabled = !working && !waitingForMicrophonePermission,
        modifier = modifier
    ) {
        Text(if (working || waitingForMicrophonePermission) "Working…" else label)
    }

    if (awaitingApproval) {
        SensitiveActionApprovalDialog(
            request = ActionRequest(pluginId, input.actionId, input.capability, input.description),
            onApprove = {
                val ticket = runtime.approveSensitiveAction(pluginId, input)
                ticket.fold(
                    onSuccess = { ticket ->
                        awaitingApproval = false
                        if (input.capability == "microphone" &&
                            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingApprovalTicket = ticket
                            waitingForMicrophonePermission = true
                            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            execute(ticket)
                        }
                    },
                    onFailure = {
                        awaitingApproval = false
                        onResult(PluginInvocationResult.Rejected(
                            it.message ?: "Approval could not be issued."
                        ))
                    }
                )
            },
            onDeny = {
                awaitingApproval = false
                onResult(PluginInvocationResult.Rejected("The user denied this action."))
            }
        )
    }
}
