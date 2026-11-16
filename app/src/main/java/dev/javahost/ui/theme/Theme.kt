package dev.javahost.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/* -------------------------------------------------------------------------- */
/* Exported palette (dark scheme + console)                                    */
/* -------------------------------------------------------------------------- */

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

/* -------------------------------------------------------------------------- */
/* Light scheme — warm cream paper + brown ink (v1.4.0 redesign)               */
/* -------------------------------------------------------------------------- */

/** Warm paper background of the whole app in light mode. */
private val CreamBackground = Color(0xFFF0E4C8)

/** Accent / primary ink brown used for active servers and emphasis. */
private val AccentBrown = Color(0xFF5A4C39)

private val CreamOnAccent = Color(0xFFF7EFDD)
private val CreamPrimaryContainer = Color(0xFFE7D9BE)
private val CreamOnPrimaryContainer = Color(0xFF3B3123)
private val CreamOnBackground = Color(0xFF2E261B)

/** Slightly lighter than the background so cards separate from the paper. */
private val CreamSurface = Color(0xFFF6ECD8)
private val CreamSurfaceVariant = Color(0xFFE3D3B4)
private val CreamOnSurfaceVariant = Color(0xFF5C4E3C)
private val CreamSecondary = Color(0xFF7A6A52)
private val CreamSecondaryContainer = Color(0xFFE0D2B6)
private val CreamOnSecondaryContainer = Color(0xFF352C20)
private val CreamOutline = Color(0xFF9C8A6E)
private val CreamError = Color(0xFFB3261E)

/* -------------------------------------------------------------------------- */
/* Dark scheme — unchanged from previous releases                             */
/* -------------------------------------------------------------------------- */

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
    primary = AccentBrown,
    onPrimary = CreamOnAccent,
    primaryContainer = CreamPrimaryContainer,
    onPrimaryContainer = CreamOnPrimaryContainer,
    secondary = CreamSecondary,
    onSecondary = CreamOnAccent,
    secondaryContainer = CreamSecondaryContainer,
    onSecondaryContainer = CreamOnSecondaryContainer,
    // Same warm tones as secondary so no Material baseline purple can leak through.
    tertiary = CreamSecondary,
    onTertiary = CreamOnAccent,
    tertiaryContainer = CreamSecondaryContainer,
    onTertiaryContainer = CreamOnSecondaryContainer,
    background = CreamBackground,
    onBackground = CreamOnBackground,
    surface = CreamSurface,
    onSurface = CreamOnBackground,
    surfaceVariant = CreamSurfaceVariant,
    onSurfaceVariant = CreamOnSurfaceVariant,
    error = CreamError,
    onError = Color.White,
    outline = CreamOutline
)

/* -------------------------------------------------------------------------- */
/* Theme-aware status colours                                                  */
/* -------------------------------------------------------------------------- */

/**
 * Semantic status colours used by warnings, errors, info and success affordances.
 *
 * The values are scheme-aware: the dark scheme keeps the historical bright colours
 * (readable on the near-black surfaces), while the light cream scheme uses darker
 * variants that stay readable on the paper background. Prefer this over referencing
 * [WarnAmber]/[Danger]/[InfoBlue]/[TealGreen] directly in new UI code.
 */
@Immutable
data class JavaHostStatusColors(
    val warning: Color,
    val danger: Color,
    val info: Color,
    val success: Color
)

private val DarkStatusColors = JavaHostStatusColors(
    warning = WarnAmber,
    danger = Danger,
    info = InfoBlue,
    success = TealGreen
)

/** Darker variants verified against the cream surfaces (~4.6:1 or better). */
private val LightStatusColors = JavaHostStatusColors(
    warning = Color(0xFF7A4E0C),
    danger = Color(0xFFB3261E),
    info = Color(0xFF1F5E8C),
    success = Color(0xFF0B6650)
)

private val LocalJavaHostStatusColors = staticCompositionLocalOf { DarkStatusColors }

/**
 * The [JavaHostStatusColors] for the active scheme. Read inside composables instead
 * of the fixed dark-scheme constants so warnings/errors remain readable on cream.
 */
@Composable
@ReadOnlyComposable
fun javaHostStatusColors(): JavaHostStatusColors = LocalJavaHostStatusColors.current

@Composable
fun JavaHostTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalJavaHostStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = JavaHostTypography,
            content = content
        )
    }
}
