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
    fun everyVeilBandKeepsSecondaryTextAtAaOnWhitePhoto() {
        val text = luminance(0x9E, 0x9E, 0x9E)
        VEIL_ALPHAS.forEach { alphaByte ->
            val a = alphaByte / 255.0
            // White photo composited under a 0x0A0A0A veil.
            val bg = (255 * (1 - a) + 0x0A * a).toInt()
            val ratio = contrast(text, luminance(bg, bg, bg))
            assertTrue(
                "veil alpha 0x${alphaByte.toString(16)} gives $ratio:1, below 4.5:1",
                ratio >= 4.5,
            )
        }
    }
}
