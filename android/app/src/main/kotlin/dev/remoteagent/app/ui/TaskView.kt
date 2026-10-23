package dev.remoteagent.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.remoteagent.core.protocol.ServerTask
import dev.remoteagent.core.store.ServerTaskProgress
import dev.remoteagent.core.store.ServerTaskProgress.Phase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * A task on the server as it goes: how it is doing, the sign-in page it
 * gave and a field for the code that page shows, and the command's output.
 * Closing it leaves the task running; the server's settings show it, and
 * open this again. Mirrors apple/Shared/Views/TaskView.swift.
 */
@Composable
fun TaskSheet(progress: ServerTaskProgress, agentName: String?, onDismiss: () -> Unit) {
    val task by progress.task.collectAsStateWithLifecycle()
    val phase by progress.phase.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var code by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val name = agentName ?: task.harness ?: ""
    val title = when (task.kind) {
        ServerTask.UPDATE_SERVER -> "Updating rad"
        ServerTask.UPDATE_AGENT -> "Updating $name"
        ServerTask.LOGIN -> "Signing In to $name"
        else -> task.kind
    }

    fun act(block: suspend () -> Unit) {
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    fun send() {
        val text = code.trim()
        if (text.isEmpty() || sending) return
        sending = true
        act {
            try {
                progress.send(text)
                code = ""
            } finally {
                sending = false
            }
        }
    }

    FullScreenDialog(onDismiss) {
        Screen(
            title = { Text(title, style = Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            background = palette.mantle,
            actions = {
                TextButton(onDismiss, modifier = Modifier.testTag("task-done")) { Text("Done", color = palette.accent, style = Type.headline) }
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().formWidth()
                    .padding(start = Metrics.margin, end = Metrics.margin, bottom = Metrics.margin),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TaskStatus(task, phase, name, Modifier.weight(1f))
                    if (phase == Phase.RUNNING) {
                        TextButton({ act { progress.cancel() } }, modifier = Modifier.testTag("task-cancel")) {
                            Text("Cancel", color = palette.red, style = Type.body)
                        }
                    }
                }

                val url = webURL(task.url)
                if (url != null && phase == Phase.RUNNING) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                                } catch (_: android.content.ActivityNotFoundException) {
                                    error = "No app here opens $url"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent),
                            shape = RoundedCornerShape(Metrics.Corner.card),
                            modifier = Modifier.testTag("open-sign-in"),
                        ) {
                            Icon(painterResource(Icons.openPage), null, Modifier.size(18.dp))
                            Text("Open Sign-In Page", modifier = Modifier.padding(start = 6.dp))
                        }
                        TextButton({
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Sign-in link", url))
                        }) {
                            Icon(painterResource(Icons.copy), null, Modifier.size(18.dp), tint = palette.accent)
                            Text("Copy Link", color = palette.accent, style = Type.body, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }

                if (task.input && phase == Phase.RUNNING) {
                    Row(
                        Modifier.fillMaxWidth().background(palette.base, RoundedCornerShape(Metrics.Corner.field)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FormTextField(
                            code, { code = it }, "Paste the code here", Modifier.weight(1f).testTag("task-code"),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                        )
                        if (sending) {
                            CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(18.dp), color = palette.subtext, strokeWidth = 2.dp)
                        } else {
                            val enabled = code.isNotBlank()
                            TextButton(::send, enabled = enabled, modifier = Modifier.testTag("task-send")) {
                                Text("Send", color = if (enabled) palette.accent else palette.overlay, style = Type.headline)
                            }
                        }
                    }
                }

                error?.let {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(Icons.warning), null, tint = palette.red, modifier = Modifier.size(16.dp))
                        Text(it, color = palette.red, style = Type.subheadline)
                    }
                }

                if (task.output.isNotEmpty()) TaskOutput(task.output, Modifier.weight(1f, fill = false))
            }
        }
    }
}

/** A spinner, a checkmark or a cross, and what it means. */
@Composable
private fun TaskStatus(task: ServerTask, phase: Phase, name: String, modifier: Modifier = Modifier) {
    val text = when (phase) {
        Phase.RUNNING -> if (task.kind == ServerTask.LOGIN && webURL(task.url) != null) "Waiting for you to sign in…" else "Running…"
        Phase.RESTARTING -> "Restarting rad…"
        Phase.DONE -> when (task.kind) {
            ServerTask.UPDATE_SERVER -> task.version?.let { "rad $it is running." } ?: "rad is running."
            ServerTask.UPDATE_AGENT -> task.version?.let { "$name $it" } ?: "Updated."
            ServerTask.LOGIN -> "Signed in."
            else -> "Done."
        }
        Phase.FAILED -> task.error?.takeIf { it.isNotEmpty() }?.let { "Failed: $it" } ?: "Failed."
        Phase.CANCELLED -> "Cancelled."
    }
    Row(modifier.testTag("task-status"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (phase) {
                Phase.RUNNING, Phase.RESTARTING -> CircularProgressIndicator(Modifier.size(16.dp), color = palette.subtext, strokeWidth = 2.dp)
                Phase.DONE -> Icon(painterResource(Icons.completed), null, tint = palette.green)
                Phase.FAILED -> Icon(painterResource(Icons.warning), null, tint = palette.red)
                Phase.CANCELLED -> Icon(painterResource(Icons.stopped), null, tint = palette.subtext)
            }
        }
        Text(text, style = Type.body, color = if (phase == Phase.FAILED) palette.red else palette.text)
    }
}

/**
 * The command's output, in the code font: it keeps to the bottom as it
 * grows, unless scrolled up to read.
 */
@Composable
private fun TaskOutput(text: String, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    var follows by remember { mutableStateOf(true) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue }.collect { if (follows) scroll.scrollTo(it) }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.collect { scrolling -> if (!scrolling) follows = scroll.value >= scroll.maxValue - 4 }
    }
    Box(modifier.fillMaxWidth().background(palette.crust, RoundedCornerShape(Metrics.Corner.card))) {
        SelectionContainer {
            Text(
                text, style = codeStyle(), color = palette.text,
                modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(Metrics.padding).testTag("task-output"),
            )
        }
    }
}

/** `s` if it is a web address (http or https), the only kind this opens; else null. */
fun webURL(s: String?): String? {
    val scheme = s?.substringBefore(':', "")?.lowercase() ?: return null
    return if ((scheme == "http" || scheme == "https") && s.length > scheme.length + 1) s else null
}
