package dev.javahost.core.model

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Colour palette of one selectable fixed theme, stored as ARGB hex values
 * (`0xAARRGGBB`) so the theme data stays pure JVM: no Compose or Android
 * classes are needed to read, test or preview it.
 *
 * A palette carries the three accent roles Material3 asks for plus the
 * background/surface/on-background triplet for both the light and the dark
 * variant of the theme. [ThemePalettes.palette] returns `null` for the themes
 * that are not palette-driven ([ThemeChoice.SYSTEM] follows the OS setting and
 * [ThemeChoice.MATERIAL_YOU] uses the wall-paper derived dynamic colours).
 */
data class ThemePalette(
    val primary: Long,
    val onPrimary: Long,
    val secondary: Long,
    val onSecondary: Long,
    val tertiary: Long,
    val onTertiary: Long,
    val lightBackground: Long,
    val lightSurface: Long,
    val lightOnBackground: Long,
    val darkBackground: Long,
    val darkSurface: Long,
    val darkOnBackground: Long
)

/**
 * Fixed palettes for the selectable JavaHost themes plus the small pure-math
 * colour helpers the Compose catalogue and the tests share.
 *
 * All values are opaque ARGB longs. The five user-selectable accent themes use
 * [onColorFor] to pick black or white on-colours; the historical cream (LIGHT)
 * and teal/ink (DARK) themes keep their exact hand-tuned on-colours.
 */
object ThemePalettes {

    /** Opaque black, used by [onColorFor] for light backgrounds. */
    const val BLACK: Long = 0xFF000000

    /** Opaque white, used by [onColorFor] for dark backgrounds. */
    const val WHITE: Long = 0xFFFFFFFF

    /**
     * The palette for [choice], or `null` when the theme is not palette-driven
     * ([ThemeChoice.SYSTEM], [ThemeChoice.MATERIAL_YOU]).
     */
    fun palette(choice: ThemeChoice): ThemePalette? = when (choice) {
        ThemeChoice.SYSTEM -> null
        ThemeChoice.MATERIAL_YOU -> null
        ThemeChoice.LIGHT -> LIGHT
        ThemeChoice.DARK -> DARK
        ThemeChoice.MONOCHROMATIC_BLUE -> MONOCHROMATIC_BLUE
        ThemeChoice.ENERGY -> ENERGY
        ThemeChoice.TRIADIC -> TRIADIC
        ThemeChoice.ANALOG_GREEN -> ANALOG_GREEN
        ThemeChoice.HIGH_CONTRAST_BLACK -> HIGH_CONTRAST_BLACK
    }

    /**
     * Reads a colour's WCAG relative luminance in `0.0..1.0` (black is 0, white
     * is 1). Alpha is ignored.
     */
    fun relativeLuminance(argb: Long): Double {
        val red = linearChannel(((argb shr 16) and 0xFF).toInt())
        val green = linearChannel(((argb shr 8) and 0xFF).toInt())
        val blue = linearChannel((argb and 0xFF).toInt())
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    /**
     * WCAG contrast ratio between two opaque colours, from 1.0 (identical) to
     * 21.0 (black on white). Symmetric, alpha is ignored.
     */
    fun contrastRatio(foreground: Long, background: Long): Double {
        val first = relativeLuminance(foreground)
        val second = relativeLuminance(background)
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Black or white, whichever gives the higher WCAG contrast ratio against
     * [argb]. This is the actual-contrast rule (equality point at relative
     * luminance ≈0.179) rather than Material's "luminance > 0.5" approximation,
     * so mid-tone accents still get the readable text colour and filled
     * buttons/tags reach AA where possible. Used for on-colours of the
     * palette-driven themes.
     */
    fun onColorFor(argb: Long): Long =
        if (contrastRatio(WHITE, argb) >= contrastRatio(BLACK, argb)) WHITE else BLACK

    /**
     * Returns [color] unchanged when it already reaches [minRatio] against
     * [background]; otherwise blends it towards black or white (whichever of the
     * two reaches the higher contrast on this background) until the ratio is
     * met. Never returns a colour that is harder to read than the input, and
     * falls back to the best of black/white in the pathological case.
     */
    fun readableOn(color: Long, background: Long, minRatio: Double = 4.5): Long {
        if (contrastRatio(color, background) >= minRatio) return color
        // A mid-tone background can make the luminance-preferred on-colour weaker
        // than its opposite, so pick the target by actual contrast.
        val target = if (contrastRatio(WHITE, background) >= contrastRatio(BLACK, background)) WHITE else BLACK
        var candidate = color
        repeat(MAX_CONTRAST_STEPS) {
            candidate = blend(candidate, target, CONTRAST_STEP)
            if (contrastRatio(candidate, background) >= minRatio) return candidate
        }
        return target
    }

    // ------------------------------------------------------------------
    // Fixed palettes
    // ------------------------------------------------------------------

    /** Warm cream paper + brown ink, mirroring the v1.4.0 light scheme. */
    private val LIGHT = ThemePalette(
        primary = 0xFF5A4C39,
        onPrimary = 0xFFF7EFDD,
        secondary = 0xFF7A6A52,
        onSecondary = 0xFFF7EFDD,
        tertiary = 0xFF7A6A52,
        onTertiary = 0xFFF7EFDD,
        lightBackground = 0xFFF0E4C8,
        lightSurface = 0xFFF6ECD8,
        lightOnBackground = 0xFF2E261B,
        darkBackground = 0xFF0C1116,
        darkSurface = 0xFF121A21,
        darkOnBackground = 0xFFE6F1F5
    )

    /** Teal on ink, mirroring the historical dark scheme. */
    private val DARK = ThemePalette(
        primary = 0xFF2BD4A8,
        onPrimary = 0xFF002117,
        secondary = 0xFF8FD3C2,
        onSecondary = 0xFF06231C,
        tertiary = 0xFF8FD3C2,
        onTertiary = 0xFF06231C,
        lightBackground = 0xFFF0E4C8,
        lightSurface = 0xFFF6ECD8,
        lightOnBackground = 0xFF2E261B,
        darkBackground = 0xFF0C1116,
        darkSurface = 0xFF121A21,
        darkOnBackground = 0xFFE6F1F5
    )

    /** Blue monochrome theme: Google blue, lighter blue, near-white blue. */
    private val MONOCHROMATIC_BLUE = fixed(
        primary = 0xFF1A73E8,
        secondary = 0xFF4A90D9,
        tertiary = 0xFFE8F0FE,
        lightBackground = 0xFFF4F8FE,
        lightSurface = 0xFFFDFEFF,
        lightOnBackground = 0xFF101418,
        darkBackground = 0xFF0B1220,
        darkSurface = 0xFF121B2B,
        darkOnBackground = 0xFFE6EDF7
    )

    /** Energy theme: deep navy, vibrant orange, warm yellow. */
    private val ENERGY = fixed(
        primary = 0xFF2C3E50,
        secondary = 0xFFE67E22,
        tertiary = 0xFFF1C40F,
        lightBackground = 0xFFFDF8F0,
        lightSurface = 0xFFFFFCF7,
        lightOnBackground = 0xFF1B1A17,
        darkBackground = 0xFF1B2430,
        darkSurface = 0xFF232F3D,
        darkOnBackground = 0xFFECF0F4
    )

    /** Triadic theme: cyan, coral red and bright yellow. */
    private val TRIADIC = fixed(
        primary = 0xFF00B4DB,
        secondary = 0xFFFF6B6B,
        tertiary = 0xFFF9CA24,
        lightBackground = 0xFFF5FAFD,
        lightSurface = 0xFFFFFFFF,
        lightOnBackground = 0xFF14181B,
        darkBackground = 0xFF0D1A22,
        darkSurface = 0xFF16252F,
        darkOnBackground = 0xFFE6F2F7
    )

    /** Analogous-green theme: emerald, ocean blue and turquoise. */
    private val ANALOG_GREEN = fixed(
        primary = 0xFF27AE60,
        secondary = 0xFF2980B9,
        tertiary = 0xFF1ABC9C,
        lightBackground = 0xFFF2FAF6,
        lightSurface = 0xFFFDFFFE,
        lightOnBackground = 0xFF101815,
        darkBackground = 0xFF0B1A16,
        darkSurface = 0xFF142620,
        darkOnBackground = 0xFFE4F3ED
    )

    /** High-contrast black theme: black, amber and white. */
    private val HIGH_CONTRAST_BLACK = fixed(
        primary = 0xFF000000,
        secondary = 0xFFFFC107,
        tertiary = 0xFFFFFFFF,
        lightBackground = 0xFFFFFFFF,
        lightSurface = 0xFFFFFFFF,
        lightOnBackground = 0xFF000000,
        darkBackground = 0xFF000000,
        darkSurface = 0xFF000000,
        darkOnBackground = 0xFFFFFFFF
    )

    /** Builds a palette, deriving the accent on-colours with [onColorFor]. */
    private fun fixed(
        primary: Long,
        secondary: Long,
        tertiary: Long,
        lightBackground: Long,
        lightSurface: Long,
        lightOnBackground: Long,
        darkBackground: Long,
        darkSurface: Long,
        darkOnBackground: Long
    ): ThemePalette = ThemePalette(
        primary = primary,
        onPrimary = onColorFor(primary),
        secondary = secondary,
        onSecondary = onColorFor(secondary),
        tertiary = tertiary,
        onTertiary = onColorFor(tertiary),
        lightBackground = lightBackground,
        lightSurface = lightSurface,
        lightOnBackground = lightOnBackground,
        darkBackground = darkBackground,
        darkSurface = darkSurface,
        darkOnBackground = darkOnBackground
    )

    // ------------------------------------------------------------------
    // Colour math
    // ------------------------------------------------------------------

    private const val MAX_CONTRAST_STEPS = 8
    private const val CONTRAST_STEP = 0.25

    /** Converts one 0..255 sRGB channel to its linear-light value. */
    private fun linearChannel(channel: Int): Double {
        val srgb = channel / 255.0
        return if (srgb <= 0.03928) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
    }

    /** Linear blend of two opaque ARGB colours; [amount] is the share of [toward]. */
    private fun blend(from: Long, toward: Long, amount: Double): Long {
        fun channel(shift: Int): Long {
            val start = ((from shr shift) and 0xFF).toDouble()
            val end = ((toward shr shift) and 0xFF).toDouble()
            return (start * (1.0 - amount) + end * amount).roundToLong().coerceIn(0L, 0xFFL)
        }
        return (0xFFL shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
