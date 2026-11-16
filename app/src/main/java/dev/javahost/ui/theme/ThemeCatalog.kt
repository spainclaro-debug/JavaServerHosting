package dev.javahost.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.javahost.core.model.ThemeChoice
import dev.javahost.core.model.ThemePalette
import dev.javahost.core.model.ThemePalettes

/**
 * Central catalogue that turns a [ThemeChoice] into the Material3 [ColorScheme]
 * the app renders with.
 *
 * [ThemeChoice.SYSTEM] and [ThemeChoice.LIGHT]/[ThemeChoice.DARK] keep the
 * historical cream and teal/ink schemes unchanged; [ThemeChoice.MATERIAL_YOU]
 * uses the Android 12+ dynamic palette (falling back to SYSTEM below API 31);
 * the five accent themes are built from their pure-JVM [ThemePalette] with all
 * roles filled in so no Material baseline purple can leak through.
 */
object ThemeCatalog {

    /** Minimum accent-vs-background contrast before a role falls back to another palette colour. */
    private const val MIN_ACCENT_CONTRAST = 2.0

    /** Error colour on the custom dark backgrounds. */
    private const val DARK_ERROR = 0xFFFF6B6B

    /** Error colour on the custom light backgrounds. */
    private const val LIGHT_ERROR = 0xFFB3261E

    /**
     * The Material3 scheme for [choice].
     *
     * [darkTheme] selects the dark variant; it is ignored for the fixed
     * [ThemeChoice.LIGHT] and [ThemeChoice.DARK] choices and only used as the
     * SYSTEM fallback for [ThemeChoice.MATERIAL_YOU] below Android 12.
     */
    fun colorScheme(choice: ThemeChoice, darkTheme: Boolean, context: Context): ColorScheme = when (choice) {
        ThemeChoice.SYSTEM -> if (darkTheme) DarkScheme else LightScheme
        ThemeChoice.LIGHT -> LightScheme
        ThemeChoice.DARK -> DarkScheme
        ThemeChoice.MATERIAL_YOU -> materialYouColorScheme(darkTheme, context)
        ThemeChoice.MONOCHROMATIC_BLUE,
        ThemeChoice.ENERGY,
        ThemeChoice.TRIADIC,
        ThemeChoice.ANALOG_GREEN,
        ThemeChoice.HIGH_CONTRAST_BLACK -> paletteColorScheme(choice, darkTheme)
    }

    /**
     * The semantic status colours (warning/danger/info/success) that stay
     * readable on the scheme [colorScheme] produces for the same arguments.
     */
    fun statusColors(choice: ThemeChoice, darkTheme: Boolean): JavaHostStatusColors = when (choice) {
        ThemeChoice.SYSTEM, ThemeChoice.MATERIAL_YOU ->
            if (darkTheme) DarkStatusColors else LightStatusColors
        ThemeChoice.LIGHT -> LightStatusColors
        ThemeChoice.DARK -> DarkStatusColors
        ThemeChoice.MONOCHROMATIC_BLUE,
        ThemeChoice.ENERGY,
        ThemeChoice.TRIADIC,
        ThemeChoice.ANALOG_GREEN,
        ThemeChoice.HIGH_CONTRAST_BLACK -> paletteStatusColors(choice, darkTheme)
    }

    // ------------------------------------------------------------------
    // Dynamic colours
    // ------------------------------------------------------------------

    private fun materialYouColorScheme(darkTheme: Boolean, context: Context): ColorScheme =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            // Dynamic colours need Android 12; SYSTEM keeps the app usable below it.
            if (darkTheme) DarkScheme else LightScheme
        }

    // ------------------------------------------------------------------
    // Custom palette themes
    // ------------------------------------------------------------------

    private fun paletteColorScheme(choice: ThemeChoice, darkTheme: Boolean): ColorScheme {
        val palette = ThemePalettes.palette(choice)
            ?: return if (darkTheme) DarkScheme else LightScheme
        val backgroundArgb = if (darkTheme) palette.darkBackground else palette.lightBackground
        val surfaceArgb = if (darkTheme) palette.darkSurface else palette.lightSurface
        val onBackgroundArgb = if (darkTheme) palette.darkOnBackground else palette.lightOnBackground

        // Every accent role gets a fallback so a palette colour that is invisible
        // on this background (e.g. HIGH_CONTRAST_BLACK's black on black) is
        // replaced by a readable sibling instead of an unreadable button.
        val inkFallback = onBackgroundArgb to backgroundArgb
        val primary = readableAccent(
            palette.primary, palette.onPrimary, backgroundArgb,
            palette.secondary to palette.onSecondary,
            palette.tertiary to palette.onTertiary,
            inkFallback
        )
        val secondary = readableAccent(
            palette.secondary, palette.onSecondary, backgroundArgb,
            palette.primary to palette.onPrimary,
            palette.tertiary to palette.onTertiary,
            inkFallback
        )
        val tertiary = readableAccent(
            palette.tertiary, palette.onTertiary, backgroundArgb,
            palette.secondary to palette.onSecondary,
            palette.primary to palette.onPrimary,
            inkFallback
        )

        val background = Color(backgroundArgb)
        val surface = Color(surfaceArgb)
        val onBackground = Color(onBackgroundArgb)
        val primaryColor = Color(primary.argb)
        val secondaryColor = Color(secondary.argb)
        val tertiaryColor = Color(tertiary.argb)

        // Containers are simple tone variants of their role over the background.
        val primaryContainer = lerp(background, primaryColor, if (darkTheme) 0.32f else 0.16f)
        val secondaryContainer = lerp(background, secondaryColor, if (darkTheme) 0.32f else 0.16f)
        val tertiaryContainer = lerp(background, tertiaryColor, if (darkTheme) 0.32f else 0.16f)
        val onContainer = onBackground

        val surfaceVariant = lerp(surface, onBackground, if (darkTheme) 0.10f else 0.06f)
        val onSurfaceVariant = lerp(onBackground, background, 0.25f)
        val outline = lerp(onBackground, background, 0.45f)
        val outlineVariant = lerp(onBackground, background, 0.72f)

        val errorArgb = if (darkTheme) DARK_ERROR else LIGHT_ERROR
        val error = Color(errorArgb)
        val onError = Color(ThemePalettes.onColorFor(errorArgb))
        val errorContainer = lerp(background, error, if (darkTheme) 0.28f else 0.14f)
        val onErrorContainer = onBackground

        val surfaceBright = if (darkTheme) lerp(surface, onBackground, 0.16f) else surface
        val surfaceDim = if (darkTheme) background else lerp(surface, onBackground, 0.06f)
        val surfaceContainerLowest = if (darkTheme) background else surface
        val surfaceContainerLow = lerp(surface, onBackground, if (darkTheme) 0.04f else 0.02f)
        val surfaceContainer = lerp(surface, onBackground, if (darkTheme) 0.08f else 0.04f)
        val surfaceContainerHigh = lerp(surface, onBackground, if (darkTheme) 0.12f else 0.06f)
        val surfaceContainerHighest = lerp(surface, onBackground, if (darkTheme) 0.16f else 0.09f)

        return (if (darkTheme) darkColorScheme() else lightColorScheme()).copy(
            primary = primaryColor,
            onPrimary = Color(primary.onArgb),
            primaryContainer = primaryContainer,
            onPrimaryContainer = onContainer,
            inversePrimary = lerp(primaryColor, onBackground, 0.5f),
            secondary = secondaryColor,
            onSecondary = Color(secondary.onArgb),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onContainer,
            tertiary = tertiaryColor,
            onTertiary = Color(tertiary.onArgb),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onBackground,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = primaryColor,
            inverseSurface = onBackground,
            inverseOnSurface = background,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = Color.Black,
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest
        )
    }

    /** An accent colour paired with the on-colour that reads on top of it. */
    private data class Accent(val argb: Long, val onArgb: Long)

    /**
     * [preferredArgb] when it reaches [MIN_ACCENT_CONTRAST] against the
     * background, otherwise the first readable fallback, otherwise the ink pair
     * (text colour as a fill) which is readable by construction.
     */
    private fun readableAccent(
        preferredArgb: Long,
        preferredOnArgb: Long,
        backgroundArgb: Long,
        vararg fallbacks: Pair<Long, Long>
    ): Accent {
        if (ThemePalettes.contrastRatio(preferredArgb, backgroundArgb) >= MIN_ACCENT_CONTRAST) {
            return Accent(preferredArgb, preferredOnArgb)
        }
        for ((argb, onArgb) in fallbacks) {
            if (ThemePalettes.contrastRatio(argb, backgroundArgb) >= MIN_ACCENT_CONTRAST) {
                return Accent(argb, onArgb)
            }
        }
        return Accent(preferredArgb, preferredOnArgb)
    }

    private fun paletteStatusColors(choice: ThemeChoice, darkTheme: Boolean): JavaHostStatusColors {
        val palette = ThemePalettes.palette(choice)
            ?: return if (darkTheme) DarkStatusColors else LightStatusColors
        val background = if (darkTheme) palette.darkBackground else palette.lightBackground
        return JavaHostStatusColors(
            warning = Color(ThemePalettes.readableOn(palette.tertiary, background)),
            danger = if (darkTheme) DarkStatusColors.danger else LightStatusColors.danger,
            info = Color(ThemePalettes.readableOn(palette.secondary, background)),
            success = Color(ThemePalettes.readableOn(palette.primary, background))
        )
    }

    // ------------------------------------------------------------------
    // Historical schemes (kept byte-for-byte for LIGHT / DARK / SYSTEM)
    // ------------------------------------------------------------------

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

    /** Teal-on-ink dark scheme, unchanged from previous releases. */
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
}
