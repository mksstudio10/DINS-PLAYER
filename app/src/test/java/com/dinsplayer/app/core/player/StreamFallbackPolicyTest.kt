package com.dinsplayer.app.core.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamFallbackPolicyTest {

    @Test
    fun behindLiveWindowAlwaysReprepares() {
        val action = StreamFallbackPolicy.decide(
            errorCode = PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            currentIndex = 0,
            totalSources = 3,
            drmRetriesSoFar = 0,
        )
        assertEquals(FallbackAction.REPREPARE_LIVE_WINDOW, action)
    }

    @Test
    fun transientDrmRetriesThenFallsBackToBackupServer() {
        val firstRetry = StreamFallbackPolicy.decide(
            errorCode = PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            currentIndex = 0,
            totalSources = 2,
            drmRetriesSoFar = 0,
        )
        assertEquals(FallbackAction.RETRY_SAME_SOURCE, firstRetry)

        val secondRetry = StreamFallbackPolicy.decide(
            errorCode = PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            currentIndex = 0,
            totalSources = 2,
            drmRetriesSoFar = 1,
        )
        assertEquals(FallbackAction.RETRY_SAME_SOURCE, secondRetry)

        val fallback = StreamFallbackPolicy.decide(
            errorCode = PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            currentIndex = 0,
            totalSources = 2,
            drmRetriesSoFar = 2,
        )
        assertEquals(FallbackAction.SWITCH_NEXT_SOURCE, fallback)
    }

    @Test
    fun permanentDrmAndNetworkAndContainerErrorsSwitchImmediatelyWhenBackupExists() {
        val codes = listOf(
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            6007,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        )
        codes.forEach { code ->
            assertEquals(
                "Expected SWITCH_NEXT_SOURCE for $code",
                FallbackAction.SWITCH_NEXT_SOURCE,
                StreamFallbackPolicy.decide(code, currentIndex = 0, totalSources = 2, drmRetriesSoFar = 0),
            )
        }
    }

    @Test
    fun failsAllWhenNoMoreBackupSourcesRemain() {
        val action = StreamFallbackPolicy.decide(
            errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            currentIndex = 1,
            totalSources = 2,
            drmRetriesSoFar = 0,
        )
        assertEquals(FallbackAction.FAIL_ALL, action)
    }
}
