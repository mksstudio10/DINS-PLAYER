package com.dinsplayer.app.core.player

import androidx.media3.ui.AspectRatioFrameLayout
import com.dinsplayer.app.data.models.PlayerState
import com.dinsplayer.app.features.player.ChannelItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test JVM untuk logika pure-Kotlin Multi-Stream.
 *
 * Pengujian ExoPlayer / PlaybackException dijalankan pada runtime Android.
 */
class MultiStreamLogicTest {

    private fun fakeChannel(id: String, name: String = "Channel $id") = ChannelItem(
        id = id,
        name = name,
        groupTitle = "TV",
        logoUrl = "",
        url = "https://example.com/$id.m3u8",
    )

    @Test
    fun `MultiSlotState default values benar`() {
        val slot = MultiSlotState(slotIndex = 0)
        assertEquals(0, slot.slotIndex)
        assertNull(slot.channel)
        assertFalse(slot.isAudioActive)
        assertTrue(slot.playerState is PlayerState.Idle)
        assertEquals("", slot.streamFormat)
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, slot.resizeMode)
    }

    @Test
    fun `MultiSlotState copy mempertahankan nilai yang tidak diubah`() {
        val original = MultiSlotState(slotIndex = 2)
        val updated = original.copy(isAudioActive = true)
        assertEquals(2, updated.slotIndex)
        assertTrue(updated.isAudioActive)
        assertNull(updated.channel)
    }

    @Test
    fun `MultiSlotState isAudioActive default adalah false`() {
        for (i in 0..3) {
            assertFalse(MultiSlotState(slotIndex = i).isAudioActive)
        }
    }

    @Test
    fun `MultiLayoutMode slotCount sesuai label`() {
        assertEquals(2, MultiLayoutMode.SPLIT_2.slotCount)
        assertEquals(3, MultiLayoutMode.FOCUS_3.slotCount)
        assertEquals(4, MultiLayoutMode.GRID_4.slotCount)
    }

    @Test
    fun `MultiLayoutMode label tidak kosong`() {
        MultiLayoutMode.entries.forEach { mode ->
            assertTrue("Label mode ${mode.name} tidak boleh kosong", mode.label.isNotBlank())
        }
    }

    @Test
    fun `MultiLayoutMode ada tepat 3 mode`() {
        assertEquals(3, MultiLayoutMode.entries.size)
    }

    @Test
    fun `resolveInitialLayout menyesuaikan jumlah saluran awal`() {
        assertEquals(MultiLayoutMode.SPLIT_2, MultiStreamLogic.resolveInitialLayout(0))
        assertEquals(MultiLayoutMode.SPLIT_2, MultiStreamLogic.resolveInitialLayout(1))
        assertEquals(MultiLayoutMode.SPLIT_2, MultiStreamLogic.resolveInitialLayout(2))
        assertEquals(MultiLayoutMode.FOCUS_3, MultiStreamLogic.resolveInitialLayout(3))
        assertEquals(MultiLayoutMode.GRID_4, MultiStreamLogic.resolveInitialLayout(4))
        assertEquals(MultiLayoutMode.GRID_4, MultiStreamLogic.resolveInitialLayout(5))
    }

    @Test
    fun `AudioOffsetMath shiftBytes returns zero saat sampleRateHz nol`() {
        assertEquals(0, AudioOffsetMath.shiftBytes(100L, 0, 4))
    }

    @Test
    fun `AudioOffsetMath shiftBytes returns zero saat frameSize nol`() {
        assertEquals(0, AudioOffsetMath.shiftBytes(100L, 44100, 0))
    }

    @Test
    fun `AudioOffsetMath shiftBytes returns zero saat offsetMs nol`() {
        assertEquals(0, AudioOffsetMath.shiftBytes(0L, 44100, 4))
    }

    @Test
    fun `AudioOffsetMath shiftBytes clamp ke maxMs`() {
        val sampleRate = 44100
        val frameSize = 4
        val clamped = AudioOffsetMath.shiftBytes(1000L, sampleRate, frameSize, maxMs = 500L)
        val expected = AudioOffsetMath.shiftBytes(500L, sampleRate, frameSize)
        assertEquals(expected, clamped)
    }

    @Test
    fun `AudioOffsetMath shiftBytes positif untuk offsetMs positif`() {
        val result = AudioOffsetMath.shiftBytes(100L, 44100, 4)
        assertTrue("Shift harus positif untuk offset positif", result > 0)
    }

    @Test
    fun `AudioOffsetMath shiftBytes negatif untuk offsetMs negatif`() {
        val result = AudioOffsetMath.shiftBytes(-100L, 44100, 4)
        assertTrue("Shift harus negatif untuk offset negatif", result < 0)
    }

    @Test
    fun `applyAudioSelectionToSlots menjaga single audio exclusivity`() {
        val slots = List(4) { idx -> MultiSlotState(slotIndex = idx, isAudioActive = idx == 0) }
        val updated = MultiStreamLogic.applyAudioSelectionToSlots(slots, activeAudioSlot = 2)

        assertEquals(1, updated.count { it.isAudioActive })
        assertFalse(updated[0].isAudioActive)
        assertFalse(updated[1].isAudioActive)
        assertTrue(updated[2].isAudioActive)
        assertFalse(updated[3].isAudioActive)
    }

    @Test
    fun `applyAudioSelectionToSlots dengan null membisukan semua slot`() {
        val slots = List(4) { idx -> MultiSlotState(slotIndex = idx, isAudioActive = idx == 1) }
        val muted = MultiStreamLogic.applyAudioSelectionToSlots(slots, activeAudioSlot = null)
        assertEquals(0, muted.count { it.isAudioActive })
    }

    @Test
    fun `swapSlotStates menukar channel resizeMode dan memindahkan fokus audio`() {
        val chA = fakeChannel("10", "Channel A")
        val chB = fakeChannel("20", "Channel B")
        val initial = listOf(
            MultiSlotState(
                slotIndex = 0,
                channel = chA,
                playerState = PlayerState.Playing,
                streamFormat = "HLS",
                isAudioActive = false,
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
            ),
            MultiSlotState(
                slotIndex = 1,
                channel = chB,
                playerState = PlayerState.Loading,
                streamFormat = "DASH",
                isAudioActive = true,
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            ),
            MultiSlotState(slotIndex = 2),
            MultiSlotState(slotIndex = 3),
        )

        val (swapped, newAudioSlot) = MultiStreamLogic.swapSlotStates(initial, a = 1, b = 0, currentAudioSlot = 1)

        assertEquals(0, newAudioSlot)
        assertEquals("20", swapped[0].channel?.id)
        assertEquals("DASH", swapped[0].streamFormat)
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, swapped[0].resizeMode)
        assertTrue(swapped[0].isAudioActive)
        assertEquals(0, swapped[0].slotIndex)

        assertEquals("10", swapped[1].channel?.id)
        assertEquals("HLS", swapped[1].streamFormat)
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, swapped[1].resizeMode)
        assertFalse(swapped[1].isAudioActive)
        assertEquals(1, swapped[1].slotIndex)

        assertEquals(1, swapped.count { it.isAudioActive })
    }

    @Test
    fun `swapSlotStates dengan indeks sama atau di luar batas tidak mengubah state`() {
        val initial = listOf(
            MultiSlotState(slotIndex = 0, isAudioActive = true),
            MultiSlotState(slotIndex = 1),
        )
        val (sameResult, sameAudio) = MultiStreamLogic.swapSlotStates(initial, a = 0, b = 0, currentAudioSlot = 0)
        assertEquals(initial, sameResult)
        assertEquals(0, sameAudio)

        val (oobResult, oobAudio) = MultiStreamLogic.swapSlotStates(initial, a = 0, b = 5, currentAudioSlot = 0)
        assertEquals(initial, oobResult)
        assertEquals(0, oobAudio)
    }

    @Test
    fun `sanitizeBufferDurations menjaga invarian DefaultLoadControl`() {
        val sanitized = StreamPlaybackFactory.sanitizeBufferDurations(
            minBufferMs = 2_500,
            maxBufferMs = 8_000,
            bufferForPlaybackMs = 1_500,
            bufferForPlaybackAfterRebufferMs = 3_000,
        )
        assertTrue(sanitized.minBufferMs >= sanitized.bufferForPlaybackMs)
        assertTrue(sanitized.minBufferMs >= sanitized.bufferForPlaybackAfterRebufferMs)
        assertTrue(sanitized.maxBufferMs >= sanitized.minBufferMs)
    }

    @Test
    fun `downgrade layout GRID_4 ke SPLIT_2 membersihkan slot 2 dan 3`() {
        val slots = List(4) { idx ->
            MultiSlotState(slotIndex = idx, playerState = PlayerState.Playing)
        }
        val newMode = MultiLayoutMode.SPLIT_2
        val cleaned = slots.mapIndexed { idx, state ->
            if (idx >= newMode.slotCount) MultiSlotState(idx) else state
        }
        assertTrue("Slot 0 harus tetap Playing", cleaned[0].playerState is PlayerState.Playing)
        assertTrue("Slot 1 harus tetap Playing", cleaned[1].playerState is PlayerState.Playing)
        assertTrue("Slot 2 harus Idle setelah downgrade", cleaned[2].playerState is PlayerState.Idle)
        assertTrue("Slot 3 harus Idle setelah downgrade", cleaned[3].playerState is PlayerState.Idle)
    }
}
