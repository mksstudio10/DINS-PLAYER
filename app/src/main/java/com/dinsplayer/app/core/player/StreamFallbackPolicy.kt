package com.dinsplayer.app.core.player

import androidx.media3.common.PlaybackException

internal enum class FallbackAction {
    REPREPARE_LIVE_WINDOW,
    RETRY_SAME_SOURCE,
    SWITCH_NEXT_SOURCE,
    FAIL_ALL,
}

internal object StreamFallbackPolicy {
    const val MAX_TRANSIENT_DRM_RETRIES = 2

    private val permanentDrmCodes = setOf(
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
        6007, // Permanent DRM session/operation error (matches zentaratv_lite)
    )

    fun isTransientDrmError(errorCode: Int): Boolean =
        errorCode in 6001..6010 && errorCode !in permanentDrmCodes

    fun decide(
        errorCode: Int,
        currentIndex: Int,
        totalSources: Int,
        drmRetriesSoFar: Int,
    ): FallbackAction = when {
        errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
            FallbackAction.REPREPARE_LIVE_WINDOW
        isTransientDrmError(errorCode) && drmRetriesSoFar < MAX_TRANSIENT_DRM_RETRIES ->
            FallbackAction.RETRY_SAME_SOURCE
        currentIndex + 1 < totalSources ->
            FallbackAction.SWITCH_NEXT_SOURCE
        else ->
            FallbackAction.FAIL_ALL
    }
}
