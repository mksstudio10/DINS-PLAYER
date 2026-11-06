@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.dinsplayer.app.core.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.dinsplayer.app.data.models.PlayerState
import com.dinsplayer.app.features.player.ChannelItem
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import com.dinsplayer.app.core.network.FallbackDns
import com.dinsplayer.app.core.network.DnsFallbackInterceptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object Media3Manager {

    private var player: ExoPlayer? = null
    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val state: StateFlow<PlayerState> = _state

    private val _currentTitle = MutableStateFlow("")
    val currentTitle: StateFlow<String> = _currentTitle

    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl

    private val _currentDrmScheme = MutableStateFlow<String?>(null)
    val currentDrmScheme: StateFlow<String?> = _currentDrmScheme

    private val _currentDrmLicenseUri = MutableStateFlow<String?>(null)
    val currentDrmLicenseUri: StateFlow<String?> = _currentDrmLicenseUri

    // Shared channel list for PlayerScreen in-player menu
    private val _activeChannels = MutableStateFlow<List<ChannelItem>>(emptyList())
    val activeChannels: StateFlow<List<ChannelItem>> = _activeChannels

    private val _activeChannelId = MutableStateFlow("")
    val activeChannelId: StateFlow<String> = _activeChannelId

    private val _streamFormat = MutableStateFlow("")
    val streamFormat: StateFlow<String> = _streamFormat

    /**
     * [currentChannelId] is optional on purpose: callers that only refresh the list
     * (e.g. LiveTvScreen after re-parsing the playlist) must not clear the channel
     * that is currently playing, or Prev/Next and the menu highlight reset to index 0.
     */
    fun setActiveChannels(channels: List<ChannelItem>, currentChannelId: String = "") {
        _activeChannels.value = channels
        if (currentChannelId.isNotBlank()) {
            _activeChannelId.value = currentChannelId
            return
        }
        if (channels.any { it.id == _activeChannelId.value }) return
        // M3uParser assigns fresh ids on every parse, so id matching alone loses the
        // playing channel on refresh. Fall back to the URL that is actually playing.
        _activeChannelId.value = channels.firstOrNull { it.url == _currentUrl.value }?.id ?: ""
    }

    fun setActiveChannelId(channelId: String) {
        _activeChannelId.value = channelId
    }

    private var baseOkHttpClient: okhttp3.OkHttpClient? = null
    private var baseDrmProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider? = null
    private var baseRetryPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy? = null
    private var appContext: android.content.Context? = null

    /** Inserted into the audio pipeline by [AudioThroughFfmpegRenderersFactory]. */
    private val audioOffsetProcessor = AudioOffsetProcessor()

    fun init(context: Context) {
        if (player != null) return
        this.appContext = context.applicationContext
        com.dinsplayer.app.data.PlayerSettings.load(context)
        audioOffsetProcessor.offsetMs =
            com.dinsplayer.app.data.PlayerSettings.audioSyncMs.coerceIn(-500L, 500L)

        // Gunakan OkHttpDataSource alih-alih DefaultHttpDataSource untuk menghindari
        // bug NullPointerException dari HttpURLConnection bawaan Android saat parsing header tertentu.
        // FallbackDns menggunakan DNS sistem terlebih dahulu, lalu otomatis beralih ke Cloudflare 1.1.1.1 jika gagal/diblokir.
        // ConnectionSpec fallback: tambahkan TLS 1.2 + cipher lama agar Android 8 (API 26) bisa terhubung
        // ke CDN modern (CloudFront, Akamai) yang menghapus dukungan TLS 1.0/1.1.
        val tlsSpecs = listOf(
            okhttp3.ConnectionSpec.MODERN_TLS,
            okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.COMPATIBLE_TLS)
                .tlsVersions(okhttp3.TlsVersion.TLS_1_2, okhttp3.TlsVersion.TLS_1_3)
                .cipherSuites(
                    okhttp3.CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                    okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                    okhttp3.CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                    okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                    okhttp3.CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
                    okhttp3.CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
                    okhttp3.CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
                    okhttp3.CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA
                )
                .build(),
            okhttp3.ConnectionSpec.CLEARTEXT
        )
        val okHttpClient = FallbackDns.configure(
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectionSpecs(tlsSpecs)
        ).build()
            
        this.baseOkHttpClient = okHttpClient
        val defaultHttpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)

        // 1. DRM Provider
        val defaultDrmProvider = androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider()
        defaultDrmProvider.setDrmHttpDataSourceFactory(defaultHttpFactory)

        val drmProvider = object : androidx.media3.exoplayer.drm.DrmSessionManagerProvider {
            private val lock = Any()
            // Keyed by license URI. The old single-slot cache returned the previous
            // channel's keys whenever a ClearKey item had no license URI, so decryption
            // failed with no useful message. Each license value gets its own manager.
            private val cache = HashMap<String, androidx.media3.exoplayer.drm.DrmSessionManager>()

            override fun get(mediaItem: androidx.media3.common.MediaItem): androidx.media3.exoplayer.drm.DrmSessionManager {
                val drmConfig = mediaItem.localConfiguration?.drmConfiguration
                val uri = if (drmConfig?.scheme == androidx.media3.common.C.CLEARKEY_UUID) {
                    drmConfig.licenseUri?.toString() ?: ""
                } else ""
                if (uri.isEmpty()) return defaultDrmProvider.get(mediaItem)

                synchronized(lock) {
                    cache[uri]?.let { return it }
                    buildClearKeyDrmSessionManager(uri)?.let { manager ->
                        cache[uri] = manager
                        return manager
                    }
                }
                // Unparseable license: let Media3 report the real DRM error instead of
                // handing over keys that belong to a different channel.
                return defaultDrmProvider.get(mediaItem)
            }
        }
        
        this.baseDrmProvider = drmProvider

        // 2. LoadControl for smoother playback on unstable/chunky TS streams & 4K high bitrate
        // maxBufferMs follows the user's "Buffer Streaming" preset; the value used to be a
        // hardcoded 50s no matter what the settings screen said.
        val maxBufferMs = when (com.dinsplayer.app.data.PlayerSettings.bufferPreset) {
            com.dinsplayer.app.data.PlayerSettings.BUFFER_16S -> 16_000
            com.dinsplayer.app.data.PlayerSettings.BUFFER_64S -> 64_000
            else -> 32_000
        }
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                (maxBufferMs / 3).coerceAtLeast(5_000), // minBufferMs: sepertiga dari max, aman untuk live window
                maxBufferMs,
                2500,  // bufferForPlaybackMs: 2.5s (mencegah starving saat awal play 4K)
                5000   // bufferForPlaybackAfterRebufferMs: 5s
            )
            .setTargetBufferBytes(128 * 1024 * 1024) // 128MB target buffer untuk video 4K bitrate tinggi (25-80 Mbps)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(10000, true) // Retain 10s back buffer dengan keyframe retention
            .build()

        // 3. Fast Retry Policy
        val fastRetryPolicy = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(errorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val exception = errorInfo.exception
                // IPTV streams sering mengembalikan 404/403 sesaat. Retry 3 kali, lalu MENYERAH
                // secara eksplisit — karena minimumRetryCount di bawah tak lagi membatasi.
                if (exception is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    val code = exception.responseCode
                    if (code == 404 || code == 403 || code == 500 || code == 502 || code == 503) {
                        return if (errorInfo.errorCount <= 3) {
                            (errorInfo.errorCount * 1000).toLong() // Backoff: 1s, 2s, 3s
                        } else {
                            androidx.media3.common.C.TIME_UNSET // TIME_UNSET = jangan retry
                        }
                    }
                }
                // EOF on a live stream is not a failure, it is the encoder restarting.
                // Never give up; grow the delay so a dead server is not hammered at 10 req/s.
                if (exception.message == LIVE_EOF_RECONNECT_MESSAGE) {
                    return (errorInfo.errorCount * 100L).coerceIn(100L, 3000L)
                }
                return super.getRetryDelayMsFor(errorInfo)
            }
            // Left at the default 3 for errors we do not special-case (DNS, timeouts).
            // The EOF-on-live branch above returns a delay unconditionally, so a live
            // stream reconnects past this count without lifting the global limit.
            override fun getMinimumLoadableRetryCount(dataType: Int): Int = 3
        }
        this.baseRetryPolicy = fastRetryPolicy

        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setTsExtractorFlags(
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )

        val renderersFactory = AudioThroughFfmpegRenderersFactory(context, audioOffsetProcessor).apply {
            setEnableDecoderFallback(true)
            // Asynchronous queueing mode memindahkan pengiriman buffer MediaCodec ke background handler terdedikasi.
            // Ini mencegah frame drop dan lag parah saat dekode video resolusi tinggi (4K 60fps).
            forceEnableMediaCodecAsynchronousQueueing()
            // Fix for ClearKey video black screen: force non-secure decoders
            setMediaCodecSelector(object : androidx.media3.exoplayer.mediacodec.MediaCodecSelector {
                override fun getDecoderInfos(
                    mimeType: String,
                    requiresSecureDecoder: Boolean,
                    requiresTunnelingDecoder: Boolean
                ): MutableList<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
                    val all = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(
                        mimeType,
                        false, // Force requiresSecureDecoder to false
                        requiresTunnelingDecoder
                    )
                    if (com.dinsplayer.app.data.PlayerSettings.hwDecode) return all
                    // Hardware decode disabled: keep only software codecs so broken vendor
                    // GPU drivers (macroblocking / green frames on some 4K panels) can be
                    // bypassed without leaving the player.
                    val software = all.filter { it.softwareOnly }
                    return if (software.isNotEmpty()) ArrayList(software) else all
                }
            })
        }

        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setTunnelingEnabled(false)
                .build()
        }

        player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build().apply {
                setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
                setHandleAudioBecomingNoisy(true)

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_IDLE && player?.playerError != null) {
                            // Abaikan STATE_IDLE jika disebabkan oleh error, biarkan onPlayerError yang mengatur UI
                            return
                        }

                        _state.value = when (playbackState) {
                            Player.STATE_IDLE -> PlayerState.Idle
                            Player.STATE_BUFFERING -> PlayerState.Loading
                            Player.STATE_READY -> if (playWhenReady) PlayerState.Playing else PlayerState.Paused
                            Player.STATE_ENDED -> {
                                val p = player
                                // Reconnect otomatis jika ini adalah siaran live yang tiba-tiba "Tamat"
                                if (p != null && (p.isCurrentMediaItemLive || p.duration == androidx.media3.common.C.TIME_UNSET)) {
                                    p.seekToDefaultPosition()
                                    p.prepare()
                                    p.play()
                                    PlayerState.Loading
                                } else {
                                    PlayerState.Idle
                                }
                            }
                            else -> _state.value
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (playbackState == Player.STATE_READY) {
                            _state.value = if (isPlaying) PlayerState.Playing else PlayerState.Paused
                        }
                    }

                    // Diagnostics: proves whether an audio track was detected and who decodes it.
                    override fun onTracksChanged(tracks: Tracks) {
                        tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }.forEach { group ->
                            val f = group.getTrackFormat(0)
                            android.util.Log.i(
                                "Media3Manager",
                                "AUDIO track: ${f.sampleMimeType} lang=${f.language} " +
                                    "supported=${group.isTrackSupported(0)} support=${group.getTrackSupport(0)} " +
                                    "selected=${group.isSelected}"
                            )
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val p = player

                        // Hanya auto-retry untuk BEHIND_LIVE_WINDOW (wajar terjadi pada siaran langsung jika koneksi lambat).
                        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                            android.util.Log.w("Media3Manager", "Live stream behind window, re-preparing...")
                            p?.seekToDefaultPosition()
                            p?.prepare()
                            p?.play()
                            return
                        }

                        val cause = error.cause?.message ?: ""
                        val httpEx = error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                        val httpCode = httpEx?.responseCode ?: 0
                        val httpBody = httpEx?.responseBody?.let {
                            try { String(it, Charsets.UTF_8) } catch (e: Exception) { "" }
                        } ?: ""

                        // Detect Akamai token-expired: 403/404 when hdntl token is in the URL
                        val failedUrl = httpEx?.dataSpec?.uri?.toString() ?: ""
                        val isAkamaiTokenExpired = (httpCode == 403 || httpCode == 404) &&
                            failedUrl.contains("hdntl=exp=", ignoreCase = true) &&
                            run {
                                val expMatch = Regex("hdntl=exp=(\\d+)").find(failedUrl)
                                val expUnix = expMatch?.groupValues?.getOrNull(1)?.toLongOrNull()
                                expUnix != null && expUnix < System.currentTimeMillis() / 1000L
                            }

                        val isDrmError = error.errorCode in 6001..6099
                        val isWidevineL1Required = isDrmError && (
                            cause.contains("ERROR_INSUFFICIENT_SECURITY", ignoreCase = true) ||
                            cause.contains("INSUFFICIENT_SECURITY", ignoreCase = true) ||
                            cause.contains("SECURITY_LEVEL", ignoreCase = true) ||
                            cause.contains("secure decoder", ignoreCase = true) ||
                            Regex("""\bL1\b""").containsMatchIn(cause)
                        )
                        val isWidevineProvisioning = error.errorCode == PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED
                        val isDrmLicenseFailed = error.errorCode == PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ||
                            error.errorCodeName == "ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED"
                        val isDrmDisallowed = error.errorCode == PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR ||
                            error.errorCodeName == "ERROR_CODE_DRM_CONTENT_ERROR"

                        val detail = when {
                            isAkamaiTokenExpired ->
                                "Token stream sudah kadaluarsa. URL stream perlu diperbarui (token Akamai expired)."
                            httpCode == 403 -> {
                                if (httpBody.contains("Content rights blocked", ignoreCase = true) ||
                                    httpBody.contains("Channel blocked", ignoreCase = true)) {
                                    "Siaran diblokir oleh server (Hak siar / Event berbayar). Coba channel lain."
                                } else {
                                    "Akses ditolak (HTTP 403 Forbidden). Token/IP/Header ditolak oleh server."
                                }
                            }
                            httpCode == 404 -> "Video atau stream tidak ditemukan di server (HTTP 404)."
                            isWidevineL1Required ->
                                "Channel ini membutuhkan Widevine L1 (keamanan hardware). Perangkat ini tidak mendukung pemutaran konten premium ini."
                            isWidevineProvisioning ->
                                "Gagal provisioning Widevine. Cek koneksi internet dan coba lagi, atau restart perangkat."
                            isDrmLicenseFailed ->
                                "Gagal mendapatkan lisensi DRM. Server lisensi menolak permintaan — channel mungkin membutuhkan akun/berlangganan."
                            isDrmDisallowed ->
                                "Konten dilindungi DRM dan tidak dapat diputar di perangkat ini."
                            error.errorCodeName == "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED" ||
                            error.errorCodeName == "ERROR_CODE_DECODER_INIT_FAILED" ->
                                "Format video atau codec tidak didukung oleh perangkat ini. Coba saluran lain."
                            error.errorCodeName == "ERROR_CODE_PARSING_CONTAINERS_UNSUPPORTED" ->
                                "Format file tidak didukung atau aliran data kosong/rusak."
                            error.errorCodeName == "ERROR_CODE_IO_NOT_FOUND" -> "Video atau URL tidak ditemukan (404)."
                            error.errorCodeName == "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED" ||
                            error.errorCodeName == "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT" ->
                                "Gagal koneksi ke server. Cek jaringan atau coba lagi nanti."
                            error.errorCodeName == "ERROR_CODE_IO_CLEARKEY_KEY_NOT_AVAILABLE" ->
                                "DRM key tidak tersedia untuk channel ini."
                            isDrmError ->
                                "Kesalahan DRM (${error.errorCodeName}). Channel ini mungkin membutuhkan perangkat dengan keamanan hardware yang lebih tinggi."
                            error.errorCodeName == "ERROR_CODE_IO_UNSPECIFIED" ->
                                if (cause.contains("unrecognized", ignoreCase = true)) {
                                    "Format file tidak dikenali. File mungkin rusak atau tidak didukung."
                                } else if (cause.contains("SSLHandshakeException", ignoreCase = true) ||
                                           cause.contains("SSL", ignoreCase = true) ||
                                           cause.contains("TLS", ignoreCase = false)) {
                                    "Gagal koneksi SSL/TLS. Server mungkin tidak mendukung protokol keamanan perangkat ini."
                                } else {
                                    "Kesalahan: $cause"
                                }
                            else ->
                                "Error: ${error.errorCodeName} (${error.errorCode})\n${error.message}\n$cause"
                        }
                        _state.value = PlayerState.Error(detail)
                        android.util.Log.e("Media3Manager", "ExoPlayer Error: [${error.errorCodeName}] ${error.message} cause=$cause httpCode=$httpCode failedUrl=${failedUrl.take(120)}", error)
                    }
                })
            }
    }

    /**
     * For api.php?action=direct URLs: make a single request with redirects disabled,
     * grab the Location header from the 302 response, and return it as the resolved URL.
     * The CDN URL is a signed, one-time-use URL — we must NOT follow the redirect ourselves
     * because that would consume the token. ExoPlayer needs to be the one fetching it.
     *
     * If the server returns a JSON body instead of a redirect, extract the URL from JSON.
     */
    private fun resolveApiDirectUrl(
        url: String,
        userAgent: String = "",
        headers: Map<String, String> = emptyMap(),
    ): Triple<String, String?, String?> {
        val uri = try { android.net.Uri.parse(url) } catch (e: Exception) { return Triple(url, null, null) }
        val action = uri.getQueryParameter("action") ?: return Triple(url, null, null)
        if (action != "direct") return Triple(url, null, null)

        android.util.Log.i("Media3Manager", "API direct URL detected, resolving: $url")

        return try {
            val client = FallbackDns.configure(
                okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
            ).build()

            val reqBuilder = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", userAgent.ifBlank { "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36" })
            headers["Origin"]?.takeIf { it.isNotBlank() }?.let { reqBuilder.header("Origin", it) }
            headers["Referer"]?.takeIf { it.isNotBlank() }?.let { reqBuilder.header("Referer", it) }

            val response = client.newCall(reqBuilder.build()).execute()
            val code = response.code
            val location = response.header("Location")

            android.util.Log.i("Media3Manager", "API response code=$code location=$location")

            if (code in 300..399 && location != null) {
                // 302 redirect — Location header is the CDN URL. Use it directly.
                val resolvedUrl = if (location.startsWith("http")) {
                    location
                } else {
                    // Relative redirect
                    val base = android.net.Uri.parse(url)
                    base.buildUpon().path(location).build().toString()
                }
                response.body?.close()
                android.util.Log.i("Media3Manager", "Resolved via redirect: $resolvedUrl")
                Triple(resolvedUrl, null, null)
            } else if (code in 200..299) {
                // Server returned a body (JSON) instead of redirect
                val bodyStr = try { response.body?.string() ?: "" } catch (e: Exception) { "" }
                response.body?.close()

                android.util.Log.d("Media3Manager", "API body (first 300 chars): ${bodyStr.take(300)}")

                var found: String? = null
                try {
                    val j = org.json.JSONObject(bodyStr)
                    for (k in listOf("url", "file", "source", "src", "playback_url")) {
                        val v = j.optString(k, "")
                        if (v.startsWith("http")) { found = v; break }
                    }
                    if (found == null) {
                        val d = j.optJSONObject("data")
                        if (d != null) for (k in listOf("url", "file", "source", "src")) {
                            val v = d.optString(k, "")
                            if (v.startsWith("http")) { found = v; break }
                        }
                    }
                } catch (e: Exception) { /* not JSON */ }

                if (found == null) {
                    found = Regex("""https?://[^\s"']+\.(mpd|m3u8)[^\s"']*""").find(bodyStr)?.value
                }

                if (found != null) {
                    android.util.Log.i("Media3Manager", "Resolved from body: $found")
                    Triple(found, null, null)
                } else {
                    android.util.Log.w("Media3Manager", "No stream URL in API body, using original URL")
                    Triple(url, null, null)
                }
            } else {
                response.body?.close()
                android.util.Log.e("Media3Manager", "API returned HTTP $code, using original URL")
                Triple(url, null, null)
            }
        } catch (e: Exception) {
            android.util.Log.e("Media3Manager", "resolveApiDirectUrl failed: ${e.message}", e)
            Triple(url, null, null)
        }
    }

    private val playSequence = java.util.concurrent.atomic.AtomicLong(0L)

    fun play(
        url: String,
        title: String = "",
        userAgent: String = "",
        headers: Map<String, String> = emptyMap(),
        drmScheme: String? = null,
        drmLicenseUri: String? = null,
        manifestType: String? = null
    ) {
        val p = player ?: return
        val currentSeq = playSequence.incrementAndGet()
        _state.value = PlayerState.Loading
        _currentTitle.value = title
        _currentUrl.value = url
        _currentDrmScheme.value = drmScheme
        _currentDrmLicenseUri.value = drmLicenseUri

        android.util.Log.i("Media3Manager", "play() called (#$currentSeq): url=$url drmScheme=$drmScheme drmLicenseUri=${drmLicenseUri?.take(60)} manifestType=$manifestType headers=$headers")

        Thread {
            try {
                val (resolvedUrl, resolvedScheme, resolvedLicense) = resolveApiDirectUrl(url, userAgent, headers)
                if (currentSeq != playSequence.get()) return@Thread
                val rawScheme = (resolvedScheme ?: drmScheme)?.takeIf { it.isNotBlank() }
                val finalDrmLicense = (resolvedLicense ?: drmLicenseUri)?.takeIf { it.isNotBlank() }
                val finalDrmScheme = when {
                    rawScheme != null -> rawScheme
                    finalDrmLicense != null -> "clearkey"
                    else -> null
                }

                android.util.Log.i("Media3Manager", "Resolved: url=$resolvedUrl finalDrmScheme=$finalDrmScheme finalDrmLicense=${finalDrmLicense?.take(60)}")

                val okClient = baseOkHttpClient ?: throw IllegalStateException("baseOkHttpClient is null")
                val drmProv = baseDrmProvider ?: throw IllegalStateException("baseDrmProvider is null")
                val retryPol = baseRetryPolicy ?: throw IllegalStateException("baseRetryPolicy is null")
                // Hindari User-Agent yang mengandung 'ExoPlayer' atau 'DinsPlayer' karena diblokir (403/404) oleh CDN Akamai / Cloudflare WAF.
                // Jika userAgent kosong atau "EMPTY_UA" (Default), gunakan browser User-Agent agar tidak bocor 'okhttp/x.x.x' yang sering diblokir CDN.
                val defaultAndroidUa = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.60 Mobile Safari/537.36"
                val finalUa = when {
                    userAgent.isNotBlank() && userAgent != "EMPTY_UA" && !userAgent.contains("ExoPlayer", ignoreCase = true) && !userAgent.contains("DinsPlayer", ignoreCase = true) -> userAgent
                    else -> defaultAndroidUa
                }

                // Build the full header map that will be injected via OkHttp Interceptor.
                // Using an Interceptor (not setDefaultRequestProperties) guarantees headers
                // reach every segment request, including those fetched after cross-domain redirects
                // (e.g. from vidio123.mikrofast.web.id → etslive.akamaized.net).
                val requestHeaders = buildMap<String, String> {
                    put("User-Agent", finalUa)
                    putAll(headers)

                    // Auto-inject Vidio/Akamai headers if not already present
                    val lowerUrl = resolvedUrl.lowercase()
                    if (lowerUrl.contains("vidio") || lowerUrl.contains("akamaized.net")) {
                        if (!keys.any { it.equals("Referer", ignoreCase = true) }) {
                            put("Referer", "https://m.vidio.com/")
                        }
                        if (!keys.any { it.equals("Origin", ignoreCase = true) }) {
                            put("Origin", "https://m.vidio.com")
                        }
                        if (!keys.any { it.equals("User-Agent", ignoreCase = true) } || get("User-Agent").isNullOrBlank()) {
                            put("User-Agent", defaultAndroidUa)
                        }
                    }
                }

                // Detect stream format early so LiveIptvDataSource wrapping can be skipped
                // for DASH/HLS (ExoPlayer manages those connections internally).
                var sniffUrl = resolvedUrl
                try {
                    val uri2 = android.net.Uri.parse(resolvedUrl)
                    val urlParam = uri2.getQueryParameter("url")
                    if (!urlParam.isNullOrBlank()) {
                        val decoded = String(android.util.Base64.decode(urlParam, android.util.Base64.DEFAULT))
                        if (decoded.startsWith("http")) sniffUrl = decoded
                    }
                } catch (e: Exception) { /* ignore */ }

                val hasDrm = finalDrmScheme != null && finalDrmLicense != null
                var isDash = hasDrm ||
                             manifestType?.equals("mpd", ignoreCase = true) == true ||
                             manifestType?.contains("dash", ignoreCase = true) == true ||
                             sniffUrl.contains(".mpd", ignoreCase = true) || resolvedUrl.contains(".mpd", ignoreCase = true)
                var isHls = !isDash && (manifestType?.equals("hls", ignoreCase = true) == true ||
                             manifestType?.contains("m3u", ignoreCase = true) == true ||
                             sniffUrl.contains(".m3u8", ignoreCase = true) || resolvedUrl.contains(".m3u8", ignoreCase = true))

                // Content sniffing for extensionless URLs (Cloudflare Workers, reverse-proxy, etc.)
                // These often serve HLS/DASH manifests but have no hint in the URL itself.
                if (!hasDrm && !isDash && !isHls && manifestType.isNullOrBlank()) {
                    val lastSegment = resolvedUrl.substringAfterLast("/")
                    val hasNoExt = !lastSegment.contains(".") || lastSegment.substringAfterLast(".").length > 6
                    if (hasNoExt) {
                        try {
                            val sniffClient = okClient.newBuilder()
                                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                                .followRedirects(true)
                                .followSslRedirects(true)
                                .build()
                            val sniffReqBuilder = okhttp3.Request.Builder()
                                .url(resolvedUrl)
                                .addHeader("Range", "bytes=0-511")
                            requestHeaders.forEach { (k, v) -> sniffReqBuilder.header(k, v) }
                            val sniffResp = sniffClient.newCall(sniffReqBuilder.build()).execute()
                            val contentType = sniffResp.header("Content-Type") ?: ""
                            val bodyPeek = try {
                                sniffResp.body?.source()?.let { src ->
                                    src.request(512)
                                    src.buffer.snapshot().utf8()
                                } ?: ""
                            } catch (e: Exception) { "" }
                            sniffResp.body?.close()

                            android.util.Log.d("Media3Manager", "Sniff ct=$contentType body=${bodyPeek.take(80)}")

                            isDash = contentType.contains("dash", ignoreCase = true) ||
                                     contentType.contains("mpd", ignoreCase = true) ||
                                     (bodyPeek.trimStart().startsWith("<?xml") && bodyPeek.contains("MPD", ignoreCase = true))
                            isHls = !isDash && (
                                     contentType.contains("mpegurl", ignoreCase = true) ||
                                     contentType.contains("m3u", ignoreCase = true) ||
                                     bodyPeek.trimStart().startsWith("#EXTM3U") ||
                                     bodyPeek.trimStart().startsWith("#EXT-X-"))
                        } catch (e: Exception) {
                            android.util.Log.w("Media3Manager", "Content sniff skipped: ${e.message}")
                        }
                    }
                }

                val formatStr = if (isDash) "DASH" else if (isHls) "HLS" else "TS/HTTP"
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    _streamFormat.value = formatStr
                }

                // Double-layer header injection for maximum reliability:
                // - addInterceptor (application): sets headers before first request
                // - addNetworkInterceptor (network): re-applies on EVERY actual TCP call,
                //   including after OkHttp follows cross-domain redirects to akamaized.net.
                val headerInjector: (okhttp3.Interceptor.Chain) -> okhttp3.Response = { chain ->
                    val modified = chain.request().newBuilder().apply {
                        requestHeaders.forEach { (k, v) -> header(k, v) }
                    }.build()
                    chain.proceed(modified)
                }
                val channelOkClient = okClient.newBuilder()
                    .addInterceptor(headerInjector)
                    .addNetworkInterceptor(headerInjector)
                    .build()

                val finalUaHeader = requestHeaders["User-Agent"]
                    ?: requestHeaders["user-agent"]
                    ?: defaultAndroidUa
                val channelHttpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(channelOkClient)
                    .setUserAgent(finalUaHeader)

                val pContext = appContext ?: throw IllegalStateException("appContext is null")
                val defaultDataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(pContext, channelHttpFactory)

                // LiveIptvDataSource only applies to Xtream-style progressive streams.
                // For DASH/HLS, ExoPlayer handles reconnection internally — wrapping those
                // with LiveIptvDataSource can interfere with MPD refresh and segment sequencing.
                val wrappedDataSourceFactory = androidx.media3.datasource.DataSource.Factory {
                    val upstream = defaultDataSourceFactory.createDataSource()
                    if (isDash || isHls) upstream else LiveIptvDataSource(upstream)
                }

                val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
                    .setTsExtractorFlags(
                        androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                        androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                    )

                val isClearKey = finalDrmLicense != null && (finalDrmScheme == null || finalDrmScheme.equals("clearkey", ignoreCase = true))
                val activeDrmManager = if (isClearKey) buildClearKeyDrmSessionManager(finalDrmLicense!!) else null

                val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(pContext, extractorsFactory)
                    .setDataSourceFactory(wrappedDataSourceFactory)
                    .setDrmSessionManagerProvider(if (activeDrmManager != null) {
                        androidx.media3.exoplayer.drm.DrmSessionManagerProvider { activeDrmManager }
                    } else {
                        drmProv
                    })
                    .setLoadErrorHandlingPolicy(retryPol)

                val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(resolvedUrl))

                if (isDash) {
                    mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
                } else if (isHls) {
                    mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                }

                if (finalDrmLicense != null && finalDrmScheme != null) {
                    val uuid = when (finalDrmScheme.lowercase()) {
                        "widevine" -> androidx.media3.common.C.WIDEVINE_UUID
                        "playready" -> androidx.media3.common.C.PLAYREADY_UUID
                        else -> androidx.media3.common.C.CLEARKEY_UUID
                    }
                    val drmBuilder = MediaItem.DrmConfiguration.Builder(uuid)
                        .setLicenseUri(finalDrmLicense)
                        .setMultiSession(false)
                        .setForceDefaultLicenseUri(true)

                    if (!finalDrmLicense.startsWith("data:")) {
                        drmBuilder.setLicenseRequestHeaders(requestHeaders)
                    }

                    mediaItemBuilder.setDrmConfiguration(drmBuilder.build())
                }
                
                val mediaSource = mediaSourceFactory.createMediaSource(mediaItemBuilder.build())

                if (currentSeq != playSequence.get()) return@Thread

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (currentSeq != playSequence.get()) return@post
                    android.util.Log.i("Media3Manager", "Setting MediaSource and preparing player (#$currentSeq)")
                    p.setMediaSource(mediaSource)
                    p.prepare()
                    p.playWhenReady = true
                }
            } catch (e: Exception) {
                if (currentSeq != playSequence.get()) return@Thread
                android.util.Log.e("Media3Manager", "Thread failed during play() setup (#$currentSeq)!", e)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (currentSeq != playSequence.get()) return@post
                    _state.value = PlayerState.Error("Gagal mempersiapkan channel: ${e.message}")
                }
            }
        }.start()
    }

    fun pause()  { player?.pause() }
    fun resume() { player?.play() }

    fun stop() {
        player?.let {
            it.stop()
            // seekTo(0) on a live stream lands outside the moving live window and
            // re-prepare then fails with BEHIND_LIVE_WINDOW. Default position handles both.
            it.seekToDefaultPosition()
        }
        // Keep the URL/title around and the player's retry path will re-prepare a file
        // that may already be deleted from disk. Clear them so Idle really means idle.
        _currentUrl.value = ""
        _currentTitle.value = ""
        _state.value = PlayerState.Idle
    }

    fun getPlayer(): ExoPlayer? = player

    fun launchPlayer(context: android.content.Context) {
        val intent = android.content.Intent(context, com.dinsplayer.app.features.player.PlayerActivity::class.java)
        // Gunakan NEW_TASK | SINGLE_TOP agar tidak membuat double activity jika sudah jalan
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)
    }

    fun setAudioSyncMs(offsetMs: Long) {
        audioOffsetProcessor.offsetMs = offsetMs.coerceIn(-500L, 500L)
        // The processor recomputes its target every queueInput() call, so a live slider drag
        // converges on the new offset without re-preparing the stream.
    }

    private fun buildClearKeyDrmSessionManager(licenseUri: String): androidx.media3.exoplayer.drm.DrmSessionManager? {
        val uri = licenseUri.trim()
        var jsonStr: String? = null
        if (uri.startsWith("data:application/json,")) {
            jsonStr = try {
                java.net.URLDecoder.decode(uri.substring("data:application/json,".length), "UTF-8")
            } catch (e: Exception) { null }
        } else if (uri.startsWith("data:application/json;base64,")) {
            jsonStr = try {
                val b64 = uri.substring("data:application/json;base64,".length)
                String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) { null }
        } else if (uri.startsWith("{") && uri.contains("\"keys\"")) {
            jsonStr = uri
        } else if (uri.contains(":") && !uri.startsWith("http")) {
            try {
                val pairs = uri.split(",").map { it.trim() }.filter { it.contains(":") }
                val zeroKid64 = android.util.Base64.encodeToString(
                    ByteArray(16),
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                )
                val keysJsonList = mutableListOf<String>()
                for (pair in pairs) {
                    val kid = pair.substringBefore(":").trim().trim('"').replace("-", "")
                    val k = pair.substringAfter(":").trim().trim('"').replace("-", "")
                    val kidBytes = hexToBytes(kid)
                    val kBytes = hexToBytes(k)
                    if (kidBytes.isNotEmpty() && kBytes.isNotEmpty()) {
                        val kid64 = android.util.Base64.encodeToString(kidBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
                        val k64 = android.util.Base64.encodeToString(kBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
                        keysJsonList.add("""{"kty":"oct","k":"$k64","kid":"$kid64"}""")
                        keysJsonList.add("""{"kty":"oct","k":"$k64","kid":"$zeroKid64"}""")
                    }
                }
                if (keysJsonList.isNotEmpty()) {
                    jsonStr = """{"keys":[${keysJsonList.joinToString(",")}],"type":"temporary"}"""
                }
            } catch (e: Exception) {
                android.util.Log.e("Media3Manager", "ClearKey direct key parse failed", e)
            }
        }

        if (jsonStr.isNullOrBlank()) return null

        return try {
            android.util.Log.d("Media3Manager", "Building ClearKey DRM session manager with JSON: $jsonStr")
            val callback = androidx.media3.exoplayer.drm.LocalMediaDrmCallback(jsonStr.toByteArray(Charsets.UTF_8))
            val defaultManager = androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(androidx.media3.common.C.CLEARKEY_UUID, androidx.media3.exoplayer.drm.FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(false)
                .build(callback)
            ClearKeyDrmSessionManager(defaultManager, jsonStr)
        } catch (e: Exception) {
            android.util.Log.e("Media3Manager", "ClearKey DRM setup failed", e)
            null
        }
    }

    private fun hexToBytes(s: String): ByteArray {
        val clean = s.replace("-", "").trim()
        val len = clean.length
        if (len % 2 != 0) return ByteArray(0)
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len - 1) {
            val high = Character.digit(clean[i], 16)
            val low = Character.digit(clean[i + 1], 16)
            if (high == -1 || low == -1) return ByteArray(0)
            data[i / 2] = ((high shl 4) + low).toByte()
            i += 2
        }
        return data
    }

    fun release() {
        player?.release()
        player = null
        baseOkHttpClient = null
        baseDrmProvider = null
        baseRetryPolicy = null
        appContext = null
        _state.value = PlayerState.Idle
    }
}

/**
 * Audio through FFmpeg, video through MediaCodec.
 *
 * nextlib's [NextRenderersFactory] inserts its renderer into BOTH lists, so MODE_PREFER also makes
 * FfmpegVideoRenderer win. Software video decode drops the container pixel aspect ratio: this
 * channel is 720x576 SAR 16:11 (DAR 20:11), but FFmpeg reports it as 720/576 = 1.25:1.
 *
 * Overriding only [buildAudioRenderers] and inserting FfmpegAudioRenderer at the head of the audio
 * list keeps hardware video (correct SAR) while MP2/MP3/AC3 — which many Android TV SoCs claim to
 * support via MediaCodec but decode to silence — always run through FFmpeg.
 */
@androidx.media3.common.util.UnstableApi
private class AudioThroughFfmpegRenderersFactory(
    context: Context,
    private val audioOffsetProcessor: AudioOffsetProcessor,
) : DefaultRenderersFactory(context) {

    /**
     * Supplies the sink that carries [audioOffsetProcessor]. Without this override the
     * processor would be constructed and then never inserted into the audio pipeline.
     */
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
        .setAudioProcessors(arrayOf<androidx.media3.common.audio.AudioProcessor>(audioOffsetProcessor))
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .build()

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
        // Insert FFmpeg Audio Renderer at the front so it's prioritized for formats like AC3/MP2
        out.add(0, FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
    }
}
