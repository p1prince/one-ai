package com.oneai.app.security

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.oneai.app.core.PluginActionInput
import com.oneai.app.core.PluginInvocationResult
import com.oneai.app.core.PluginActionExecutor
import kotlinx.coroutines.launch

/**
 * Reusable user-facing entry point for a trusted plugin action.
 *
 * Sensitive actions pause for ONE AI's exact-action approval dialog. ONE AI never
 * requests Android runtime permissions on a plugin's behalf: a plugin is a separate
 * signed app and asks for any Android permission it needs (for example RECORD_AUDIO)
 * itself (decision D10 / D-R). The Core app declares no capability permissions.
 */
@Composable
fun PluginActionButton(
    pluginId: String,
    input: PluginActionInput,
    runtime: PluginActionExecutor,
    label: String,
    modifier: Modifier = Modifier,
    onResult: (PluginInvocationResult) -> Unit
) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var awaitingApproval by remember { mutableStateOf(false) }

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

    Button(
        onClick = {
            if (working) return@Button
            execute(null)
        },
        enabled = !working,
        modifier = modifier
    ) {
        Text(if (working) "Working\u2026" else label)
    }

    if (awaitingApproval) {
        SensitiveActionApprovalDialog(
            request = ActionRequest(pluginId, input.actionId, input.capability, input.description),
            onApprove = {
                val ticket = runtime.approveSensitiveAction(pluginId, input)
                ticket.fold(
                    onSuccess = { ticket ->
                        awaitingApproval = false
                        execute(ticket)
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
