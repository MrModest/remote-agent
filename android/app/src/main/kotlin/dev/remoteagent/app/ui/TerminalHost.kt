// The only file that imports termlib (org.connectbot.terminal), as
// TerminalHost.swift is the only one that imports SwiftTerm.
package dev.remoteagent.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.remoteagent.core.store.TerminalSession
import kotlinx.coroutines.delay
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import org.connectbot.terminal.VTermKey

/** Ctrl from the key row, for the next key: tap once for one key, as the web's sticky ctrl. */
@Stable
class StickyModifiers : ModifierManager {
    var ctrl by mutableStateOf(false)
    override fun isCtrlActive() = ctrl
    override fun isAltActive() = false
    override fun isShiftActive() = false
    override fun clearTransients() {
        ctrl = false
    }
}

/** termlib's terminal, showing a session's shell (TerminalHost.swift with SwiftTerm). */
@Composable
fun TerminalHost(session: TerminalSession, modifiers: StickyModifiers, onEmulator: (TerminalEmulator) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = palette.terminal
    var size by remember(session) { mutableStateOf<TerminalDimensions?>(null) }
    val emulator = remember(session) {
        TerminalEmulatorFactory.create(
            defaultForeground = Color(0xFF000000.toInt() or colors.foreground),
            defaultBackground = Color(0xFF000000.toInt() or colors.background),
            onKeyboardInput = { session.send(it) },
            onResize = { size = it },
            // A program in the shell copies text (OSC 52), as tmux and vim can.
            onClipboardCopy = { text ->
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Terminal", text))
            },
            autoDetectUrls = true,
        )
    }
    LaunchedEffect(emulator) { onEmulator(emulator) }
    LaunchedEffect(emulator, colors) {
        emulator.applyColorScheme(colors.ansi.map { 0xFF000000.toInt() or it }.toIntArray(), 0xFF000000.toInt() or colors.foreground, 0xFF000000.toInt() or colors.background)
    }
    DisposableEffect(session, emulator) {
        session.onOutput = { emulator.writeInput(it, 0, it.size) }
        // CAN ends any sequence the last connection left half-sent; ESC c resets.
        session.onReset = { emulator.writeInput(byteArrayOf(0x18, 0x1b, 'c'.code.toByte()), 0, 3) }
        onDispose {
            session.onOutput = {}
            session.onReset = {}
        }
    }
    // Connects once the view's size settles, so the shell starts at it rather
    // than at a size it passes through while the panel opens; then follows it.
    var started by remember(session) { mutableStateOf(false) }
    LaunchedEffect(session, size) {
        val s = size ?: emulator.dimensions
        if (s.columns < 2 || s.rows < 2) return@LaunchedEffect
        if (started) {
            session.resize(s.columns, s.rows)
        } else {
            delay(150)
            started = true
            session.start(s.columns, s.rows, create = true)
        }
    }
    Terminal(
        terminalEmulator = emulator,
        modifier = modifier.testTag("terminal"),
        typeface = Typeface.MONOSPACE,
        initialFontSize = fixedSp(12f),
        backgroundColor = Color(0xFF000000.toInt() or colors.background),
        foregroundColor = Color(0xFF000000.toInt() or colors.foreground),
        selectionBackgroundColor = Color(0xFF000000.toInt() or colors.selection),
        keyboardEnabled = true,
        modifierManager = modifiers,
        onHyperlinkClick = { link ->
            val uri = link.toUri()
            if (uri.scheme == "http" || uri.scheme == "https") context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        },
    )
}

/**
 * Keys a phone keyboard lacks, above it: esc, a sticky ctrl, tab and the
 * arrows (libvterm applies the application cursor mode itself).
 */
@Composable
fun TerminalKeys(emulator: TerminalEmulator?, modifiers: StickyModifiers, modifier: Modifier = Modifier) {
    fun key(k: Int) {
        emulator?.dispatchKey(if (modifiers.ctrl) CTRL else 0, k)
        modifiers.clearTransients()
    }
    Row(
        modifier.fillMaxWidth().background(palette.mantle).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp).testTag("terminal-keys"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KeyCap("esc") { key(VTermKey.ESCAPE) }
        KeyCap("ctrl", on = modifiers.ctrl) { modifiers.ctrl = !modifiers.ctrl }
        KeyCap("tab") { key(VTermKey.TAB) }
        KeyCap(icon = Icons.keyLeft, label = "Left") { key(VTermKey.LEFT) }
        KeyCap(icon = Icons.keyUp, label = "Up") { key(VTermKey.UP) }
        KeyCap(icon = Icons.keyDown, label = "Down") { key(VTermKey.DOWN) }
        KeyCap(icon = Icons.keyRight, label = "Right") { key(VTermKey.RIGHT) }
    }
}

@Composable
private fun KeyCap(text: String? = null, icon: Int? = null, label: String? = null, on: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).widthIn(min = 44.dp)
            .background(if (on) palette.accent else palette.surface0, RoundedCornerShape(Metrics.Corner.card))
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        val color = if (on) palette.onAccent else palette.text
        if (icon != null) Icon(painterResource(icon), label, Modifier.size(20.dp), tint = color)
        else Text(text ?: "", color = color, style = Type.footnote.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 14.sp))
    }
}

/** libvterm's VTermModifier for Ctrl (vterm_keycodes.h). */
private const val CTRL = 4
