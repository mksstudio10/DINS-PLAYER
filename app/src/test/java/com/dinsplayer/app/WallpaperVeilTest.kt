package com.dinsplayer.app

import com.dinsplayer.app.features.mobile.VEIL_ALPHAS
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The veil alpha is chosen by contrast, so it is checked like a contract rather than eyeballed.
 * Worst case is a pure-white photo, which is exactly what a user can pick in Custom mode.
 */
class WallpaperVeilTest {

    private fun channel(byte: Int): Double {
        val s = byte / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    private fun luminance(r: Int, g: Int, b: Int) =
        0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)

    private fun contrast(a: Double, b: Double): Double {
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun everyVeilBandKeepsPrimaryTextAtAaOnWhitePhotoAndSecondaryOnBrightPhoto() {
        val primaryText = luminance(0xFF, 0xFF, 0xFF)
        val secondaryText = luminance(0xC4, 0xCA, 0xD4)
        VEIL_ALPHAS.forEach { alphaByte ->
            val a = alphaByte / 255.0
            // White photo composited under a 0x101622 veil (average channel ~0x16) keeps primary text >= 4.5:1.
            val bgWhite = (255 * (1 - a) + 0x16 * a).toInt()
            val primaryRatio = contrast(primaryText, luminance(bgWhite, bgWhite, bgWhite))
            assertTrue(
                "veil alpha 0x${alphaByte.toString(16)} gives primary $primaryRatio:1, below 4.5:1",
                primaryRatio >= 4.5,
            )

            // Daylight photo (byte 180) composited under the veil keeps brightened TextSecondary (#C4CAD4) >= 4.5:1.
            val bgDaylight = (180 * (1 - a) + 0x16 * a).toInt()
            val secondaryRatio = contrast(secondaryText, luminance(bgDaylight, bgDaylight, bgDaylight))
            assertTrue(
                "veil alpha 0x${alphaByte.toString(16)} gives secondary $secondaryRatio:1, below 4.5:1",
                secondaryRatio >= 4.5,
            )
        }
    }
}
