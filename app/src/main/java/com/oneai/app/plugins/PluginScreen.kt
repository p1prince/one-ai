package com.oneai.app.plugins

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oneai.app.bubble.BubbleElement
import com.oneai.app.bubble.BubbleRunning

/**
 * The in-app screen of one plugin (decision D12, slice C2): the same declarative view the Floating Bubble
 * draws, rendered here by ONE AI. The plugin supplies data only; every element, the approval dialog, the
 * Running state with Cancel and the result frame are drawn by the host. Plugin text is labelled as plugin
 * text. Leaving the screen cancels a running call. It is shown for an Enabled plugin only and never reads
 * Bubble access; the caller (slice C3) decides when it may be opened.
 */
@Composable
fun PluginScreen(pluginName: String, controller: InAppPluginController, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ui by controller.ui.collectAsState()
    val inputs = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(controller) { controller.load() }
    DisposableEffect(controller) { onDispose { controller.close() } }
    BackHandler(onBack = onBack)

    val idle = ui.call !is PluginCallState.Running && ui.call !is PluginCallState.AwaitingApproval
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(pluginName, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        Text(SCREEN_NOTE, fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        OutlinedButton(onClick = onBack) { Text("Back") }

        when (val view = ui.view) {
            is PluginViewState.Loading -> Text("Loading…", color = MaterialTheme.colorScheme.onBackground)
            is PluginViewState.Failed -> {
                Text(view.message, color = MaterialTheme.colorScheme.onBackground)
                Button(onClick = { controller.load() }) { Text("Try again") }
            }
            is PluginViewState.Loaded -> view.view.elements.forEach { element ->
                when (element) {
                    is BubbleElement.Label -> Text(element.text, fontSize = element.size.sp, color = MaterialTheme.colorScheme.onBackground)
                    is BubbleElement.TextInput -> OutlinedTextField(
                        value = inputs[element.key].orEmpty(),
                        onValueChange = { inputs[element.key] = it.take(MAX_INPUT) },
                        label = { Text(element.hint) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = idle
                    )
                    is BubbleElement.Button -> Button(
                        onClick = { controller.press(element.onPress(inputs.toMap())) },
                        enabled = idle,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(element.label) }
                }
            }
        }

        ui.notice?.let { notice ->
            ResultFrame(notice.title, notice.content, onDismiss = { controller.dismiss() })
        }

        when (val call = ui.call) {
            is PluginCallState.Running -> {
                Text(call.heading, color = MaterialTheme.colorScheme.onBackground)
                Text(LEAVE_NOTE, fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                Button(onClick = { controller.cancel() }) { Text("Cancel") }
            }
            is PluginCallState.Done -> ResultFrame(call.title, call.content, onDismiss = { controller.dismiss() })
            is PluginCallState.Cancelled -> ResultFrame(BubbleRunning.CANCELLED_TITLE, BubbleRunning.CANCELLED_NOTE, onDismiss = { controller.dismiss() }, fromPlugin = false)
            else -> Unit
        }
    }

    ui.approval?.let { approval ->
        AlertDialog(
            onDismissRequest = { controller.reject() },
            title = { Text(approval.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(approval.message)
                    approval.review?.let { Text(it, fontSize = 12.sp) }
                    Text(APPROVAL_NOTE, fontSize = 11.sp)
                }
            },
            confirmButton = { Button(onClick = { controller.approve() }) { Text(approval.confirmLabel) } },
            dismissButton = { TextButton(onClick = { controller.reject() }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ResultFrame(title: String, content: String, onDismiss: () -> Unit, fromPlugin: Boolean = true) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (fromPlugin) Text(RESULT_NOTE, fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(content, color = MaterialTheme.colorScheme.onBackground)
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

private const val MAX_INPUT = 4_000
private const val SCREEN_NOTE = "This screen is supplied by the plugin. ONE AI draws it and asks before anything sensitive runs."
private const val LEAVE_NOTE = "Leaving this screen cancels the command."
private const val RESULT_NOTE = "Text from the plugin"
private const val APPROVAL_NOTE = "ONE AI asks every time. The values above are exactly what will be sent."
