package com.dinsplayer.app.core.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uBackupUrlTest {

    @Test
    fun parsesSingleUrlWithSingleServerSource() = runBlocking {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="News",CNN
            https://cdn1.example.com/cnn.m3u8
        """.trimIndent()

        val channels = M3uParser.parseM3uString(playlistId = 1L, m3u = m3u)
        assertEquals(1, channels.size)
        val ch = channels[0]
        assertEquals("https://cdn1.example.com/cnn.m3u8", ch.url)
        assertEquals(1, ch.resolvedSources().size)
        assertEquals("Server 1", ch.resolvedSources()[0].serverName)
    }

    @Test
    fun accumulatesDoubleAndTripleConsecutiveUrlsAsBackupServers() = runBlocking {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="rcti" group-title="National",RCTI
            https://primary.example.com/rcti.m3u8
            https://backup1.example.com/rcti.m3u8
            https://backup2.example.com/rcti.mpd
            #EXTINF:-1,SCTV
            https://primary.example.com/sctv.m3u8
        """.trimIndent()

        val channels = M3uParser.parseM3uString(playlistId = 1L, m3u = m3u)
        assertEquals(2, channels.size)

        val rcti = channels[0]
        assertEquals("RCTI", rcti.name)
        assertEquals("https://primary.example.com/rcti.m3u8", rcti.url)
        val sources = rcti.resolvedSources()
        assertEquals(3, sources.size)
        assertEquals("Server 1", sources[0].serverName)
        assertEquals("https://primary.example.com/rcti.m3u8", sources[0].url)
        assertEquals("Server 2", sources[1].serverName)
        assertEquals("https://backup1.example.com/rcti.m3u8", sources[1].url)
        assertEquals("Server 3", sources[2].serverName)
        assertEquals("https://backup2.example.com/rcti.mpd", sources[2].url)

        val sctv = channels[1]
        assertEquals("SCTV", sctv.name)
        assertEquals(1, sctv.resolvedSources().size)
    }

    @Test
    fun isolatesPipeHeadersPerUrlAndAppliesBetweenUrlOverrides() = runBlocking {
        val m3u = """
            #EXTM3U
            #EXTINF:-1,Trans TV
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            #KODIPROP:inputstream.adaptive.license_key=00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100
            https://s1.example.com/stream.mpd|User-Agent=AgentOne&Referer=https://ref1.example.com
            #EXTVLCOPT:http-user-agent=AgentTwo
            https://s2.example.com/stream.m3u8
        """.trimIndent()

        val channels = M3uParser.parseM3uString(playlistId = 1L, m3u = m3u)
        assertEquals(1, channels.size)
        val sources = channels[0].resolvedSources()
        assertEquals(2, sources.size)

        assertEquals("AgentOne", sources[0].userAgent)
        assertEquals("https://ref1.example.com", sources[0].referer)
        assertEquals("clearkey", sources[0].drmScheme)

        // Pipe headers from URL 1 must not leak to URL 2, and EXTVLCOPT between URL 1 and URL 2 applies only to URL 2.
        assertEquals("AgentTwo", sources[1].userAgent)
        assertEquals("", sources[1].referer)
    }

    @Test
    fun preservesStyleBTagsBeforeNextExtinf() = runBlocking {
        val m3u = """
            #EXTM3U
            #EXTINF:-1,Channel A
            https://a1.example.com/live.m3u8
            #EXTVLCOPT:http-user-agent=AgentB
            #EXTINF:-1,Channel B
            https://b1.example.com/live.m3u8
        """.trimIndent()

        val channels = M3uParser.parseM3uString(playlistId = 1L, m3u = m3u)
        assertEquals(2, channels.size)
        assertEquals("", channels[0].userAgent)
        assertEquals("AgentB", channels[1].userAgent)
        assertNull(channels[0].drmLicenseUri)
    }

    @Test
    fun deduplicatesIdenticalBackupUrlsWithinSameChannel() = runBlocking {
        val m3u = """
            #EXTM3U
            #EXTINF:-1,Channel Dup
            https://same.example.com/live.m3u8
            https://same.example.com/live.m3u8
            https://other.example.com/live.m3u8
        """.trimIndent()

        val channels = M3uParser.parseM3uString(playlistId = 1L, m3u = m3u)
        assertEquals(1, channels.size)
        assertEquals(2, channels[0].resolvedSources().size)
        assertEquals("https://other.example.com/live.m3u8", channels[0].resolvedSources()[1].url)
    }

    @Test
    fun keepsPlainUrlListAsSeparateChannels() = runBlocking {
        val m3u = """
            https://plain1.example.com/live.m3u8
            https://plain2.example.com/live.m3u8
        """.trimIndent()

        val channels = M3uParser.parseM3uString(playlistId = 1L, m3u = m3u)
        assertEquals(2, channels.size)
        assertEquals("https://plain1.example.com/live.m3u8", channels[0].url)
        assertEquals("https://plain2.example.com/live.m3u8", channels[1].url)
    }
}
