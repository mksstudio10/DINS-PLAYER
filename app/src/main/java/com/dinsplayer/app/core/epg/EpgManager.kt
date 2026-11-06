package com.dinsplayer.app.core.epg

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import com.dinsplayer.app.core.network.FallbackDns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

data class EpgProgram(
    val title: String,
    val desc: String,
    val startMillis: Long,
    val stopMillis: Long
)

object EpgManager {
    private const val TAG = "EpgManager"

    private val _epgData = MutableStateFlow<Map<String, List<EpgProgram>>>(emptyMap())
    val epgData: StateFlow<Map<String, List<EpgProgram>>> = _epgData.asStateFlow()

    // Normalized key index: normalized_key -> original_key in epgData, for O(1) lookup
    private val _normalizedIndex = MutableStateFlow<Map<String, String>>(emptyMap())
    val normalizedIndex: StateFlow<Map<String, String>> = _normalizedIndex.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

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

    // Default EPGs provided by the user
    val defaultUrls = listOf(
        "https://raw.githubusercontent.com/AqFad2811/epg/refs/heads/main/indonesia.xml",
        "https://raw.githubusercontent.com/apistech/project/refs/heads/main/epgs/guide.xml",
        "https://www.open-epg.com/files/indonesia1.xml",
        "https://bit.ly/AstroEpg"
    )

    suspend fun syncEpgs(urls: Set<String>) {
        if (urls.isEmpty()) return
        _isLoading.value = true
        
        try {
            withContext(Dispatchers.IO) {
                val combinedData = mutableMapOf<String, MutableList<EpgProgram>>()
                
                for (url in urls) {
                    try {
                        Log.i(TAG, "Downloading EPG from $url")
                        val request = Request.Builder().url(url).build()
                        val response = client.newCall(request).execute()
                        
                        if (response.isSuccessful) {
                            response.body?.byteStream()?.use { rawStream ->
                                val inputStream = if (url.endsWith(".gz", ignoreCase = true) || "gzip".equals(response.header("Content-Encoding"), ignoreCase = true)) {
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
                                                    } catch (e: Exception) {
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
                                                    if (!combinedData.containsKey(currentChannel)) {
                                                        combinedData[currentChannel] = mutableListOf()
                                                    }
                                                    combinedData[currentChannel]?.add(program)
                                                }
                                                currentChannel = null
                                            }
                                        }
                                    }
                                    eventType = parser.next()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error syncing EPG from $url", e)
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
