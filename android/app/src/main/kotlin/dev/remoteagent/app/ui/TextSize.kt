package dev.remoteagent.app.ui

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The text sizes chosen in Settings: one for the interface, one for messages.
 * Mirrors apple/Shared/Views/TextSize.swift. The interface follows the
 * system's font scale, or a step of `interfaceSizes`; messages have their own
 * size, which neither changes.
 */
object TextSize {
    /** A step of `interfaceSizes`. */
    const val INTERFACE_KEY = "interfaceTextSize"
    /** Points. */
    const val MESSAGES_KEY = "messageTextSize"
    /** The interface follows the system's font scale, and `INTERFACE_KEY` is unused. */
    const val FOLLOWS_SYSTEM_KEY = "interfaceTextSizeFollowsSystem"

    /** The body text size at each step of the interface setting; the default is the system's 1.0 scale. */
    val interfaceSizes = listOf(14f, 15f, 16f, 17f, 19f, 21f, 23f)
    const val DEFAULT_INTERFACE = 3
    val messageSizes = 12f..28f
    const val DEFAULT_MESSAGES = Metrics.TEXT_SIZE

    fun step(stored: Int) = stored.coerceIn(0, interfaceSizes.lastIndex)
    fun messages(stored: Float) = stored.coerceIn(messageSizes.start, messageSizes.endInclusive)
}

/** The interface's text styles, by the names the Apple apps use; they scale with the interface size. */
object Type {
    val title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val headline = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)
    val subheadline = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val caption2 = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
}

fun appTypography() = Typography()

/** The message size in points, from Settings. */
val LocalMessageSize = compositionLocalOf { TextSize.DEFAULT_MESSAGES }

/** `points` as text that the interface's font scale doesn't change, like messages on iOS. */
@Composable
@ReadOnlyComposable
fun fixedSp(points: Float): TextUnit = (points / LocalDensity.current.fontScale).sp

/** Prompts and replies, at the message size. */
@Composable
@ReadOnlyComposable
fun messageStyle(scale: Float = 1f): TextStyle {
    val size = LocalMessageSize.current * scale
    return TextStyle(fontSize = fixedSp(size), lineHeight = fixedSp(size * 1.4f))
}

/** Code and tool output, at the message size times `scale`. */
@Composable
@ReadOnlyComposable
fun codeStyle(scale: Float = Metrics.TOOL_TEXT): TextStyle {
    val size = LocalMessageSize.current * scale
    return TextStyle(fontSize = fixedSp(size), lineHeight = fixedSp(size * 1.35f), fontFamily = FontFamily.Monospace)
}

/**
 * The text settings for everything inside: the interface size (the system's
 * font scale, or the chosen step) and the message size.
 */
@Composable
fun TextSettings(followsSystem: Boolean, interfaceStep: Int, messageSize: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scale = if (followsSystem) density.fontScale else TextSize.interfaceSizes[TextSize.step(interfaceStep)] / 17f
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, scale),
        LocalMessageSize provides TextSize.messages(messageSize),
        content = content,
    )
}
