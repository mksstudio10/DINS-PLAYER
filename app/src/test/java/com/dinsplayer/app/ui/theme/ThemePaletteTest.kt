package com.dinsplayer.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ThemePaletteTest {

    private fun channel(value: Float): Double {
        return if (value <= 0.03928f) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun defaultBluePresetMatchesOriginalTokens() {
        assertEquals(DefaultBlueTokens, resolveThemeTokens(null, 0))
        assertEquals(DefaultBlueTokens, resolveThemeTokens("unknown", 0))
        assertEquals(DefaultBlueTokens, resolveThemeTokens(ThemePreset.BLUE.key, 0))
    }

    @Test
    fun parseHexColorHandlesValidAndInvalidInputs() {
        assertEquals(0xFF2979FF.toInt(), ThemeColorMath.parseHexColor("#2979FF"))
        assertEquals(0xFF10B981.toInt(), ThemeColorMath.parseHexColor("10b981"))
        assertEquals("#E53935", ThemeColorMath.formatHex(0xFFE53935.toInt()))
        assertNull(ThemeColorMath.parseHexColor("#123"))
        assertNull(ThemeColorMath.parseHexColor("ZZZZZZ"))
        assertNull(ThemeColorMath.parseHexColor(""))
    }

    @Test
    fun hslRoundTripPreservesPrimaryColors() {
        val samples = listOf(
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt(),
            0xFF0000FF.toInt(),
            0xFF2979FF.toInt(),
            0xFFE11D48.toInt(),
        )
        samples.forEach { argb ->
            val hsl = ThemeColorMath.rgbToHsl(argb)
            val rebuilt = ThemeColorMath.hslToArgb(hsl[0], hsl[1], hsl[2])
            assertEquals(argb, rebuilt)
        }
    }

    @Test
    fun derivedThemesMaintainDarkSurfaceContrastAcrossAllPresetsAndExtremes() {
        val testSeeds = ThemePreset.entries.map { it.argb } + listOf(
            0xFFFFFF00.toInt(), // bright yellow
            0xFF00FFFF.toInt(), // cyan
            0xFF050505.toInt(), // near black
            0xFFFFFFFF.toInt(), // white
        )
        testSeeds.forEach { seed ->
            val tokens = resolveThemeTokens(ThemePreset.CUSTOM_KEY, seed)
            val primaryOnBase = contrast(TextPrimary, tokens.bgBase)
            val secondaryOnBase = contrast(TextSecondary, tokens.bgBase)
            val secondaryOnSurface = contrast(TextSecondary, tokens.bgSurface)

            assertTrue("Primary contrast on bgBase too low: $primaryOnBase", primaryOnBase >= 10.0)
            assertTrue("Secondary contrast on bgBase too low: $secondaryOnBase", secondaryOnBase >= 6.0)
            assertTrue("Secondary contrast on bgSurface too low: $secondaryOnSurface", secondaryOnSurface >= 4.5)
        }
    }
}
