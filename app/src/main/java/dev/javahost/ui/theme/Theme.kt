package dev.javahost.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.javahost.core.model.ThemeChoice

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
/* Theme-aware status colours                                                  */
/* -------------------------------------------------------------------------- */

/**
 * Semantic status colours used by warnings, errors, info and success affordances.
 *
 * The values are scheme-aware: dark schemes keep the historical bright colours
 * (readable on the near-black surfaces), light schemes use darker variants that
 * stay readable on the paper background, and the palette-driven themes derive
 * them from their own accents with a contrast rule. Prefer this over referencing
 * [WarnAmber]/[Danger]/[InfoBlue]/[TealGreen] directly in new UI code.
 */
@Immutable
data class JavaHostStatusColors(
    val warning: Color,
    val danger: Color,
    val info: Color,
    val success: Color
)

internal val DarkStatusColors = JavaHostStatusColors(
    warning = WarnAmber,
    danger = Danger,
    info = InfoBlue,
    success = TealGreen
)

/** Darker variants verified against the cream surfaces (~4.6:1 or better). */
internal val LightStatusColors = JavaHostStatusColors(
    warning = Color(0xFF7A4E0C),
    danger = Color(0xFFB3261E),
    info = Color(0xFF1F5E8C),
    success = Color(0xFF0B6650)
)

private val LocalJavaHostStatusColors = staticCompositionLocalOf { DarkStatusColors }

/**
 * The [JavaHostStatusColors] for the active scheme. Read inside composables instead
 * of the fixed dark-scheme constants so warnings/errors remain readable on cream,
 * on the accent themes and on dynamic colours.
 */
@Composable
@ReadOnlyComposable
fun javaHostStatusColors(): JavaHostStatusColors = LocalJavaHostStatusColors.current

/**
 * Applies the selected [ThemeChoice] to the whole app.
 *
 * [ThemeChoice.LIGHT] always renders the cream scheme and [ThemeChoice.DARK]
 * always renders the teal/ink scheme; [ThemeChoice.SYSTEM] follows the OS
 * setting, while [ThemeChoice.MATERIAL_YOU] and the five accent themes select
 * their light or dark variant from the OS setting too. The Material3 scheme
 * itself comes from [ThemeCatalog], and the status-colour layer switches with
 * it so warnings/errors stay readable on every theme.
 */
@Composable
fun JavaHostTheme(
    theme: ThemeChoice = ThemeChoice.SYSTEM,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (theme) {
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
        else -> systemDark
    }
    CompositionLocalProvider(
        LocalJavaHostStatusColors provides ThemeCatalog.statusColors(theme, darkTheme)
    ) {
        MaterialTheme(
            colorScheme = ThemeCatalog.colorScheme(theme, darkTheme, context),
            typography = JavaHostTypography,
            content = content
        )
    }
}
