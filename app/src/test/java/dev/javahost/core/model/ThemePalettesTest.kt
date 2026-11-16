package dev.javahost.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ThemePalettes]. No Compose or Android classes are touched,
 * so these run on any host and lock down the exact ARGB values of every theme.
 */
class ThemePalettesTest {

    private val fixedChoices = listOf(
        ThemeChoice.LIGHT,
        ThemeChoice.DARK,
        ThemeChoice.MONOCHROMATIC_BLUE,
        ThemeChoice.ENERGY,
        ThemeChoice.TRIADIC,
        ThemeChoice.ANALOG_GREEN,
        ThemeChoice.HIGH_CONTRAST_BLACK
    )

    private val customChoices = listOf(
        ThemeChoice.MONOCHROMATIC_BLUE,
        ThemeChoice.ENERGY,
        ThemeChoice.TRIADIC,
        ThemeChoice.ANALOG_GREEN,
        ThemeChoice.HIGH_CONTRAST_BLACK
    )

    // ------------------------------------------------------------------
    // palette(choice) coverage
    // ------------------------------------------------------------------

    @Test
    fun `system and material you are not palette driven`() {
        assertNull(ThemePalettes.palette(ThemeChoice.SYSTEM))
        assertNull(ThemePalettes.palette(ThemeChoice.MATERIAL_YOU))
    }

    @Test
    fun `every theme choice is handled`() {
        for (choice in ThemeChoice.entries) {
            val palette = ThemePalettes.palette(choice)
            if (choice == ThemeChoice.SYSTEM || choice == ThemeChoice.MATERIAL_YOU) {
                assertNull("$choice must not have a fixed palette", palette)
            } else {
                assertNotNull("$choice must have a fixed palette", palette)
            }
        }
        assertEquals(7, ThemeChoice.entries.count { ThemePalettes.palette(it) != null })
        assertEquals(fixedChoices.toSet(), ThemeChoice.entries.filter { ThemePalettes.palette(it) != null }.toSet())
    }

    // ------------------------------------------------------------------
    // LIGHT — the exact v1.4.0 cream scheme
    // ------------------------------------------------------------------

    @Test
    fun `light theme keeps the exact cream and brown values`() {
        val palette = ThemePalettes.palette(ThemeChoice.LIGHT)!!
        assertEquals(0xFF5A4C39L, palette.primary)
        assertEquals(0xFFF7EFDDL, palette.onPrimary)
        assertEquals(0xFF7A6A52L, palette.secondary)
        assertEquals(0xFFF7EFDDL, palette.onSecondary)
        assertEquals(0xFF7A6A52L, palette.tertiary)
        assertEquals(0xFFF7EFDDL, palette.onTertiary)
        assertEquals(0xFFF0E4C8L, palette.lightBackground)
        assertEquals(0xFFF6ECD8L, palette.lightSurface)
        assertEquals(0xFF2E261BL, palette.lightOnBackground)
        assertEquals(0xFF0C1116L, palette.darkBackground)
        assertEquals(0xFF121A21L, palette.darkSurface)
        assertEquals(0xFFE6F1F5L, palette.darkOnBackground)
    }

    // ------------------------------------------------------------------
    // DARK — the exact historical teal/ink scheme
    // ------------------------------------------------------------------

    @Test
    fun `dark theme keeps the exact teal and ink values`() {
        val palette = ThemePalettes.palette(ThemeChoice.DARK)!!
        assertEquals(0xFF2BD4A8L, palette.primary)
        assertEquals(0xFF002117L, palette.onPrimary)
        assertEquals(0xFF8FD3C2L, palette.secondary)
        assertEquals(0xFF06231CL, palette.onSecondary)
        assertEquals(0xFF8FD3C2L, palette.tertiary)
        assertEquals(0xFF06231CL, palette.onTertiary)
        assertEquals(0xFF0C1116L, palette.darkBackground)
        assertEquals(0xFF121A21L, palette.darkSurface)
        assertEquals(0xFFE6F1F5L, palette.darkOnBackground)
    }

    // ------------------------------------------------------------------
    // Custom accent themes — exact contract colours
    // ------------------------------------------------------------------

    @Test
    fun `custom themes use the exact contract accent colours`() {
        val expected = mapOf(
            ThemeChoice.MONOCHROMATIC_BLUE to Triple(0xFF1A73E8L, 0xFF4A90D9L, 0xFFE8F0FEL),
            ThemeChoice.ENERGY to Triple(0xFF2C3E50L, 0xFFE67E22L, 0xFFF1C40FL),
            ThemeChoice.TRIADIC to Triple(0xFF00B4DBL, 0xFFFF6B6BL, 0xFFF9CA24L),
            ThemeChoice.ANALOG_GREEN to Triple(0xFF27AE60L, 0xFF2980B9L, 0xFF1ABC9CL),
            ThemeChoice.HIGH_CONTRAST_BLACK to Triple(0xFF000000L, 0xFFFFC107L, 0xFFFFFFFFL)
        )
        for ((choice, colours) in expected) {
            val palette = ThemePalettes.palette(choice)!!
            assertEquals("$choice primary", colours.first, palette.primary)
            assertEquals("$choice secondary", colours.second, palette.secondary)
            assertEquals("$choice tertiary", colours.third, palette.tertiary)
        }
    }

    @Test
    fun `custom themes define both light and dark backgrounds surfaces and ink`() {
        val expected = mapOf(
            ThemeChoice.MONOCHROMATIC_BLUE to listOf(
                0xFFF4F8FEL, 0xFFFDFEFFL, 0xFF101418L, 0xFF0B1220L, 0xFF121B2BL, 0xFFE6EDF7L
            ),
            ThemeChoice.ENERGY to listOf(
                0xFFFDF8F0L, 0xFFFFFCF7L, 0xFF1B1A17L, 0xFF1B2430L, 0xFF232F3DL, 0xFFECF0F4L
            ),
            ThemeChoice.TRIADIC to listOf(
                0xFFF5FAFDL, 0xFFFFFFFFL, 0xFF14181BL, 0xFF0D1A22L, 0xFF16252FL, 0xFFE6F2F7L
            ),
            ThemeChoice.ANALOG_GREEN to listOf(
                0xFFF2FAF6L, 0xFFFDFFFEL, 0xFF101815L, 0xFF0B1A16L, 0xFF142620L, 0xFFE4F3EDL
            ),
            ThemeChoice.HIGH_CONTRAST_BLACK to listOf(
                0xFFFFFFFFL, 0xFFFFFFFFL, 0xFF000000L, 0xFF000000L, 0xFF000000L, 0xFFFFFFFFL
            )
        )
        for ((choice, values) in expected) {
            val palette = ThemePalettes.palette(choice)!!
            assertEquals("$choice light background", values[0], palette.lightBackground)
            assertEquals("$choice light surface", values[1], palette.lightSurface)
            assertEquals("$choice light on-background", values[2], palette.lightOnBackground)
            assertEquals("$choice dark background", values[3], palette.darkBackground)
            assertEquals("$choice dark surface", values[4], palette.darkSurface)
            assertEquals("$choice dark on-background", values[5], palette.darkOnBackground)
        }
    }

    @Test
    fun `custom theme on colours are the higher contrast of black and white`() {
        val expected = mapOf(
            ThemeChoice.MONOCHROMATIC_BLUE to listOf(
                ThemePalettes.BLACK, ThemePalettes.BLACK, ThemePalettes.BLACK
            ),
            ThemeChoice.ENERGY to listOf(
                ThemePalettes.WHITE, ThemePalettes.BLACK, ThemePalettes.BLACK
            ),
            ThemeChoice.TRIADIC to listOf(
                ThemePalettes.BLACK, ThemePalettes.BLACK, ThemePalettes.BLACK
            ),
            ThemeChoice.ANALOG_GREEN to listOf(
                ThemePalettes.BLACK, ThemePalettes.BLACK, ThemePalettes.BLACK
            ),
            ThemeChoice.HIGH_CONTRAST_BLACK to listOf(
                ThemePalettes.WHITE, ThemePalettes.BLACK, ThemePalettes.BLACK
            )
        )
        for ((choice, onColours) in expected) {
            val palette = ThemePalettes.palette(choice)!!
            assertEquals("$choice onPrimary", onColours[0], palette.onPrimary)
            assertEquals("$choice onSecondary", onColours[1], palette.onSecondary)
            assertEquals("$choice onTertiary", onColours[2], palette.onTertiary)
        }
    }

    @Test
    fun `custom on colours equal onColorFor of their accent`() {
        // LIGHT and DARK intentionally keep their hand-tuned cream/teal on-colours.
        for (choice in customChoices) {
            val palette = ThemePalettes.palette(choice)!!
            assertEquals("$choice onPrimary", ThemePalettes.onColorFor(palette.primary), palette.onPrimary)
            assertEquals("$choice onSecondary", ThemePalettes.onColorFor(palette.secondary), palette.onSecondary)
            assertEquals("$choice onTertiary", ThemePalettes.onColorFor(palette.tertiary), palette.onTertiary)
        }
    }

    @Test
    fun `all palette values are opaque argb colours`() {
        for (choice in fixedChoices) {
            val palette = ThemePalettes.palette(choice)!!
            val values = listOf(
                palette.primary, palette.onPrimary,
                palette.secondary, palette.onSecondary,
                palette.tertiary, palette.onTertiary,
                palette.lightBackground, palette.lightSurface, palette.lightOnBackground,
                palette.darkBackground, palette.darkSurface, palette.darkOnBackground
            )
            for (value in values) {
                assertEquals("$choice value $value must be opaque ARGB", 0xFFL, (value ushr 24) and 0xFFL)
            }
        }
    }

    // ------------------------------------------------------------------
    // onColorFor
    // ------------------------------------------------------------------

    @Test
    fun `onColorFor picks the higher contrast of black and white`() {
        assertEquals(ThemePalettes.BLACK, ThemePalettes.onColorFor(0xFFFFFFFFL))
        assertEquals(ThemePalettes.BLACK, ThemePalettes.onColorFor(0xFFF1C40FL)) // bright yellow
        assertEquals(ThemePalettes.BLACK, ThemePalettes.onColorFor(0xFFFFC107L)) // amber
        assertEquals(ThemePalettes.BLACK, ThemePalettes.onColorFor(0xFFF9CA24L)) // yellow
        assertEquals(ThemePalettes.BLACK, ThemePalettes.onColorFor(0xFFE8F0FEL)) // near-white blue
        assertEquals(ThemePalettes.WHITE, ThemePalettes.onColorFor(0xFF000000L))
        assertEquals(ThemePalettes.WHITE, ThemePalettes.onColorFor(0xFF2C3E50L)) // navy
        // Mid-tone accents: black is the better text colour even though their
        // luminance is below 0.5 (the old Material approximation picked white).
        assertEquals(ThemePalettes.BLACK, ThemePalettes.onColorFor(0xFF1A73E8L)) // Google blue
        assertEquals(ThemePalettes.BLACK, ThemePalettes.onColorFor(0xFF808080L)) // mid grey
        assertEquals(ThemePalettes.BLACK, ThemePalettes.onColorFor(0xFF00B4DBL)) // Triadic primary
        assertEquals(ThemePalettes.BLACK, ThemePalettes.onColorFor(0xFF27AE60L)) // Analog primary
        assertEquals(ThemePalettes.BLACK, ThemePalettes.onColorFor(0xFF1ABC9CL)) // Analog tertiary
        assertEquals(ThemePalettes.BLACK, ThemePalettes.onColorFor(0xFFFF6B6BL)) // Triadic secondary
        assertEquals(ThemePalettes.BLACK, ThemePalettes.onColorFor(0xFFE67E22L)) // Energy secondary
        assertEquals(ThemePalettes.BLACK, ThemePalettes.onColorFor(0xFF4A90D9L)) // Mono secondary
    }

    @Test
    fun `onColorFor always picks the better of black and white`() {
        val samples = listOf(
            0xFFFFFFFFL, 0xFF000000L, 0xFF808080L,
            // The six accents the v1.5.0 accessibility audit flagged.
            0xFF00B4DBL, 0xFF27AE60L, 0xFF1ABC9CL, 0xFFFF6B6BL, 0xFFE67E22L, 0xFF4A90D9L,
            // Every other palette accent.
            0xFF1A73E8L, 0xFFE8F0FEL, 0xFF2C3E50L, 0xFFF1C40FL, 0xFFF9CA24L,
            0xFF2980B9L, 0xFFFFC107L
        )
        for (argb in samples) {
            val on = ThemePalettes.onColorFor(argb)
            val other = if (on == ThemePalettes.BLACK) ThemePalettes.WHITE else ThemePalettes.BLACK
            assertTrue(
                "on-colour $on for $argb must beat its alternative",
                ThemePalettes.contrastRatio(on, argb) >= ThemePalettes.contrastRatio(other, argb)
            )
        }
    }

    @Test
    fun `audited mid-tone accents reach AA with their on-colour`() {
        val audited = listOf(
            0xFF00B4DBL, // Triadic primary, was 2.46:1 white
            0xFF27AE60L, // Analog Green primary, was 2.87:1 white
            0xFF1ABC9CL, // Analog Green tertiary, was 2.41:1 white
            0xFFFF6B6BL, // Triadic secondary, was 2.78:1 white
            0xFFE67E22L, // Energy secondary, was 2.85:1 white
            0xFF4A90D9L // Monochromatic Blue secondary, was 3.34:1 white
        )
        for (accent in audited) {
            val on = ThemePalettes.onColorFor(accent)
            val ratio = ThemePalettes.contrastRatio(on, accent)
            assertTrue("on-colour for $accent must reach 4.5:1, was $ratio", ratio >= 4.5)
        }
    }

    @Test
    fun `every fixed palette on colour reaches AA on its accent`() {
        for (choice in fixedChoices) {
            val palette = ThemePalettes.palette(choice)!!
            val pairs = listOf(
                Triple("primary", palette.primary, palette.onPrimary),
                Triple("secondary", palette.secondary, palette.onSecondary),
                Triple("tertiary", palette.tertiary, palette.onTertiary)
            )
            for ((role, accent, on) in pairs) {
                val ratio = ThemePalettes.contrastRatio(on, accent)
                assertTrue("$choice $role on-colour must reach 4.5:1, was $ratio", ratio >= 4.5)
            }
        }
    }

    @Test
    fun `onColorFor only ever returns opaque black or white`() {
        val samples = listOf(0x00000000L, 0x7F7F7F7FL, 0xFF123456L, 0xFFFFFFFFL, 0xFFF0E4C8L)
        for (sample in samples) {
            val on = ThemePalettes.onColorFor(sample)
            assertTrue("on-colour for $sample must be black or white", on == ThemePalettes.BLACK || on == ThemePalettes.WHITE)
            assertEquals(0xFFL, (on ushr 24) and 0xFFL)
        }
    }

    // ------------------------------------------------------------------
    // Luminance and contrast helpers
    // ------------------------------------------------------------------

    @Test
    fun `relative luminance spans black to white`() {
        assertEquals(0.0, ThemePalettes.relativeLuminance(0xFF000000L), 0.0001)
        assertEquals(1.0, ThemePalettes.relativeLuminance(0xFFFFFFFFL), 0.0001)
        // Alpha must not influence the luminance of the colour channels.
        assertEquals(
            ThemePalettes.relativeLuminance(0xFF1A73E8L),
            ThemePalettes.relativeLuminance(0x001A73E8L),
            0.0001
        )
        // Mid grey is darker than half luminance in linear space.
        assertTrue(ThemePalettes.relativeLuminance(0xFF808080L) < 0.5)
        assertTrue(ThemePalettes.relativeLuminance(0xFFF1C40FL) > 0.5)
    }

    @Test
    fun `contrast ratio matches known wcag values`() {
        assertEquals(21.0, ThemePalettes.contrastRatio(0xFF000000L, 0xFFFFFFFFL), 0.01)
        assertEquals(1.0, ThemePalettes.contrastRatio(0xFF123456L, 0xFF123456L), 0.0001)
        assertEquals(
            ThemePalettes.contrastRatio(0xFF1A73E8L, 0xFFFFFFFFL),
            ThemePalettes.contrastRatio(0xFFFFFFFFL, 0xFF1A73E8L),
            0.0001
        )
        assertTrue(ThemePalettes.contrastRatio(0xFF000000L, 0xFFFFFFFFL) > ThemePalettes.contrastRatio(0xFF808080L, 0xFFFFFFFFL))
    }

    // ------------------------------------------------------------------
    // readableOn
    // ------------------------------------------------------------------

    @Test
    fun `readableOn keeps colours that already have enough contrast`() {
        assertEquals(0xFFFFFFFFL, ThemePalettes.readableOn(0xFFFFFFFFL, 0xFF000000L))
        assertEquals(0xFF000000L, ThemePalettes.readableOn(0xFF000000L, 0xFFFFFFFFL))
        assertEquals(0xFF1A73E8L, ThemePalettes.readableOn(0xFF1A73E8L, 0xFFFFFFFFL))
    }

    @Test
    fun `readableOn darkens a light colour on a light background`() {
        val background = 0xFFFFFFFFL
        val adjusted = ThemePalettes.readableOn(0xFFF1C40FL, background)
        assertTrue("adjusted colour must differ from the unreadable one", adjusted != 0xFFF1C40FL)
        assertTrue(
            "adjusted contrast ${ThemePalettes.contrastRatio(adjusted, background)} must reach 4.5",
            ThemePalettes.contrastRatio(adjusted, background) >= 4.5
        )
        assertEquals(0xFFL, (adjusted ushr 24) and 0xFFL)
    }

    @Test
    fun `readableOn lightens a dark colour on a dark background`() {
        val background = 0xFF000000L
        val adjusted = ThemePalettes.readableOn(0xFF2C3E50L, background)
        assertTrue(adjusted != 0xFF2C3E50L)
        assertTrue(ThemePalettes.contrastRatio(adjusted, background) >= 4.5)
    }

    @Test
    fun `readableOn always reaches the requested ratio`() {
        val backgrounds = listOf(0xFF000000L, 0xFFFFFFFFL, 0xFF0C1116L, 0xFFF0E4C8L, 0xFF808080L)
        val colours = fixedChoices.flatMap { choice ->
            val palette = ThemePalettes.palette(choice)!!
            listOf(palette.primary, palette.secondary, palette.tertiary)
        }
        for (background in backgrounds) {
            for (colour in colours) {
                val adjusted = ThemePalettes.readableOn(colour, background)
                assertTrue(
                    "readableOn($colour, $background) = $adjusted must reach 4.5",
                    ThemePalettes.contrastRatio(adjusted, background) >= 4.5
                )
                assertEquals(0xFFL, (adjusted ushr 24) and 0xFFL)
            }
        }
    }
}
