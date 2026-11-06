package com.dinsplayer.app.core.network

import android.util.Log
import com.dinsplayer.app.data.models.Channel
import com.dinsplayer.app.data.models.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object M3uParser {
    private const val TAG = "M3uParser"

    private val PATTERN_TVG_LOGO  = java.util.regex.Pattern.compile("""tvg-logo=["']([^"']*)["']""")
    private val PATTERN_GROUP     = java.util.regex.Pattern.compile("""group-title=["']([^"']*)["']""")
    private val PATTERN_TVG_ID    = java.util.regex.Pattern.compile("""tvg-id=["']([^"']*)["']""")
    private val PATTERN_TVG_NAME  = java.util.regex.Pattern.compile("""tvg-name=["']([^"']*)["']""")

    private val client = FallbackDns.configure(
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
    ).build()

    private val IPTV_USER_AGENTS = listOf(
        // Browser UAs first: CDN/WAF (Akamai, Cloudflare) blocks non-browser UAs
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.60 Mobile Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
        // IPTV app UAs: some servers whitelist these
        "TiviMate",
        "IPTV Smarters Pro",
        "VLC/3.0.21 LibVLC/3.0.21",
        // Legacy / no-UA fallback
        "okhttp/4.9.0",
        ""
    )

    private suspend fun fetchWithFallback(url: String, specificUa: String? = null): Pair<String, String>? = withContext(Dispatchers.IO) {
        val agentsToTry = if (!specificUa.isNullOrBlank()) listOf(specificUa) + IPTV_USER_AGENTS.filter { it != specificUa } else IPTV_USER_AGENTS

        for (ua in agentsToTry) {
            ensureActive()
            try {
                val requestBuilder = Request.Builder().url(url)
                if (ua.isNotEmpty()) requestBuilder.header("User-Agent", ua)

                val response = suspendCancellableCoroutine<okhttp3.Response> { continuation ->
                    val call = client.newCall(requestBuilder.build())
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : okhttp3.Callback {
                        override fun onResponse(call: okhttp3.Call, resp: okhttp3.Response) {
                            continuation.resume(resp)
                        }
                        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                            if (continuation.isCancelled) return
                            continuation.resumeWith(Result.failure(e))
                        }
                    })
                }

                response.use { resp ->
                    if (resp.isSuccessful) {
                        val contentLength = resp.body?.contentLength() ?: -1L
                        if (contentLength > 50_000_000L) {
                            Log.w(TAG, "Playlist too large ($contentLength bytes), skipping UA: $ua")
                            return@use
                        }
                        val body = resp.body?.string() ?: ""
                        if (isPlaylistContent(body)) {
                            return@withContext Pair(body, ua)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
        }
        return@withContext null
    }

    private fun isPlaylistContent(body: String): Boolean {
        val cleanBody = body.removePrefix("\uFEFF")
        val firstNonBlank = cleanBody.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return false
        if (firstNonBlank.startsWith("#EXTM3U") || firstNonBlank.startsWith("#EXTINF:")) return true
        val firstLines = cleanBody.lineSequence().take(30)
        return firstLines.any { it.trim().startsWith("http://") || it.trim().startsWith("https://") }
    }

    suspend fun parsePlaylist(
        playlist: Playlist,
        context: android.content.Context? = null,
        onProgress: suspend (String) -> Unit = {}
    ): List<Channel> = withContext(Dispatchers.IO) {
        var content = ""
        var fallbackUa: String? = null
        val playlist = playlist.copy(source = playlist.source.trim())

        when {
            playlist.source.startsWith("http://") || playlist.source.startsWith("https://") -> {
                onProgress("Mengunduh playlist dari URL...")
                val customUa = playlist.userAgent?.trim()?.takeIf { it.isNotBlank() && it != "EMPTY_UA" }
                val result = fetchWithFallback(playlist.source, customUa)
                    ?: throw Exception("Gagal mengambil playlist. URL tidak bisa diakses atau format tidak didukung.")
                content = result.first
                fallbackUa = result.second.takeIf { it.isNotBlank() }
            }
            playlist.source.startsWith("content://") -> {
                onProgress("Membaca file playlist...")
                val ctx = context ?: throw Exception("Konteks aplikasi diperlukan untuk membaca file lokal.")
                val uri = android.net.Uri.parse(playlist.source)
                content = ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                } ?: throw Exception("Gagal membaca file dari penyimpanan.")
            }
            playlist.source.startsWith("file://") || playlist.source.startsWith("/") -> {
                onProgress("Membaca file playlist...")
                val path = playlist.source.removePrefix("file://")
                val file = java.io.File(path)
                if (!file.exists() || !file.canRead()) {
                    throw Exception("File tidak ditemukan atau tidak dapat dibaca: $path")
                }
                content = file.readText(Charsets.UTF_8)
            }
            playlist.source.contains("#EXTM3U") || playlist.source.contains("#EXTINF:") -> {
                onProgress("Memproses konten M3U...")
                content = playlist.source
            }
            else -> {
                val ctx = context
                val internalFile = if (ctx != null) java.io.File(ctx.filesDir, "playlists/${playlist.source}") else null
                if (internalFile != null && internalFile.exists() && internalFile.canRead()) {
                    onProgress("Membaca file playlist lokal...")
                    content = internalFile.readText(Charsets.UTF_8)
                } else {
                    throw Exception("Format sumber playlist tidak didukung atau file tidak ditemukan.")
                }
            }
        }

        val cleanContent = content.removePrefix("\uFEFF")
        onProgress("Memulai parsing playlist...")
        val channels = parseM3uString(playlist.id, cleanContent, fallbackUa, onProgress)
        return@withContext channels
    }

    private suspend fun parseM3uString(
        playlistId: Long,
        m3u: String,
        fallbackUa: String? = null,
        onProgress: suspend (String) -> Unit = {}
    ): List<Channel> {
        val channels = mutableListOf<Channel>()
        val reader = java.io.BufferedReader(java.io.StringReader(m3u))

        // State for the *current* channel being built
        var currentName: String? = null
        var currentLogo: String? = null
        var currentCategory: String? = null
        var currentTvgId: String? = null
        var currentDrmKey: String? = null
        var userAgent: String? = fallbackUa
        var referer: String? = null
        var origin: String? = null
        var drmLicenseUri: String? = null
        var drmScheme: String? = null
        var manifestType: String? = null
        var nextChannelId = 9000L

        // Pending state from tags that appear BEFORE EXTINF (e.g. KODIPROP, EXTVLCOPT)
        var pendingUserAgent: String? = null
        var pendingReferer: String? = null
        var pendingOrigin: String? = null
        var pendingDrmScheme: String? = null
        var pendingDrmLicenseUri: String? = null
        var pendingManifestType: String? = null
        var pendingDrmKey: String? = null

        // Split pipe-suffix headers from URL (e.g. "url|Referer=...|User-Agent=...")
        fun splitPipeSuffix(rawUrl: String): Pair<String, List<String>> {
            if (!rawUrl.contains('|')) return rawUrl to emptyList()
            val parts = rawUrl.split('|')
            val url = parts.first()
            val headers = if (parts.size > 1) parts.drop(1).filter { it.contains('=') } else emptyList()
            return url to headers
        }

        fun applyPendingState() {
            if (pendingUserAgent != null) userAgent = pendingUserAgent
            if (pendingReferer != null) referer = pendingReferer
            if (pendingOrigin != null) origin = pendingOrigin
            if (pendingDrmScheme != null) drmScheme = pendingDrmScheme
            if (pendingDrmLicenseUri != null) drmLicenseUri = pendingDrmLicenseUri
            if (pendingManifestType != null) manifestType = pendingManifestType
            if (pendingDrmKey != null) currentDrmKey = pendingDrmKey
            // Clear pending after applying
            pendingUserAgent = null
            pendingReferer = null
            pendingOrigin = null
            pendingDrmScheme = null
            pendingDrmLicenseUri = null
            pendingManifestType = null
            pendingDrmKey = null
        }

        val parseAndApplyLicenseKey: (String) -> Unit = { rawKey ->
            val key = rawKey.trim()
            if (key.startsWith("http", ignoreCase = true)) {
                if (currentName != null) {
                    drmLicenseUri = key
                } else {
                    pendingDrmLicenseUri = key
                }
            } else if (key.contains(":")) {
                if (currentName != null) {
                    currentDrmKey = key
                    if (drmScheme.isNullOrBlank()) drmScheme = "clearkey"
                } else {
                    pendingDrmKey = key
                    if (pendingDrmScheme.isNullOrBlank()) pendingDrmScheme = "clearkey"
                }
                try {
                    // Support multiple "kid:key" pairs separated by comma
                    val pairs = key.split(",").map { it.trim() }.filter { it.contains(":") }
                    val zeroKid64 = android.util.Base64.encodeToString(
                        hexStringToByteArray("00000000000000000000000000000000"),
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                    )
                    val keysJsonList = mutableListOf<String>()
                    for (pair in pairs) {
                        val kid = pair.substringBefore(":").trim().trim('"').replace("-", "")
                        val k = pair.substringAfter(":").trim().trim('"').replace("-", "")
                        val kidBytes = hexStringToByteArray(kid)
                        val kBytes = hexStringToByteArray(k)
                        if (kidBytes.isNotEmpty() && kBytes.isNotEmpty()) {
                            val kid64 = android.util.Base64.encodeToString(kidBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
                            val k64 = android.util.Base64.encodeToString(kBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
                            keysJsonList.add("""{"kty":"oct","k":"$k64","kid":"$kid64"}""")
                            if (!kid.equals("00000000000000000000000000000000", ignoreCase = true)) {
                                keysJsonList.add("""{"kty":"oct","k":"$k64","kid":"$zeroKid64"}""")
                            }
                        }
                    }
                    if (keysJsonList.isNotEmpty()) {
                        val json = """{"keys":[${keysJsonList.joinToString(",")}],"type":"temporary"}"""
                        val licenseUri = "data:application/json,${java.net.URLEncoder.encode(json, "UTF-8")}"
                        if (currentName != null) {
                            drmLicenseUri = licenseUri
                        } else {
                            pendingDrmLicenseUri = licenseUri
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Gagal parse ClearKey: $key", e)
                }
            }
        }

        fun flushChannel(url: String) {
            if (currentName == null || url.isBlank()) return
            channels.add(
                Channel(
                    id = nextChannelId++,
                    playlistId = playlistId,
                    name = currentName!!,
                    url = url,
                    logoUrl = currentLogo ?: "",
                    groupTitle = currentCategory ?: "Umum",
                    tvgId = currentTvgId ?: "",
                    drmKey = currentDrmKey ?: "",
                    drmScheme = drmScheme ?: "",
                    userAgent = userAgent ?: "",
                    referer = referer ?: "",
                    origin = origin ?: "",
                    drmLicenseUri = drmLicenseUri,
                    manifestType = manifestType ?: ""
                )
            )
            // Reset all state for the next channel
            currentName = null
            currentLogo = null
            currentCategory = null
            currentTvgId = null
            currentDrmKey = null
            userAgent = fallbackUa
            referer = null
            origin = null
            drmScheme = null
            drmLicenseUri = null
            manifestType = null
        }

        fun resetChannelState() {
            currentName = null
            currentLogo = null
            currentCategory = null
            currentTvgId = null
            currentDrmKey = null
            userAgent = fallbackUa
            referer = null
            origin = null
            drmScheme = null
            drmLicenseUri = null
            manifestType = null
        }

        var lineCount = 0
        var charsRead = 0L
        val totalChars = m3u.length.toLong().coerceAtLeast(1L)
        var line = reader.readLine()
        
        while (line != null) {
            lineCount++
            charsRead += line.length + 1 // +1 for newline character
            
            if (lineCount % 1500 == 0) {
                val percent = (charsRead * 100 / totalChars).toInt().coerceIn(0, 100)
                onProgress("Memparsing playlist... $percent%\n(Ditemukan ${channels.size} saluran)")
                kotlinx.coroutines.yield()
            }

            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                line = reader.readLine()
                continue
            }

            when {
                trimmed.startsWith("#EXTINF:") -> {
                    // New channel starts — flush any previous channel that had no URL (edge case)
                    if (currentName != null) {
                        // Previous EXTINF without URL — treat as invalid, reset
                        resetChannelState()
                    }
                    // Apply any pending state from tags before this EXTINF
                    applyPendingState()

                    // Cari koma pertama di luar tanda kutip (setelah atribut #EXTINF)
                    // Format: #EXTINF:-1 attr1="v1" attr2="v2",Channel Name
                    // Koma pemisah adalah koma pertama yang tidak ada di dalam tanda kutip
                    val commaIdx = run {
                        var inQuote = false
                        var quoteChar = ' '
                        var idx = -1
                        for (i in trimmed.indices) {
                            val c = trimmed[i]
                            if (inQuote) { if (c == quoteChar) inQuote = false }
                            else if (c == '"' || c == '\'') { inQuote = true; quoteChar = c }
                            else if (c == ',') { idx = i; break }
                        }
                        idx
                    }
                    currentName = if (commaIdx != -1) trimmed.substring(commaIdx + 1).trim() else "Unknown"

                    val tvgNameMatcher = PATTERN_TVG_NAME.matcher(trimmed)
                    val tvgName = if (tvgNameMatcher.find()) tvgNameMatcher.group(1)?.takeIf { it.isNotBlank() } else null
                    if (currentName == "Unknown" || currentName.isNullOrBlank()) {
                        if (tvgName != null) currentName = tvgName
                    }

                    val logoMatcher = PATTERN_TVG_LOGO.matcher(trimmed)
                    currentLogo = if (logoMatcher.find()) logoMatcher.group(1)?.takeIf { it.isNotBlank() } else null

                    val groupMatcher = PATTERN_GROUP.matcher(trimmed)
                    currentCategory = if (groupMatcher.find()) groupMatcher.group(1)?.takeIf { it.isNotBlank() } else "Umum"

                    val tvgIdMatcher = PATTERN_TVG_ID.matcher(trimmed)
                    currentTvgId = if (tvgIdMatcher.find()) tvgIdMatcher.group(1)?.takeIf { it.isNotBlank() } else null
                }
                trimmed.startsWith("#KODIPROP:inputstream.adaptive.manifest_type=", ignoreCase = true) -> {
                    val value = trimmed.substringAfter("=").trim().lowercase()
                    if (currentName != null) {
                        manifestType = value
                    } else {
                        pendingManifestType = value
                    }
                }
                trimmed.startsWith("#KODIPROP:inputstream.adaptive.license_type=", ignoreCase = true) -> {
                    val scheme = trimmed.substringAfter("=").trim().lowercase()
                    val parsedScheme = when {
                        scheme.contains("widevine") -> "widevine"
                        scheme.contains("playready") -> "playready"
                        scheme.contains("clearkey") -> "clearkey"
                        else -> scheme
                    }
                    if (currentName != null) {
                        drmScheme = parsedScheme
                    } else {
                        pendingDrmScheme = parsedScheme
                    }
                }
                trimmed.startsWith("#KODIPROP:inputstream.adaptive.license_key=", ignoreCase = true) -> {
                    val key = trimmed.substringAfter("=").trim()
                    parseAndApplyLicenseKey(key)
                }
                trimmed.startsWith("#KVP:", ignoreCase = true) -> {
                    val kvp = trimmed.substringAfter(":").trim()
                    val keyName = kvp.substringBefore("=").trim().lowercase()
                    val value = kvp.substringAfter("=").trim()
                    when (keyName) {
                        "drm_type", "license_type" -> {
                            val parsedScheme = when {
                                value.contains("widevine", ignoreCase = true) -> "widevine"
                                value.contains("playready", ignoreCase = true) -> "playready"
                                value.contains("clearkey", ignoreCase = true) -> "clearkey"
                                else -> value.lowercase()
                            }
                            if (currentName != null) drmScheme = parsedScheme else pendingDrmScheme = parsedScheme
                        }
                        "clearkey", "license_key" -> {
                            parseAndApplyLicenseKey(value)
                        }
                        "manifest_type" -> {
                            val mt = value.lowercase()
                            if (currentName != null) manifestType = mt else pendingManifestType = mt
                        }
                    }
                }
                trimmed.startsWith("#KODIPROP:inputstream.adaptive.stream_headers=", ignoreCase = true) -> {
                    val rawHeaders = trimmed.substringAfter("=").trim()
                    // Format: Referer=https://...&Origin=https://...&User-Agent=...
                    val pairs = rawHeaders.split("&")
                    for (pair in pairs) {
                        if (pair.contains("=")) {
                            val k = pair.substringBefore("=").trim().lowercase()
                            val rawV = pair.substringAfter("=").trim()
                            val v = try { java.net.URLDecoder.decode(rawV, "UTF-8") } catch (e: Exception) { rawV }
                            when (k) {
                                "referer", "referrer" -> if (currentName != null) referer = v else pendingReferer = v
                                "origin" -> if (currentName != null) origin = v else pendingOrigin = v
                                "user-agent", "useragent", "http-user-agent" -> if (currentName != null) userAgent = v else pendingUserAgent = v
                            }
                        }
                    }
                }
                trimmed.startsWith("#EXTHTTP:", ignoreCase = true) -> {
                    val jsonStr = trimmed.substringAfter(":").trim()
                    try {
                        val json = org.json.JSONObject(jsonStr)
                        val ref = json.optString("Referer").ifBlank { json.optString("referer") }
                        val org = json.optString("Origin").ifBlank { json.optString("origin") }
                        val ua = json.optString("User-Agent").ifBlank { json.optString("user-agent") }
                        if (ref.isNotBlank()) {
                            if (currentName != null) referer = ref else pendingReferer = ref
                        }
                        if (org.isNotBlank()) {
                            if (currentName != null) origin = org else pendingOrigin = org
                        }
                        if (ua.isNotBlank()) {
                            if (currentName != null) userAgent = ua else pendingUserAgent = ua
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Gagal parse EXTHTTP json: $jsonStr", e)
                    }
                }
                trimmed.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val value = trimmed.substringAfter(":").trim()
                    when {
                        value.startsWith("http-user-agent=", ignoreCase = true) -> {
                            val ua = value.substringAfter("=").trim().trim('"')
                            if (currentName != null) userAgent = ua else pendingUserAgent = ua
                        }
                        value.startsWith("http-referrer=", ignoreCase = true) ||
                        value.startsWith("http-referer=", ignoreCase = true) -> {
                            val ref = value.substringAfter("=").trim().trim('"')
                            if (currentName != null) referer = ref else pendingReferer = ref
                        }
                        value.startsWith("http-origin=", ignoreCase = true) -> {
                            val org = value.substringAfter("=").trim().trim('"')
                            if (currentName != null) origin = org else pendingOrigin = org
                        }
                    }
                }
                !trimmed.startsWith("#") -> {
                    // Parse pipe-suffix headers (e.g. "url|Referer=...|User-Agent=...|Origin=...")
                    val (cleanUrl, pipeHeaders) = splitPipeSuffix(trimmed)
                    for (header in pipeHeaders) {
                        val hName = header.substringBefore("=").trim()
                        val hValue = header.substringAfter("=").trim()
                        when (hName.lowercase()) {
                            "referer", "referrer" -> referer = hValue
                            "origin" -> origin = hValue
                            "user-agent", "useragent", "http-user-agent" -> userAgent = hValue
                        }
                    }
                    if (currentName == null) {
                        // Plain URL tanpa EXTINF — terapkan pending state lebih dulu
                        applyPendingState()
                        currentName = "Channel ${nextChannelId - 8999}"
                    }
                    flushChannel(cleanUrl)
                }
            }
            line = reader.readLine()
        }
        // Flush final channel if file ends with URL but no trailing blank line
        flushChannel("")
        return channels
    }

    private fun hexStringToByteArray(s: String): ByteArray {
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
