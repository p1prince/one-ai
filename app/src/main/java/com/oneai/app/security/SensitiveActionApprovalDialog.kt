package com.oneai.app.security

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Reusable just-in-time consent UI for a plugin action already classified as sensitive.
 * The caller must create the one-time approval ticket only inside onApprove.
 */
@Composable
fun SensitiveActionApprovalDialog(
    request: ActionRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text("Allow this action?") },
        text = {
            Text(
                "Plugin: ${request.pluginId}\n" +
                    "Action: ${request.actionId}\n" +
                    "Capability: ${request.capability}\n\n" +
                    "What ONE AI will do: ${request.description}\n\n" +
                    "This approval is for this action only. Android may still show a separate system permission prompt."
            )
        },
        confirmButton = { TextButton(onClick = onApprove) { Text("Allow once") } },
        dismissButton = { TextButton(onClick = onDeny) { Text("Deny") } }
    )
}
