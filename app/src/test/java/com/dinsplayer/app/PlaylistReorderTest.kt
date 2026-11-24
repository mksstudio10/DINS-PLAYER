package com.dinsplayer.app

import com.dinsplayer.app.features.playlist.mergeFilteredOrder
import com.dinsplayer.app.features.playlist.movePlaylistItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistReorderTest {

    @Test
    fun movePlaylistItemMovesForwardAndBackwardWithoutLosingItems() {
        val original = listOf("A", "B", "C", "D")
        assertEquals(listOf("C", "A", "B", "D"), movePlaylistItem(original, 2, 0))
        assertEquals(listOf("A", "C", "D", "B"), movePlaylistItem(original, 1, 3))
        assertEquals(original, movePlaylistItem(original, 1, 1))
        assertEquals(original, movePlaylistItem(original, -1, 2))
        assertEquals(original, movePlaylistItem(original, 0, 99))
    }

    @Test
    fun mergeFilteredOrderPreservesHiddenPlaylistsPositions() {
        val allIds = listOf(10L, 20L, 30L, 40L, 50L)
        // Suppose search filter hid 20L and 40L, leaving [10, 30, 50],
        // and the user moved 50L above 10L -> [50, 10, 30].
        val merged = mergeFilteredOrder(allIds, listOf(50L, 10L, 30L))
        assertEquals(listOf(50L, 20L, 10L, 40L, 30L), merged)
    }
}
