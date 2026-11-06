package com.dinsplayer.app

import com.dinsplayer.app.core.epg.EpgManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The old test downloaded a real EPG from GitHub and asserted nothing, so it passed or
 * failed depending on the network. This one covers the lookup chain the UI depends on.
 */
class EpgTest {
    // Keys are normalizeKey() of the EPG channel ids: "SCTV.id" -> "sctv",
    // "Metro TV" -> "metrotv" (everything after the last dot is dropped).
    private val index = mapOf(
        "sctv" to "SCTV.id",
        "metrotv" to "Metro TV",
    )

    @Test
    fun exactTvgIdWins() {
        val data = mapOf("SCTV.id" to "exact", "sctv" to "normalised")
        assertEquals("exact", EpgManager.resolve("SCTV.id", "SCTV", data, index))
    }

    @Test
    fun fallsBackToNormalisedTvgId() {
        val data = mapOf("SCTV.id" to "byid")
        assertEquals("byid", EpgManager.resolve("sctv", null, data, index))
    }

    @Test
    fun fallsBackToNormalisedName() {
        val data = mapOf("Metro TV" to "byname")
        assertEquals("byname", EpgManager.resolve("", "Metro TV", data, index))
    }

    @Test
    fun noMatchReturnsNull() {
        val data = mapOf("Metro TV" to "byname")
        assertNull(EpgManager.resolve("unknown", "RCTI", data, index))
        assertNull(EpgManager.resolve(null, null, data, index))
    }

    @Test
    fun normalisedMatchOnMissingKeyIsNull() {
        // Index points at a channel the programme map never got an entry for.
        assertNull(EpgManager.resolve("sctv", "SCTV", emptyMap<String, String>(), index))
    }
}
