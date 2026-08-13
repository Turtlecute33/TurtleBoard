// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.settings.Settings

/**
 * Material 3 theme for the clipboard panel, derived from the active keyboard theme so the panel
 * always matches the keys around it (including user-defined colors and dynamic colors).
 */
@Composable
fun ClipboardPanelTheme(content: @Composable () -> Unit) {
    val settings = Settings.getInstance()
    val colors = settings.current.mColors
    val scheme = remember(colors) { colors.toColorScheme() }
    val typeface = remember(colors) { settings.customTypeface }
    val typography = remember(typeface) {
        if (typeface == null) Typography()
        else FontFamily(typeface).let { family ->
            Typography().run {
                copy(
                    bodyLarge = bodyLarge.copy(fontFamily = family),
                    bodyMedium = bodyMedium.copy(fontFamily = family),
                    bodySmall = bodySmall.copy(fontFamily = family),
                    labelLarge = labelLarge.copy(fontFamily = family),
                    labelMedium = labelMedium.copy(fontFamily = family),
                    labelSmall = labelSmall.copy(fontFamily = family),
                    titleMedium = titleMedium.copy(fontFamily = family),
                    titleSmall = titleSmall.copy(fontFamily = family)
                )
            }
        }
    }
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

/** corner radii used by the panel; expressive rounding, but not fully circular for cards */
object ClipboardShapes {
    val card = 20.dp
    val pinnedCard = 18.dp
    val sheet = 28.dp
    val chip = 12.dp
}

private fun Colors.toColorScheme(): ColorScheme {
    val background = get(ColorType.MAIN_BACKGROUND).opaque()
    val onBackground = get(ColorType.KEY_TEXT).opaque()
    val accent = get(ColorType.CLIPBOARD_PIN).opaque() // maps to the theme accent in all color schemes
    val isDark = ColorUtils.calculateLuminance(background) < 0.5
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Color(accent),
        onPrimary = Color(accent.contrastColor()),
        primaryContainer = Color(background.blend(accent, 0.30f)),
        onPrimaryContainer = Color(onBackground),
        secondary = Color(accent),
        onSecondary = Color(accent.contrastColor()),
        secondaryContainer = Color(background.blend(accent, 0.22f)),
        onSecondaryContainer = Color(onBackground),
        background = Color(background),
        onBackground = Color(onBackground),
        surface = Color(background),
        onSurface = Color(onBackground),
        surfaceVariant = Color(background.blend(onBackground, 0.10f)),
        onSurfaceVariant = Color(background.blend(onBackground, 0.65f)),
        surfaceContainerLowest = Color(background),
        surfaceContainerLow = Color(background.blend(onBackground, 0.05f)),
        surfaceContainer = Color(background.blend(onBackground, 0.08f)),
        surfaceContainerHigh = Color(background.blend(onBackground, 0.12f)),
        surfaceContainerHighest = Color(background.blend(onBackground, 0.16f)),
        outline = Color(background.blend(onBackground, 0.40f)),
        outlineVariant = Color(background.blend(onBackground, 0.20f)),
        scrim = Color(0x99000000)
    )
}

private fun Int.opaque() = this or (0xFF shl 24)

private fun Int.blend(other: Int, ratio: Float) = ColorUtils.blendARGB(this, other, ratio)

private fun Int.contrastColor() = if (ColorUtils.calculateLuminance(this) > 0.5) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
