package com.dinsplayer.app.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioOffsetMathTest {

    // 16-bit stereo = 4 bytes per frame.
    private val frame = 4

    @Test
    fun zeroOffset_shiftsNothing() {
        assertEquals(0, AudioOffsetMath.shiftBytes(0, 48_000, frame))
    }

    @Test
    fun positiveOffset_padsWithSilence() {
        // 100 ms at 48 kHz = 4800 frames = 19200 bytes.
        assertEquals(19_200, AudioOffsetMath.shiftBytes(100, 48_000, frame))
    }

    @Test
    fun negativeOffset_dropsSamples() {
        assertEquals(-19_200, AudioOffsetMath.shiftBytes(-100, 48_000, frame))
    }

    @Test
    fun resultIsWholeFrames() {
        // 44.1 kHz gives frame counts that do not divide the byte count evenly.
        val bytes = AudioOffsetMath.shiftBytes(7, 44_100, 6)
        assertEquals(0, bytes % 6)
    }

    @Test
    fun clampsToHalfSecond() {
        assertEquals(
            AudioOffsetMath.shiftBytes(500, 48_000, frame),
            AudioOffsetMath.shiftBytes(9_999, 48_000, frame),
        )
        assertEquals(
            AudioOffsetMath.shiftBytes(-500, 48_000, frame),
            AudioOffsetMath.shiftBytes(-9_999, 48_000, frame),
        )
    }

    @Test
    fun invalidInput_returnsZero() {
        assertEquals(0, AudioOffsetMath.shiftBytes(100, 0, frame))
        assertEquals(0, AudioOffsetMath.shiftBytes(100, 48_000, 0))
    }
}
