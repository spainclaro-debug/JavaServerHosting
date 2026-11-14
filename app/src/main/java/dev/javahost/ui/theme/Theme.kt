package dev.javahost.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val TealGreen = Color(0xFF2BD4A8)
val TealDeep = Color(0xFF0E7C66)
val Ink = Color(0xFF0C1116)
val Surface1 = Color(0xFF121A21)
val Surface2 = Color(0xFF1A252E)
val TextPrimary = Color(0xFFE6F1F5)
val TextMuted = Color(0xFF91A4AE)
val WarnAmber = Color(0xFFFFB74D)
val Danger = Color(0xFFFF6B6B)
val InfoBlue = Color(0xFF64B5F6)

private val DarkScheme = darkColorScheme(
    primary = TealGreen,
    onPrimary = Color(0xFF002117),
    primaryContainer = TealDeep,
    onPrimaryContainer = Color(0xFFB8F5E4),
    secondary = Color(0xFF8FD3C2),
    onSecondary = Color(0xFF06231C),
    background = Ink,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMuted,
    error = Danger,
    onError = Color(0xFF2A0000),
    outline = Color(0xFF3A4A55)
)

private val LightScheme = lightColorScheme(
    primary = TealDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F5E4),
    onPrimaryContainer = Color(0xFF002117),
    background = Color(0xFFF5F9F8),
    onBackground = Color(0xFF101A17),
    surface = Color.White,
    onSurface = Color(0xFF101A17),
    surfaceVariant = Color(0xFFE2ECE8),
    onSurfaceVariant = Color(0xFF45544F),
    error = Color(0xFFB3261E)
)

@Composable
fun JavaHostTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = JavaHostTypography,
        content = content
    )
}
