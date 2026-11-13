package com.dinsplayer.app.core.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import com.dinsplayer.app.core.network.FallbackDns
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer

/** Hasil pembuatan MediaSource siap pakai beserta format dan URL teresolusi. */
data class BuiltMediaSource(
    val mediaSource: MediaSource,
    val streamFormat: String,
    val resolvedUrl: String,
)

/** Hasil sanitasi durasi buffer agar selalu memenuhi invarian DefaultLoadControl. */
internal data class BufferDurationsMs(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
)

/**
 * Shared factory komponen ExoPlayer untuk [Media3Manager] (single) dan
 * [MultiStreamManager] (multi-slot). Tidak menyimpan state sesi.
 */
@androidx.media3.common.util.UnstableApi
object StreamPlaybackFactory {

    internal fun sanitizeBufferDurations(
        minBufferMs: Int,
        maxBufferMs: Int,
        bufferForPlaybackMs: Int,
        bufferForPlaybackAfterRebufferMs: Int,
    ): BufferDurationsMs {
        val safePlaybackMs = bufferForPlaybackMs.coerceAtLeast(0)
        val safeRebufferMs = bufferForPlaybackAfterRebufferMs.coerceAtLeast(0)
        val safeMinMs = maxOf(minBufferMs, safePlaybackMs, safeRebufferMs)
        val safeMaxMs = maxOf(maxBufferMs, safeMinMs)
        return BufferDurationsMs(
            minBufferMs = safeMinMs,
            maxBufferMs = safeMaxMs,
            bufferForPlaybackMs = safePlaybackMs,
            bufferForPlaybackAfterRebufferMs = safeRebufferMs,
        )
    }

    // TLS 1.2 + modern cipher fallback agar Android 8+ dapat mengakses CDN modern
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

    private val apiResolveClient: okhttp3.OkHttpClient by lazy {
        FallbackDns.configure(
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
        ).build()
    }

    fun createBaseOkHttpClient(): okhttp3.OkHttpClient =
        FallbackDns.configure(
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectionSpecs(tlsSpecs)
        ).build()

    fun createDrmProvider(okHttpClient: okhttp3.OkHttpClient): androidx.media3.exoplayer.drm.DrmSessionManagerProvider {
        val defaultHttpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)
        val defaultDrmProvider = androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider()
        defaultDrmProvider.setDrmHttpDataSourceFactory(defaultHttpFactory)

        return object : androidx.media3.exoplayer.drm.DrmSessionManagerProvider {
            private val lock = Any()
            // Keyed by license URI agar kunci ClearKey tiap channel tidak tertukar
            private val cache = HashMap<String, androidx.media3.exoplayer.drm.DrmSessionManager>()

            override fun get(mediaItem: MediaItem): androidx.media3.exoplayer.drm.DrmSessionManager {
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
                return defaultDrmProvider.get(mediaItem)
            }
        }
    }

    fun createDefaultLoadControl(
        maxBufferMs: Int,
        targetBufferBytes: Int,
        minBufferMs: Int = (maxBufferMs / 3).coerceAtLeast(5_000),
        bufferForPlaybackMs: Int = 2_500,
        bufferForPlaybackAfterRebufferMs: Int = 5_000,
    ): androidx.media3.exoplayer.DefaultLoadControl {
        val durations = sanitizeBufferDurations(
            minBufferMs = minBufferMs,
            maxBufferMs = maxBufferMs,
            bufferForPlaybackMs = bufferForPlaybackMs,
            bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMs,
        )
        return androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                durations.minBufferMs,
                durations.maxBufferMs,
                durations.bufferForPlaybackMs,
                durations.bufferForPlaybackAfterRebufferMs,
            )
            .setTargetBufferBytes(targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(10_000, true)
            .build()
    }

    fun createRenderersFactory(
        context: Context,
        audioOffsetProcessor: AudioOffsetProcessor,
    ): DefaultRenderersFactory = AudioThroughFfmpegRenderersFactory(context, audioOffsetProcessor).apply {
        setEnableDecoderFallback(true)
        forceEnableMediaCodecAsynchronousQueueing()
        // ClearKey butuh decoder non-secure agar tidak layar hitam
        setMediaCodecSelector(object : MediaCodecSelector {
            override fun getDecoderInfos(
                mimeType: String,
                requiresSecureDecoder: Boolean,
                requiresTunnelingDecoder: Boolean
            ): MutableList<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
                val all = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(
                    mimeType, false, requiresTunnelingDecoder
                )
                if (com.dinsplayer.app.data.PlayerSettings.hwDecode) return all
                val software = all.filter { it.softwareOnly }
                return if (software.isNotEmpty()) ArrayList(software) else all
            }
        })
    }

    fun createTrackSelector(
        context: Context,
        cap720p: Boolean = false,
    ): androidx.media3.exoplayer.trackselection.DefaultTrackSelector =
        androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
            val paramsBuilder = buildUponParameters().setTunnelingEnabled(false)
            if (cap720p) paramsBuilder.setMaxVideoSize(1280, 720)
            parameters = paramsBuilder.build()
        }

    fun createFastRetryPolicy(): androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy =
        object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(errorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val exception = errorInfo.exception
                if (exception is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    val code = exception.responseCode
                    if (code == 404 || code == 403 || code == 500 || code == 502 || code == 503) {
                        return if (errorInfo.errorCount <= 3) (errorInfo.errorCount * 1000).toLong()
                        else androidx.media3.common.C.TIME_UNSET
                    }
                }
                if (exception.message == LIVE_EOF_RECONNECT_MESSAGE) {
                    return (errorInfo.errorCount * 100L).coerceIn(100L, 3000L)
                }
                return super.getRetryDelayMsFor(errorInfo)
            }
            override fun getMinimumLoadableRetryCount(dataType: Int): Int = 3
        }

    /**
     * Resolve `api.php?action=direct` (302 Location atau JSON tanpa menghabiskan token),
     * lalu bangun MediaSource lengkap dengan interceptor header, auto-inject Vidio/Akamai,
     * sniffing manifest tanpa ekstensi, LiveIptvDataSource, dan DRM.
     */
    fun resolveAndBuildMediaSource(
        context: Context,
        okClient: okhttp3.OkHttpClient,
        drmProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider,
        retryPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy,
        url: String,
        userAgent: String = "",
        headers: Map<String, String> = emptyMap(),
        drmScheme: String? = null,
        drmLicenseUri: String? = null,
        manifestType: String? = null,
    ): BuiltMediaSource {
        val (resolvedUrl, resolvedScheme, resolvedLicense) = resolveApiDirectUrl(url, userAgent, headers)
        val rawScheme = (resolvedScheme ?: drmScheme)?.takeIf { it.isNotBlank() }
        val finalDrmLicense = (resolvedLicense ?: drmLicenseUri)?.takeIf { it.isNotBlank() }
        val finalDrmScheme = when {
            rawScheme != null -> rawScheme
            finalDrmLicense != null -> "clearkey"
            else -> null
        }

        // Hindari UA ExoPlayer/DinsPlayer atau kosong karena sering diblokir WAF CDN
        val defaultAndroidUa = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.60 Mobile Safari/537.36"
        val finalUa = when {
            userAgent.isNotBlank() && userAgent != "EMPTY_UA" &&
                !userAgent.contains("ExoPlayer", ignoreCase = true) &&
                !userAgent.contains("DinsPlayer", ignoreCase = true) -> userAgent
            else -> defaultAndroidUa
        }

        val requestHeaders = buildMap<String, String> {
            put("User-Agent", finalUa)
            putAll(headers)
            val lowerUrl = resolvedUrl.lowercase()
            if (lowerUrl.contains("vidio") || lowerUrl.contains("akamaized.net")) {
                if (!keys.any { it.equals("Referer", ignoreCase = true) }) put("Referer", "https://m.vidio.com/")
                if (!keys.any { it.equals("Origin", ignoreCase = true) }) put("Origin", "https://m.vidio.com")
                if (get("User-Agent").isNullOrBlank()) put("User-Agent", defaultAndroidUa)
            }
        }

        var sniffUrl = resolvedUrl
        try {
            val uri2 = Uri.parse(resolvedUrl)
            val urlParam = uri2.getQueryParameter("url")
            if (!urlParam.isNullOrBlank()) {
                val decoded = String(android.util.Base64.decode(urlParam, android.util.Base64.DEFAULT))
                if (decoded.startsWith("http")) sniffUrl = decoded
            }
        } catch (_: Exception) {}

        val hasDrm = finalDrmScheme != null && finalDrmLicense != null
        var isDash = hasDrm ||
            manifestType?.equals("mpd", ignoreCase = true) == true ||
            manifestType?.contains("dash", ignoreCase = true) == true ||
            sniffUrl.contains(".mpd", ignoreCase = true) ||
            resolvedUrl.contains(".mpd", ignoreCase = true)
        var isHls = !isDash && (
            manifestType?.equals("hls", ignoreCase = true) == true ||
            manifestType?.contains("m3u", ignoreCase = true) == true ||
            sniffUrl.contains(".m3u8", ignoreCase = true) ||
            resolvedUrl.contains(".m3u8", ignoreCase = true)
        )

        // Sniffing URL tanpa ekstensi (mis. Cloudflare Workers / reverse proxy)
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
                    } catch (_: Exception) { "" }
                    sniffResp.body?.close()

                    isDash = contentType.contains("dash", ignoreCase = true) ||
                        contentType.contains("mpd", ignoreCase = true) ||
                        (bodyPeek.trimStart().startsWith("<?xml") && bodyPeek.contains("MPD", ignoreCase = true))
                    isHls = !isDash && (
                        contentType.contains("mpegurl", ignoreCase = true) ||
                        contentType.contains("m3u", ignoreCase = true) ||
                        bodyPeek.trimStart().startsWith("#EXTM3U") ||
                        bodyPeek.trimStart().startsWith("#EXT-X-")
                    )
                } catch (e: Exception) {
                    android.util.Log.w("StreamPlaybackFactory", "Content sniff skipped: ${e.message}")
                }
            }
        }

        val formatStr = if (isDash) "DASH" else if (isHls) "HLS" else "TS/HTTP"

        // Interceptor ganda agar header ikut terkirim setelah redirect lintas-domain
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

        val finalUaHeader = requestHeaders["User-Agent"] ?: requestHeaders["user-agent"] ?: defaultAndroidUa
        val channelHttpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(channelOkClient)
            .setUserAgent(finalUaHeader)
        val defaultDataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, channelHttpFactory)

        // Progressive TS dibungkus LiveIptvDataSource agar EOF dianggap reconnect; DASH/HLS tidak
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

        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context, extractorsFactory)
            .setDataSourceFactory(wrappedDataSourceFactory)
            .setDrmSessionManagerProvider(
                if (activeDrmManager != null) androidx.media3.exoplayer.drm.DrmSessionManagerProvider { activeDrmManager }
                else drmProvider
            )
            .setLoadErrorHandlingPolicy(retryPolicy)

        val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(resolvedUrl))
        if (isDash) mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
        else if (isHls) mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)

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
        return BuiltMediaSource(mediaSource, formatStr, resolvedUrl)
    }

    private fun resolveApiDirectUrl(
        url: String,
        userAgent: String = "",
        headers: Map<String, String> = emptyMap(),
    ): Triple<String, String?, String?> {
        val uri = try { Uri.parse(url) } catch (_: Exception) { return Triple(url, null, null) }
        val action = uri.getQueryParameter("action") ?: return Triple(url, null, null)
        if (action != "direct") return Triple(url, null, null)

        return try {
            val reqBuilder = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", userAgent.ifBlank { "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36" })
            headers["Origin"]?.takeIf { it.isNotBlank() }?.let { reqBuilder.header("Origin", it) }
            headers["Referer"]?.takeIf { it.isNotBlank() }?.let { reqBuilder.header("Referer", it) }

            apiResolveClient.newCall(reqBuilder.build()).execute().use { response ->
                val code = response.code
                val location = response.header("Location")

                if (code in 300..399 && location != null) {
                    val resolvedUrl = if (location.startsWith("http")) location else {
                        val base = Uri.parse(url)
                        base.buildUpon().path(location).build().toString()
                    }
                    Triple(resolvedUrl, null, null)
                } else if (code in 200..299) {
                    val bodyStr = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
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
                    } catch (_: Exception) {}

                    if (found == null) {
                        found = Regex("""https?://[^\s"']+\.(mpd|m3u8)[^\s"']*""").find(bodyStr)?.value
                    }
                    if (found != null) Triple(found, null, null) else Triple(url, null, null)
                } else {
                    Triple(url, null, null)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("StreamPlaybackFactory", "resolveApiDirectUrl failed: ${e.message}", e)
            Triple(url, null, null)
        }
    }

    /**
     * Pemetaan error ExoPlayer ke pesan Indonesia yang mudah dipahami user.
     * [activeStreamCount] menambahkan petunjuk pengurangan layar saat decoder hardware penuh.
     */
    fun mapPlaybackError(error: PlaybackException, activeStreamCount: Int = 1): String {
        val cause = error.cause?.message ?: ""
        val httpEx = error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
        val httpCode = httpEx?.responseCode ?: 0
        val httpBody = httpEx?.responseBody?.let {
            try { String(it, Charsets.UTF_8) } catch (_: Exception) { "" }
        } ?: ""
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
        val isDrmLicenseFailed = error.errorCode == PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED
        val isDrmDisallowed = error.errorCode == PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR

        val isDecoderError = error.errorCodeName in listOf(
            "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED",
            "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES",
            "ERROR_CODE_DECODING_FAILED",
            "ERROR_CODE_DECODER_INIT_FAILED",
        )

        return when {
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
                "Gagal mendapatkan lisensi DRM. Server lisensi menolak permintaan, channel mungkin membutuhkan akun berlangganan."
            isDrmDisallowed ->
                "Konten dilindungi DRM dan tidak dapat diputar di perangkat ini."
            isDecoderError -> {
                val info = "${error.message.orEmpty()} $cause"
                val is4k = info.contains("3840") || info.contains("4096") || info.contains("2160")
                val isHevc = info.contains("hevc", ignoreCase = true) || info.contains("hvc1", ignoreCase = true)
                val isHdr = info.contains("ST2084") || info.contains("HLG", ignoreCase = true) ||
                    info.contains("BT2020", ignoreCase = true) || info.contains("10bit", ignoreCase = true)
                val exceeds = info.contains("EXCEEDS_CAPABILITIES")
                val reason = buildList {
                    if (is4k) add("resolusi 4K")
                    if (isHevc) add("codec HEVC (H.265)")
                    if (isHdr) add("HDR 10-bit")
                }
                val multiHint = if (activeStreamCount > 1) {
                    "\n\nSedang berjalan $activeStreamCount layar sekaligus. " +
                    "Kuota decoder hardware mungkin penuh. " +
                    "Tutup salah satu layar atau aktifkan batas Max 720p."
                } else ""
                if (exceeds || reason.isNotEmpty()) {
                    val what = if (reason.isNotEmpty()) reason.joinToString(", ") else "format video ini"
                    "Perangkat ini tidak mampu memutar saluran ini.\n\nSaluran memakai $what yang melebihi kemampuan chip video HP/TV Anda." +
                    multiHint
                } else {
                    "Format video atau codec tidak didukung perangkat ini. Coba saluran lain.$multiHint"
                }
            }
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
                           cause.contains("TLS", ignoreCase = true)) {
                    "Gagal koneksi SSL/TLS. Server mungkin tidak mendukung protokol keamanan perangkat ini."
                } else {
                    "Kesalahan: $cause"
                }
            else ->
                "Error: ${error.errorCodeName} (${error.errorCode})\n${error.message}\n$cause"
        }
    }

    fun buildClearKeyDrmSessionManager(licenseUri: String): androidx.media3.exoplayer.drm.DrmSessionManager? {
        val uri = licenseUri.trim()
        var jsonStr: String? = null
        if (uri.startsWith("data:application/json,")) {
            jsonStr = try {
                java.net.URLDecoder.decode(uri.substring("data:application/json,".length), "UTF-8")
            } catch (_: Exception) { null }
        } else if (uri.startsWith("data:application/json;base64,")) {
            jsonStr = try {
                val b64 = uri.substring("data:application/json;base64,".length)
                String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) { null }
        } else if (uri.startsWith("{") && uri.contains("\"keys\"")) {
            jsonStr = uri
        } else if (uri.contains(":") && !uri.startsWith("http")) {
            try {
                val pairs = uri.split(",").map { it.trim() }.filter { it.contains(":") }
                val zeroKid64 = android.util.Base64.encodeToString(
                    ByteArray(16), android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
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
                android.util.Log.e("StreamPlaybackFactory", "ClearKey direct key parse failed", e)
            }
        }
        if (jsonStr.isNullOrBlank()) return null
        return try {
            val callback = androidx.media3.exoplayer.drm.LocalMediaDrmCallback(jsonStr.toByteArray(Charsets.UTF_8))
            val defaultManager = androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(androidx.media3.common.C.CLEARKEY_UUID, androidx.media3.exoplayer.drm.FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(false)
                .build(callback)
            ClearKeyDrmSessionManager(defaultManager, jsonStr)
        } catch (e: Exception) {
            android.util.Log.e("StreamPlaybackFactory", "ClearKey DRM setup failed", e)
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
}

@androidx.media3.common.util.UnstableApi
internal class AudioThroughFfmpegRenderersFactory(
    context: Context,
    private val audioOffsetProcessor: AudioOffsetProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
        .setAudioProcessors(arrayOf<AudioProcessor>(audioOffsetProcessor))
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
            context, extensionRendererMode, mediaCodecSelector,
            enableDecoderFallback, audioSink, eventHandler, eventListener, out
        )
        out.add(0, FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
    }
}
