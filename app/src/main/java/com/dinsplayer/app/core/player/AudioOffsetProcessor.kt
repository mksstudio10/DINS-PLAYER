package com.dinsplayer.app.core.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Shifts the audio track in time by inserting silence or dropping samples.
 *
 * ExoPlayer has no audio offset API. A constant delay needs no running buffer: inserting N ms
 * of silence at the start shifts every following sample by N ms, and dropping N ms has the
 * opposite effect. Progress is tracked as a signed byte count so dragging the slider while
 * audio is playing converges on the new target instead of re-applying the full offset.
 *
 * Positive [offsetMs] delays audio (audio was early), negative advances it (audio was late).
 */
class AudioOffsetProcessor : BaseAudioProcessor() {

    @Volatile
    var offsetMs: Long = 0L

    private var frameSize = 0
    private var sampleRateHz = 0
    /** Signed bytes already shifted: positive = silence inserted, negative = samples dropped. */
    private var netShift = 0

    private fun desiredBytes(): Int =
        AudioOffsetMath.shiftBytes(offsetMs, sampleRateHz, frameSize)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRateHz = inputAudioFormat.sampleRate
        frameSize = inputAudioFormat.bytesPerFrame
        netShift = 0
        return inputAudioFormat
    }

    override fun onFlush() {
        // A flush means a seek or a discontinuity: the new stream needs the offset again.
        netShift = 0
    }

    override fun onReset() {
        netShift = 0
        frameSize = 0
        sampleRateHz = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0 || frameSize <= 0) return

        val delta = desiredBytes() - netShift

        if (delta > 0) {
            // Insert silence in whole frames, capped so one call cannot allocate a huge buffer.
            val cap = MAX_PAD_BYTES - (MAX_PAD_BYTES % frameSize)
            val pad = minOf(delta, cap)
            netShift += pad
            val out = replaceOutputBuffer(pad + remaining)
            out.put(ByteArray(pad))
            out.put(inputBuffer)
            out.flip()
            return
        }

        if (delta < 0) {
            // Advance audio: drop whole frames from the front of this buffer.
            val droppable = (remaining / frameSize) * frameSize
            val drop = minOf(-delta, droppable)
            if (drop > 0) {
                inputBuffer.position(inputBuffer.position() + drop)
                netShift -= drop
            }
            if (!inputBuffer.hasRemaining()) return
            val out = replaceOutputBuffer(inputBuffer.remaining())
            out.put(inputBuffer)
            out.flip()
            return
        }

        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()
    }

    private companion object {
        const val MAX_PAD_BYTES = 64 * 1024
    }
}

/** Pure arithmetic, split out so it can be unit tested without an Android runtime. */
object AudioOffsetMath {

    /**
     * Byte shift for [offsetMs]: positive to pad with silence, negative to drop.
     * Always a whole number of frames, clamped to [maxMs] of audio.
     */
    fun shiftBytes(offsetMs: Long, sampleRateHz: Int, frameSize: Int, maxMs: Long = 500L): Int {
        if (offsetMs == 0L || sampleRateHz <= 0 || frameSize <= 0) return 0
        val clamped = offsetMs.coerceIn(-maxMs, maxMs)
        val frames = clamped * sampleRateHz / 1000L
        return (frames * frameSize).toInt()
    }
}
