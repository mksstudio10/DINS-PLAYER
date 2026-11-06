package com.dinsplayer.app.core.player

import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.extractor.mp4.PsshAtomUtil

/**
 * ClearKey DASH fix.
 *
 * Transvision/cubmu manifests declare `<ContentProtection>` only under the Widevine scheme
 * (`urn:uuid:edef8ba9-...`) and carry no `pssh` element; the actual KID lives solely in the init
 * segment `tenc` box (16 zero bytes). The DASH parser therefore builds [DrmInitData] containing a
 * single Widevine [DrmInitData.SchemeData] with `data == null`.
 *
 * [DefaultDrmSessionManager.acquireSession] then looks for a ClearKey scheme data — either an exact
 * `e2719d58-...` match or the Common PSSH `1077efecc0b24d02ace33c1e52e2fb4b` (which is what ClearKey
 * maps onto when the manifest does have a `default_KID`). Neither is present, so it returns an
 * [androidx.media3.exoplayer.drm.ErrorStateDrmSession] with
 * `ERROR_CODE_DRM_SCHEME_UNSUPPORTED` and drops every track.
 *
 * Symptom: the manifest parses (quality/resolution list appears) but nothing ever renders — no
 * picture, no audio, no error surfaced to the UI.
 *
 * This wrapper injects a Common-PSSH scheme data entry carrying the KID(s) taken from the inline
 * ClearKey JSON, so the ClearKey session is acquired and keys are looked up by the KID the stream
 * actually reports.
 *
 * ponytail: the injected pssh atom only needs to be non-null — the ClearKey CDM ignores its contents
 * and returns the whole local key set. If a feed ever needs real DRM init data, read the KID from
 * the init segment instead of the playlist.
 */
@UnstableApi
class ClearKeyDrmSessionManager(
    private val delegate: DefaultDrmSessionManager,
    clearKeyJson: String,
) : DrmSessionManager by delegate {

    private val keyIds: List<ByteArray> = parseKeyIds(clearKeyJson)

    override fun acquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format,
    ): DrmSession? {
        val original = format.drmInitData
        android.util.Log.d("ClearKeyDrm", "acquireSession mime=${format.sampleMimeType} hasOriginalDrm=${original != null} keyIdsCount=${keyIds.size}")
        // No DRM init data means this track is unencrypted — skip DRM session entirely.
        if (original == null) return null
        if (hasClearKeySchemeData(original)) {
            return delegate.acquireSession(eventDispatcher, format)
        }
        return delegate.acquireSession(eventDispatcher, format.withInjectedSchemeData(original))
    }

    override fun preacquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format,
    ): DrmSessionManager.DrmSessionReference {
        val original = format.drmInitData
        // No DRM init data means this track is unencrypted — return a no-op reference.
        if (original == null) return DrmSessionManager.DrmSessionReference.EMPTY
        if (hasClearKeySchemeData(original)) {
            return delegate.preacquireSession(eventDispatcher, format)
        }
        return delegate.preacquireSession(eventDispatcher, format.withInjectedSchemeData(original))
    }

    override fun prepare() {
        android.util.Log.d("ClearKeyDrm", "prepare() called")
        delegate.prepare()
    }

    override fun release() {
        android.util.Log.d("ClearKeyDrm", "release() called")
        delegate.release()
    }

    override fun getCryptoType(format: Format): Int {
        // A track with no drmInitData is unencrypted — return NONE regardless of whether
        // keyIds are configured. Returning FRAMEWORK here caused ExoPlayer to attach a
        // crypto object to the clear track's MediaCodec, which then threw CryptoException
        // wrapped as ERROR_CODE_FAILED_RUNTIME_CHECK (1004).
        val original = format.drmInitData
        if (original == null) return C.CRYPTO_TYPE_NONE
        return C.CRYPTO_TYPE_FRAMEWORK
    }

    private fun hasClearKeySchemeData(drmInitData: DrmInitData): Boolean {
        for (i in 0 until drmInitData.schemeDataCount) {
            val sd = drmInitData.get(i)
            // Must have actual PSSH atom data, not empty or null
            if ((C.CLEARKEY_UUID == sd.uuid || C.COMMON_PSSH_UUID == sd.uuid) && sd.hasData()) return true
        }
        return false
    }

    private fun Format.withInjectedSchemeData(original: DrmInitData?): Format {
        val mimeType = if (MimeTypes.isVideo(sampleMimeType)) MimeTypes.VIDEO_MP4 else MimeTypes.AUDIO_MP4
        val originalCount = original?.schemeDataCount ?: 0
        val merged = ArrayList<DrmInitData.SchemeData>(originalCount + keyIds.size * 2 + 2)
        if (original != null) {
            for (i in 0 until original.schemeDataCount) merged.add(original.get(i))
        }

        val keyUuids = keyIds.mapNotNull { it.toUuidOrNull() }.toTypedArray()
        if (keyUuids.isNotEmpty()) {
            val commonAtom = PsshAtomUtil.buildPsshAtom(C.COMMON_PSSH_UUID, keyUuids, null)
            merged.add(DrmInitData.SchemeData(C.COMMON_PSSH_UUID, mimeType, commonAtom))
            val clearKeyAtom = PsshAtomUtil.buildPsshAtom(C.CLEARKEY_UUID, keyUuids, null)
            merged.add(DrmInitData.SchemeData(C.CLEARKEY_UUID, mimeType, clearKeyAtom))
        }

        for (kid in keyIds) {
            val kidUuid = kid.toUuidOrNull() ?: continue
            val atom = PsshAtomUtil.buildPsshAtom(C.COMMON_PSSH_UUID, arrayOf(kidUuid), null)
            merged.add(DrmInitData.SchemeData(C.COMMON_PSSH_UUID, mimeType, atom))
            val ckAtom = PsshAtomUtil.buildPsshAtom(C.CLEARKEY_UUID, arrayOf(kidUuid), null)
            merged.add(DrmInitData.SchemeData(C.CLEARKEY_UUID, mimeType, ckAtom))
        }
        val schemeType = original?.schemeType ?: C.CENC_TYPE_cenc
        return buildUpon().setDrmInitData(DrmInitData(schemeType, merged)).build()
    }

    private fun ByteArray.toUuidOrNull(): java.util.UUID? {
        if (size != 16) return null
        val buf = java.nio.ByteBuffer.wrap(this)
        val msb = buf.getLong()
        val lsb = buf.getLong()
        return java.util.UUID(msb, lsb)
    }

    private companion object {
        /** Pulls every `"kid":"<base64url>"` out of the ClearKey JSON produced by M3uParser. */
        fun parseKeyIds(clearKeyJson: String): List<ByteArray> {
            val result = ArrayList<ByteArray>()
            for (match in Regex("\"kid\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"").findAll(clearKeyJson)) {
                val raw = try {
                    android.util.Base64.decode(
                        match.groupValues[1],
                        android.util.Base64.URL_SAFE or
                            android.util.Base64.NO_PADDING or
                            android.util.Base64.NO_WRAP
                    )
                } catch (e: IllegalArgumentException) {
                    null
                }
                if (raw != null && raw.isNotEmpty()) result.add(raw)
            }
            return result
        }
    }
}
