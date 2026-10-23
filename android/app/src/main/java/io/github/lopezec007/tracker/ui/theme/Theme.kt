// Material 3 theme following the system light/dark setting. Dynamic colour
// is off so the status colours stay legible against a neutral surface.
package io.github.lopezec007.tracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colours beyond the Material scheme. */
data class TrackerPalette(
    val ok: Color,        // tracking, synced, connected
    val warn: Color,      // stale, queued
    val danger: Color,    // errors, stopped
    val subtle: Color,    // secondary text
    val rowBorder: Color,
)

val LocalPalette = staticCompositionLocalOf {
    TrackerPalette(ok = Color(0xFF2E9E4F), warn = Color(0xFFF5A524), danger = Color(0xFFE5484D), subtle = Color(0xFF6B7280), rowBorder = Color(0xFFE5E7EB))
}

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF1F6FB2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4F8),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF1F7A70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFBDEDE5),
    onSecondaryContainer = Color(0xFF00201C),
    background = Color(0xFFF9FAFB),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEEF0F3),
    onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0xFF9CA3AF),
    error = Color(0xFFB3261E),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF9CC7F5),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF14497D),
    onPrimaryContainer = Color(0xFFD3E4F8),
    secondary = Color(0xFF6ED4C6),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFBDEDE5),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF16191F),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF262A33),
    onSurfaceVariant = Color(0xFFB4BAC6),
    outline = Color(0xFF6B7280),
    error = Color(0xFFF2B8B5),
)

private val LightPalette = TrackerPalette(
    ok = Color(0xFF2E9E4F), warn = Color(0xFFF5A524), danger = Color(0xFFE5484D), subtle = Color(0xFF6B7280), rowBorder = Color(0xFFE5E7EB),
)

private val DarkPalette = TrackerPalette(
    ok = Color(0xFF6BD08A), warn = Color(0xFFF7B955), danger = Color(0xFFF26D71), subtle = Color(0xFF9CA3AF), rowBorder = Color(0xFF2A2F3A),
)

@Composable
fun TrackerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalPalette provides if (dark) DarkPalette else LightPalette) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
