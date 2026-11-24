package com.dinsplayer.app

import com.dinsplayer.app.core.epg.EpgManager
import com.dinsplayer.app.core.epg.EpgUrlStatus
import com.dinsplayer.app.core.epg.EpgUrlValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The old test downloaded a real EPG from GitHub and asserted nothing, so it passed or
 * failed depending on the network. This one covers the lookup chain and URL validation logic.
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

    @Test
    fun urlValidatorAcceptsHttpAndHttpsOnly() {
        assertTrue(EpgUrlValidator.isValidHttpUrl("https://example.com/epg.xml.gz"))
        assertTrue(EpgUrlValidator.isValidHttpUrl("http://192.168.1.10:8080/guide.xml"))
        assertFalse(EpgUrlValidator.isValidHttpUrl("ftp://example.com/epg.xml"))
        assertFalse(EpgUrlValidator.isValidHttpUrl("example.com/epg.xml"))
        assertFalse(EpgUrlValidator.isValidHttpUrl(""))
    }

    @Test
    fun xmltvHeaderDetectionAcceptsXmltvAndRejectsHtml() {
        assertTrue(EpgUrlValidator.looksLikeXmltvHeader("<?xml version=\"1.0\"?><tv generator-info-name=\"test\">"))
        assertTrue(EpgUrlValidator.looksLikeXmltvHeader("\uFEFF  <programme start=\"2026\" channel=\"id\">"))
        assertFalse(EpgUrlValidator.looksLikeXmltvHeader("<!DOCTYPE html><html><body>404 Not Found</body></html>"))
        assertFalse(EpgUrlValidator.looksLikeXmltvHeader("   "))
    }

    @Test
    fun statusSummaryCountsActiveErrorAndCheckingCorrectly() {
        val urls = listOf("u1", "u2", "u3")
        val allActive = mapOf(
            "u1" to EpgUrlStatus.Active(200, "Aktif"),
            "u2" to EpgUrlStatus.Active(200, "Aktif"),
            "u3" to EpgUrlStatus.Active(200, "Aktif"),
        )
        assertEquals("3 tautan EPG (3 aktif)", EpgUrlValidator.summarizeEpgStatuses(urls, allActive))

        val mixed = mapOf(
            "u1" to EpgUrlStatus.Active(200, "Aktif"),
            "u2" to EpgUrlStatus.Error("HTTP 404"),
        )
        assertEquals("3 tautan EPG (1 aktif, 1 error)", EpgUrlValidator.summarizeEpgStatuses(urls, mixed))

        val checking = mapOf("u1" to EpgUrlStatus.Checking)
        assertEquals("3 tautan EPG (memeriksa...)", EpgUrlValidator.summarizeEpgStatuses(urls, checking))
    }

    @Test
    fun errorMappersProduceReadableIndonesianMessages() {
        assertTrue(EpgUrlValidator.mapHttpStatusError(404).contains("404"))
        assertTrue(EpgUrlValidator.mapExceptionError(UnknownHostException("bad.host")).contains("Domain"))
        assertTrue(EpgUrlValidator.mapExceptionError(SocketTimeoutException()).contains("timeout"))
    }
}
