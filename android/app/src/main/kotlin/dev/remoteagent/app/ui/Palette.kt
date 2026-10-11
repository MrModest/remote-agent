package dev.remoteagent.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The app's colours: Catppuccin (catppuccin.com/palette), Frappé in dark
 * mode and Latte in light mode. Mirrors apple/Shared/Views/Palette.swift;
 * views take their colours from here rather than Material's defaults.
 */
@Immutable
data class Palette(
    // Accents. Catppuccin's style guide: errors red, warnings yellow,
    // success green, links blue.
    val red: Color,
    val peach: Color,
    val yellow: Color,
    val green: Color,
    val blue: Color,
    val mauve: Color,
    // Text, from the most to the least prominent.
    val text: Color,
    val subtext: Color,
    /** Subtle text, such as placeholders and times: Overlay 1, as the style guide says. */
    val overlay: Color,
    // Surfaces. In Frappé, from the lightest to the darkest.
    val surface1: Color,
    val surface0: Color,
    /** Windows, the transcript and lists. */
    val base: Color,
    /** Grouped forms, the composer, sheets, and cards in the transcript. */
    val mantle: Color,
    /** Code and output, set into a card or the page. */
    val crust: Color,
    val dark: Boolean,
) {
    /** Buttons, controls and the user's messages. */
    val accent: Color get() = mauve
    /** Text on an accent colour. */
    val onAccent: Color get() = base

    /**
     * A terminal's colours: Catppuccin's own terminal themes, on the transcript's background.
     * `ansi` is the 16 ANSI colours: black, red, green, yellow, blue, magenta, cyan, white, then bright.
     */
    data class Terminal(val ansi: List<Int>, val background: Int, val foreground: Int, val cursor: Int, val selection: Int)

    val terminal: Terminal
        get() = if (dark) {
            Terminal(
                listOf(0x51576d, 0xe78284, 0xa6d189, 0xe5c890, 0x8caaee, 0xf4b8e4, 0x81c8be, 0xb5bfe2,
                    0x626880, 0xe78284, 0xa6d189, 0xe5c890, 0x8caaee, 0xf4b8e4, 0x81c8be, 0xa5adce),
                0x303446, 0xc6d0f5, 0xf2d5cf, 0x626880,
            )
        } else {
            Terminal(
                listOf(0x5c5f77, 0xd20f39, 0x40a02b, 0xdf8e1d, 0x1e66f5, 0xea76cb, 0x179299, 0xacb0be,
                    0x6c6f85, 0xd20f39, 0x40a02b, 0xdf8e1d, 0x1e66f5, 0xea76cb, 0x179299, 0xbcc0cc),
                0xeff1f5, 0x4c4f69, 0xdc8a78, 0xacb0be,
            )
        }

    companion object {
        val latte = Palette(
            red = Color(0xFFD20F39), peach = Color(0xFFFE640B), yellow = Color(0xFFDF8E1D), green = Color(0xFF40A02B),
            blue = Color(0xFF1E66F5), mauve = Color(0xFF8839EF), text = Color(0xFF4C4F69), subtext = Color(0xFF6C6F85),
            overlay = Color(0xFF8C8FA1), surface1 = Color(0xFFBCC0CC), surface0 = Color(0xFFCCD0DA), base = Color(0xFFEFF1F5),
            mantle = Color(0xFFE6E9EF), crust = Color(0xFFDCE0E8), dark = false,
        )
        val frappe = Palette(
            red = Color(0xFFE78284), peach = Color(0xFFEF9F76), yellow = Color(0xFFE5C890), green = Color(0xFFA6D189),
            blue = Color(0xFF8CAAEE), mauve = Color(0xFFCA9EE6), text = Color(0xFFC6D0F5), subtext = Color(0xFFA5ADCE),
            overlay = Color(0xFF838BA7), surface1 = Color(0xFF51576D), surface0 = Color(0xFF414559), base = Color(0xFF303446),
            mantle = Color(0xFF292C3C), crust = Color(0xFF232634), dark = true,
        )
    }
}

val LocalPalette = staticCompositionLocalOf { Palette.frappe }

/** The palette of the current appearance. */
val palette: Palette
    @Composable @ReadOnlyComposable get() = LocalPalette.current

/** Light or dark, from Settings, or as the system is: Latte or Frappé. */
enum class Appearance(val title: String) {
    SYSTEM("System"), DARK("Dark"), LIGHT("Light");

    companion object {
        const val KEY = "appearance"
    }
}

private fun Palette.colorScheme(): ColorScheme {
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = accent, onPrimary = onAccent, primaryContainer = surface0, onPrimaryContainer = text,
        secondary = blue, onSecondary = onAccent, secondaryContainer = surface0, onSecondaryContainer = text,
        tertiary = peach, onTertiary = onAccent,
        background = base, onBackground = text, surface = base, onSurface = text,
        surfaceVariant = surface0, onSurfaceVariant = subtext,
        surfaceContainerLowest = crust, surfaceContainerLow = mantle, surfaceContainer = mantle,
        surfaceContainerHigh = mantle, surfaceContainerHighest = surface0, surfaceBright = surface0, surfaceDim = crust,
        inverseSurface = text, inverseOnSurface = base, inversePrimary = mauve,
        error = red, onError = onAccent, errorContainer = surface0, onErrorContainer = red,
        outline = surface1, outlineVariant = surface0, scrim = Color.Black,
        surfaceTint = Color.Transparent,
    )
}

/** The app's look: the palette for the current appearance, as a Material theme too. */
@Composable
fun RemoteAgentTheme(content: @Composable () -> Unit) {
    // The appearance setting reaches here through the app's night mode
    // (UiModeManager.setApplicationNightMode), so this follows it.
    val p = if (isSystemInDarkTheme()) Palette.frappe else Palette.latte
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = p.colorScheme(), typography = appTypography(), content = content)
    }
}
