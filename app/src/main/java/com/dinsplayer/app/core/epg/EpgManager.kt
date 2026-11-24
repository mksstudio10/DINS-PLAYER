package com.dinsplayer.app.core.epg

import android.util.Log
import com.dinsplayer.app.core.network.FallbackDns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class EpgProgram(
    val title: String,
    val desc: String,
    val startMillis: Long,
    val stopMillis: Long
)

sealed class EpgUrlStatus {
    data object Unknown : EpgUrlStatus()
    data object Checking : EpgUrlStatus()
    data class Active(
        val httpCode: Int = 200,
        val detail: String = "Active",
        val checkedAtMillis: Long = System.currentTimeMillis(),
    ) : EpgUrlStatus()
    data class Error(
        val reason: String,
        val checkedAtMillis: Long = System.currentTimeMillis(),
    ) : EpgUrlStatus()
}

/** Logika validasi URL & header XMLTV pure-Kotlin untuk diuji di JVM. */
internal object EpgUrlValidator {

    fun isValidHttpUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        return try {
            val uri = URI(trimmed)
            !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun looksLikeXmltvHeader(snippet: String): Boolean {
        val clean = snippet.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (clean.isEmpty()) return false
        val lower = clean.lowercase(Locale.ROOT)
        if (lower.contains("<html") || lower.contains("<!doctype html")) {
            return false
        }
        return lower.contains("<tv") ||
            lower.contains("<programme") ||
            lower.contains("<!doctype tv")
    }

    fun mapHttpStatusError(code: Int): String = when (code) {
        401 -> "HTTP 401 (Unauthorized)"
        403 -> "HTTP 403 (Forbidden)"
        404 -> "HTTP 404 (Not Found)"
        429 -> "HTTP 429 (Too Many Requests)"
        in 500..599 -> "HTTP $code (Server Error)"
        else -> "HTTP $code (Request Failed)"
    }

    fun mapExceptionError(e: Throwable): String = when (e) {
        is UnknownHostException -> com.dinsplayer.app.core.i18n.S.epgDomainError
        is SocketTimeoutException -> com.dinsplayer.app.core.i18n.S.epgTimeoutError
        is SSLException -> com.dinsplayer.app.core.i18n.S.epgSslError
        else -> e.message?.takeIf { it.isNotBlank() }?.let { "${com.dinsplayer.app.core.i18n.S.epgConnectFail}: $it" }
            ?: com.dinsplayer.app.core.i18n.S.epgConnectFail
    }

    fun summarizeEpgStatuses(urls: List<String>, statuses: Map<String, EpgUrlStatus>): String {
        val strings = com.dinsplayer.app.core.i18n.S
        if (urls.isEmpty()) return strings.epgNoSource
        var activeCount = 0
        var errorCount = 0
        var checkingCount = 0
        urls.forEach { url ->
            when (statuses[url]) {
                is EpgUrlStatus.Active -> activeCount++
                is EpgUrlStatus.Error -> errorCount++
                EpgUrlStatus.Checking -> checkingCount++
                EpgUrlStatus.Unknown, null -> {}
            }
        }
        return when {
            checkingCount > 0 -> strings.fmtEpgSummaryChecking(urls.size)
            activeCount == 0 && errorCount == 0 -> strings.fmtEpgSummarySaved(urls.size)
            errorCount == 0 -> strings.fmtEpgSummaryActiveOnly(urls.size, activeCount)
            activeCount == 0 -> strings.fmtEpgSummaryErrorOnly(urls.size, errorCount)
            else -> strings.fmtEpgSummaryMixed(urls.size, activeCount, errorCount)
        }
    }
}

object EpgManager {
    private const val TAG = "EpgManager"
    private const val CHECK_SNIPPET_BYTES = 8 * 1024

    private val _epgData = MutableStateFlow<Map<String, List<EpgProgram>>>(emptyMap())
    val epgData: StateFlow<Map<String, List<EpgProgram>>> = _epgData.asStateFlow()

    // Normalized key index: normalized_key -> original_key in epgData, for O(1) lookup
    private val _normalizedIndex = MutableStateFlow<Map<String, String>>(emptyMap())
    val normalizedIndex: StateFlow<Map<String, String>> = _normalizedIndex.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _urlStatuses = MutableStateFlow<Map<String, EpgUrlStatus>>(emptyMap())
    val urlStatuses: StateFlow<Map<String, EpgUrlStatus>> = _urlStatuses.asStateFlow()

    private val NON_ALPHANUMERIC_REGEX = Regex("[^a-zA-Z0-9]")
    private val normCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun normalizeKey(raw: String): String {
        if (raw.isEmpty()) return ""
        return normCache.getOrPut(raw) {
            raw.substringBeforeLast(".").replace(NON_ALPHANUMERIC_REGEX, "").lowercase()
        }
    }

    private val client = FallbackDns.configure(
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
    ).build()

    private val checkClient = FallbackDns.configure(
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
    ).build()

    // Default EPGs provided by the user
    val defaultUrls = listOf(
        "https://raw.githubusercontent.com/AqFad2811/epg/refs/heads/main/indonesia.xml",
        "https://raw.githubusercontent.com/apistech/project/refs/heads/main/epgs/guide.xml",
        "https://www.open-epg.com/files/indonesia1.xml",
        "https://bit.ly/AstroEpg"
    )

    private fun setUrlStatus(url: String, status: EpgUrlStatus) {
        _urlStatuses.update { current -> current + (url to status) }
    }

    suspend fun checkUrl(rawUrl: String): EpgUrlStatus {
        val url = rawUrl.trim()
        if (!EpgUrlValidator.isValidHttpUrl(url)) {
            val invalid = EpgUrlStatus.Error(com.dinsplayer.app.core.i18n.S.epgInvalidUrl)
            setUrlStatus(rawUrl, invalid)
            return invalid
        }

        setUrlStatus(url, EpgUrlStatus.Checking)
        val result = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).get().build()
                checkClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext EpgUrlStatus.Error(
                            EpgUrlValidator.mapHttpStatusError(response.code)
                        )
                    }
                    val bodyStream = response.body?.byteStream()
                        ?: return@withContext EpgUrlStatus.Error("${com.dinsplayer.app.core.i18n.S.epgEmptyResponse} (HTTP ${response.code})")
                    val isGzip = url.endsWith(".gz", ignoreCase = true) ||
                        "gzip".equals(response.header("Content-Encoding"), ignoreCase = true)

                    val snippet = bodyStream.use { rawStream ->
                        readHeaderSnippet(rawStream, isGzip)
                    }

                    if (EpgUrlValidator.looksLikeXmltvHeader(snippet)) {
                        EpgUrlStatus.Active(
                            httpCode = response.code,
                            detail = com.dinsplayer.app.core.i18n.S.fmtEpgActiveHttp(response.code),
                        )
                    } else {
                        EpgUrlStatus.Error("${com.dinsplayer.app.core.i18n.S.epgNotXmltv} (HTTP ${response.code})")
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "Check URL failed: $url", e)
                EpgUrlStatus.Error(EpgUrlValidator.mapExceptionError(e))
            }
        }
        setUrlStatus(url, result)
        return result
    }

    suspend fun checkAllUrls(urls: List<String>) {
        val distinctUrls = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (distinctUrls.isEmpty()) return
        coroutineScope {
            distinctUrls.map { url ->
                async { checkUrl(url) }
            }.awaitAll()
        }
    }

    private fun readHeaderSnippet(rawStream: InputStream, expectGzip: Boolean): String {
        val buffered = java.io.BufferedInputStream(rawStream, CHECK_SNIPPET_BYTES)
        buffered.mark(4)
        val magic = ByteArray(2)
        val readMagic = buffered.read(magic)
        buffered.reset()

        val hasGzipMagic = readMagic == 2 &&
            (magic[0].toInt() and 0xFF) == 0x1F &&
            (magic[1].toInt() and 0xFF) == 0x8B

        val stream = if (expectGzip && hasGzipMagic || hasGzipMagic) {
            java.util.zip.GZIPInputStream(buffered)
        } else {
            buffered
        }

        val buffer = ByteArray(1024)
        val out = ByteArrayOutputStream()
        var total = 0
        while (total < CHECK_SNIPPET_BYTES) {
            val n = stream.read(buffer, 0, minOf(buffer.size, CHECK_SNIPPET_BYTES - total))
            if (n <= 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toString(Charsets.UTF_8.name())
    }

    suspend fun syncEpgs(urls: Set<String>) {
        if (urls.isEmpty()) return
        _isLoading.value = true

        try {
            withContext(Dispatchers.IO) {
                val combinedData = mutableMapOf<String, MutableList<EpgProgram>>()

                for (url in urls) {
                    setUrlStatus(url, EpgUrlStatus.Checking)
                    try {
                        Log.i(TAG, "Downloading EPG from $url")
                        val request = Request.Builder().url(url).build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                setUrlStatus(url, EpgUrlStatus.Error(EpgUrlValidator.mapHttpStatusError(response.code)))
                                return@use
                            }
                            var programCount = 0
                            response.body?.byteStream()?.use { rawStream ->
                                val inputStream = if (
                                    url.endsWith(".gz", ignoreCase = true) ||
                                    "gzip".equals(response.header("Content-Encoding"), ignoreCase = true)
                                ) {
                                    java.util.zip.GZIPInputStream(rawStream)
                                } else {
                                    rawStream
                                }
                                val factory = XmlPullParserFactory.newInstance()
                                factory.isNamespaceAware = true
                                val parser = factory.newPullParser()
                                parser.setInput(inputStream, null)

                                var eventType = parser.eventType
                                var currentChannel: String? = null
                                var currentStart = 0L
                                var currentStop = 0L
                                var currentTitle = ""
                                var currentDesc = ""

                                val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ENGLISH)
                                val altDateFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.ENGLISH)

                                while (eventType != XmlPullParser.END_DOCUMENT) {
                                    when (eventType) {
                                        XmlPullParser.START_TAG -> {
                                            when (parser.name) {
                                                "programme" -> {
                                                    currentChannel = parser.getAttributeValue(null, "channel")
                                                    val startStr = parser.getAttributeValue(null, "start") ?: ""
                                                    val stopStr = parser.getAttributeValue(null, "stop") ?: ""

                                                    try {
                                                        currentStart = if (startStr.contains("+") || startStr.contains("-")) {
                                                            dateFormat.parse(startStr)?.time ?: 0L
                                                        } else {
                                                            altDateFormat.parse(startStr)?.time ?: 0L
                                                        }
                                                        currentStop = if (stopStr.contains("+") || stopStr.contains("-")) {
                                                            dateFormat.parse(stopStr)?.time ?: 0L
                                                        } else {
                                                            altDateFormat.parse(stopStr)?.time ?: 0L
                                                        }
                                                    } catch (_: Exception) {
                                                        // Parse error, ignore
                                                    }

                                                    currentTitle = ""
                                                    currentDesc = ""
                                                }
                                                "title" -> {
                                                    if (currentChannel != null) {
                                                        currentTitle = parser.nextText()
                                                    }
                                                }
                                                "desc" -> {
                                                    if (currentChannel != null) {
                                                        currentDesc = parser.nextText()
                                                    }
                                                }
                                            }
                                        }
                                        XmlPullParser.END_TAG -> {
                                            if (parser.name == "programme" && currentChannel != null) {
                                                if (currentStart > 0 && currentTitle.isNotBlank()) {
                                                    val program = EpgProgram(currentTitle, currentDesc, currentStart, currentStop)
                                                    combinedData.getOrPut(currentChannel) { mutableListOf() }.add(program)
                                                    programCount++
                                                }
                                                currentChannel = null
                                            }
                                        }
                                    }
                                    eventType = parser.next()
                                }
                            }
                            if (programCount > 0) {
                                setUrlStatus(url, EpgUrlStatus.Active(response.code, com.dinsplayer.app.core.i18n.S.fmtEpgActivePrograms(programCount)))
                            } else {
                                setUrlStatus(url, EpgUrlStatus.Error(com.dinsplayer.app.core.i18n.S.epgEmptyXmltv))
                            }
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        Log.e(TAG, "Error syncing EPG from $url", e)
                        setUrlStatus(url, EpgUrlStatus.Error(EpgUrlValidator.mapExceptionError(e)))
                    }
                }

                // Sort programmes by start time
                val finalData = combinedData.mapValues { entry ->
                    entry.value.sortedBy { it.startMillis }
                }
                _epgData.value = finalData
                // Build normalized lookup index for O(1) channel matching in UI
                _normalizedIndex.value = finalData.keys.associateBy { key -> normalizeKey(key) }
            }
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Resolves a channel to its EPG entry: exact tvg-id, then the normalised tvg-id, then
     * the normalised display name. Generic so both the programme map and the
     * now-playing-only map can use it. This chain was copy-pasted into five call sites,
     * which is how they drifted apart.
     */
    fun <T> resolve(
        tvgId: String?,
        name: String?,
        data: Map<String, T>,
        index: Map<String, String> = _normalizedIndex.value,
    ): T? {
        if (!tvgId.isNullOrBlank()) {
            data[tvgId]?.let { return it }
            index[normalizeKey(tvgId)]?.let { data[it]?.let { hit -> return hit } }
        }
        if (!name.isNullOrBlank()) {
            index[normalizeKey(name)]?.let { data[it]?.let { hit -> return hit } }
        }
        return null
    }
}
