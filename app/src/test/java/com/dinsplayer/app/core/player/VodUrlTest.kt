package com.dinsplayer.app.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the single rule shared by reconnect behaviour and the LIVE/VOD menu label.
 * Old bug: LiveIptvDataSource matched URL substrings (`live.php`, `play/`, `get.php`)
 * that Xtream live URLs never contain, so live reconnect was silently dead.
 */
class VodUrlTest {

    @Test
    fun xtreamLiveUrlIsNotVod() {
        assertFalse(isVodUrl("http://host:8080/live/user/pass/1234.ts"))
        assertFalse(isVodUrl("http://host:8080/live/user/pass/1234.m3u8"))
    }

    @Test
    fun extensionlessProgressiveStreamIsNotVod() {
        assertFalse(isVodUrl("http://host:8080/live/user/pass/1234"))
    }

    @Test
    fun movieContainersAreVod() {
        assertTrue(isVodUrl("http://host:8080/movie/user/pass/99.mp4"))
        assertTrue(isVodUrl("http://host:8080/movie/user/pass/99.mkv"))
    }

    @Test
    fun queryStringAndFragmentDoNotChangeClassification() {
        assertTrue(isVodUrl("http://host/movie/u/p/99.MKV?token=abc"))
        assertFalse(isVodUrl("http://host/live/u/p/7.ts?token=abc"))
        assertFalse(isVodUrl("http://host/live/u/p/7.ts#t=10"))
    }
}
