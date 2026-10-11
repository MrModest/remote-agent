package dev.remoteagent.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.remoteagent.core.store.ConnectionState
import dev.remoteagent.core.store.ServerConnection
import dev.remoteagent.core.store.TerminalState
import org.connectbot.terminal.TerminalEmulator

/**
 * A session's shell on the server. It keeps running when this goes away;
 * showing it again resumes where it was. Mirrors apple/Shared/Views/TerminalPanel.swift.
 */
@Composable
fun TerminalPanel(
    connection: ServerConnection,
    sessionID: String,
    folder: String,
    hide: () -> Unit,
    modifier: Modifier = Modifier,
    /** The key row keeps clear of the navigation bar and the keyboard itself. */
    insets: Boolean = true,
) {
    val session = remember(connection, sessionID) { connection.terminal(sessionID) }
    val connectionState by connection.state.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val closedHere by session.closedHere.collectAsStateWithLifecycle()
    val modifiers = remember { StickyModifiers() }
    var emulator by remember { mutableStateOf<TerminalEmulator?>(null) }
    var menu by remember { mutableStateOf(false) }

    DisposableEffect(session) { onDispose { session.detach() } }
    LaunchedEffect(session, connectionState) { session.connectionChanged(connectionState == ConnectionState.Connected) }
    BackHandler(onBack = hide)

    Column(modifier.background(palette.base).testTag("terminal-panel")) {
        Row(
            Modifier.fillMaxWidth().background(palette.mantle).padding(start = Metrics.margin, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(Icons.terminal), null, Modifier.size(16.dp), tint = palette.subtext)
            Text(folder, style = Type.caption, color = palette.subtext, maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f))
            Box {
                IconButton({ menu = true }) { Icon(painterResource(Icons.more), "Terminal Menu", tint = palette.accent) }
                DropdownMenu(menu, { menu = false }, containerColor = palette.mantle) {
                    MenuItem("Close Shell", Icons.forceStop, color = palette.red, enabled = state == TerminalState.Open) {
                        menu = false
                        session.closeShell()
                    }
                }
            }
            IconButton(hide) { Icon(painterResource(Icons.close), "Hide Terminal", tint = palette.accent) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(4.dp)) {
            TerminalHost(session, modifiers, onEmulator = { emulator = it }, modifier = Modifier.fillMaxSize())
            when (val s = state) {
                TerminalState.Reconnecting -> Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 8.dp).background(palette.mantle, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(Icons.reconnect), null, Modifier.size(14.dp), tint = palette.subtext)
                    Text("Reconnecting…", style = Type.caption, color = palette.subtext)
                }
                is TerminalState.Exited -> Banner(
                    if (closedHere) "The shell was closed." else if (s.code == 0) "The shell exited." else "The shell exited with code ${s.code}.",
                    "Start New Shell", session::restart,
                )
                TerminalState.Gone -> Banner("The shell has ended.", "Start New Shell", session::restart)
                is TerminalState.Failed -> Banner(s.message, "Try Again", session::restart)
                TerminalState.Connecting, TerminalState.Open -> {}
            }
        }
        TerminalKeys(emulator, modifiers, if (insets) Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)) else Modifier)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Banner(message: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.align(Alignment.BottomCenter).padding(Metrics.margin).fillMaxWidth()
            .background(palette.mantle, RoundedCornerShape(Metrics.Corner.card))
            .border(1.dp, palette.surface1, RoundedCornerShape(Metrics.Corner.card)).padding(10.dp).testTag("terminal-banner"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = Type.subheadline, color = palette.text, modifier = Modifier.weight(1f))
        Button(onAction, colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent)) {
            Text(action, style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

/** The bar above a terminal below the session: dragging it sets the terminal's height. */
@Composable
fun PanelResizeHandle(height: Float, range: ClosedFloatingPointRange<Float>, onHeight: (Float) -> Unit, onDone: () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var current by remember { mutableFloatStateOf(height) }
    Box(
        Modifier.fillMaxWidth().height(16.dp).background(palette.mantle)
            .pointerInput(range) {
                detectVerticalDragGestures(onDragStart = { current = height }, onDragEnd = onDone) { change, dy ->
                    change.consume()
                    current = (current - with(density) { dy.toDp().value }).coerceIn(range.start, range.endInclusive)
                    onHeight(current)
                }
            }
            .testTag("terminal-resize"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 36.dp, height = 4.dp).background(palette.surface1, CircleShape))
    }
}
