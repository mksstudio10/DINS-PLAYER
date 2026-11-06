package com.dinsplayer.app.core.network

import android.util.Log
import android.net.Uri
import com.dinsplayer.app.data.models.Channel
import com.dinsplayer.app.data.models.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object XtreamParser {
    private const val TAG = "XtreamParser"
    private val client = FallbackDns.configure(
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
    ).build()

    data class XtreamCategory(val id: String, val name: String)

    suspend fun fetchChannels(playlist: Playlist, onProgress: suspend (String) -> Unit = {}): List<Channel> = withContext(Dispatchers.IO) {
        val server = playlist.serverUrl.trimEnd('/')
        val username = Uri.encode(playlist.username)
        val password = Uri.encode(playlist.password)
        val ua = playlist.userAgent?.takeIf { it.isNotBlank() && it != "EMPTY_UA" }
            ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.60 Mobile Safari/537.36"
        
        // 1. Fetch categories
        onProgress("Mengambil daftar kategori Xtream...")
        val catMap = try {
            fetchCategories(server, username, password, ua)
                .associate { it.id to it.name }
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch categories: ${e.message}")
            emptyMap()
        }

        // 2. Fetch channels
        onProgress("Mengambil daftar saluran Xtream...")
        val url = "$server/player_api.php?username=$username&password=$password&action=get_live_streams"
        val body = fetchJsonArray(url, ua)

        val channels = mutableListOf<Channel>()
        val totalChannels = body.length()
        for (i in 0 until totalChannels) {
            if (i % 1000 == 0) {
                val percent = (i * 100 / totalChannels.coerceAtLeast(1)).coerceIn(0, 100)
                onProgress("Memparsing saluran... $percent%\n(Ditemukan ${channels.size} saluran)")
                kotlinx.coroutines.yield()
            }

            try {
                val ch = body.getJSONObject(i)
                val streamId = ch.optInt("stream_id", -1)
                if (streamId <= 0) continue

                val ext = ch.optString("container_extension", "ts").ifBlank { "ts" }
                val streamUrl = "$server/live/$username/$password/$streamId.$ext"
                val categoryId = ch.optString("category_id", "")
                val categoryName = catMap[categoryId] ?: "Umum"

                channels.add(
                    Channel(
                        id = streamId.toLong(),
                        playlistId = playlist.id,
                        name = ch.optString("name", "Channel $streamId"),
                        url = streamUrl,
                        logoUrl = ch.optString("stream_icon", "").takeIf { it.isNotBlank() } ?: "",
                        groupTitle = categoryName,
                        userAgent = playlist.userAgent ?: ""
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skipping channel at index $i: ${e.message}")
            }
        }
        channels
    }

    private suspend fun fetchCategories(
        server: String,
        username: String,
        password: String,
        ua: String
    ): List<XtreamCategory> {
        val url = "$server/player_api.php?username=$username&password=$password&action=get_live_categories"
        val arr = fetchJsonArray(url, ua)
        val list = mutableListOf<XtreamCategory>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(XtreamCategory(
                id = obj.optString("category_id", ""),
                name = obj.optString("category_name", "Lainnya")
            ))
        }
        return list
    }

    private fun fetchJsonArray(url: String, ua: String): JSONArray {
        val reqBuilder = Request.Builder().url(url).header("User-Agent", ua)
        val response = client.newCall(reqBuilder.build()).execute()
        if (!response.isSuccessful) {
            response.body?.close()
            throw Exception("HTTP ${response.code}: Server menolak request")
        }
        val responseBody = response.use { it.body?.string() } ?: throw Exception("Respons server kosong")
        val parsed = org.json.JSONTokener(responseBody).nextValue()
        if (parsed is JSONArray) return parsed
        if (parsed is JSONObject) {
            // An object here is not "no channels": Xtream answers a bad login with
            // {"user_info":{"auth":0}} and a dead account with status "Disabled".
            // Returning an empty list made that look like an empty playlist.
            val userInfo = parsed.optJSONObject("user_info")
            val auth = userInfo?.optInt("auth", 1) ?: 1
            val status = (userInfo?.optString("status", "") ?: "").ifBlank {
                parsed.optString("status", "")
            }
            if (auth == 0) {
                throw Exception("Login Xtream ditolak. Periksa username dan password.")
            }
            if (status.equals("Disabled", ignoreCase = true) ||
                status.equals("Expired", ignoreCase = true)
            ) {
                throw Exception("Akun Xtream $status. Hubungi penyedia layanan.")
            }
            if (status.isNotBlank() && status.equals("Banned", ignoreCase = true)) {
                throw Exception("Akun Xtream diblokir oleh server.")
            }
            // Genuinely empty result from a healthy panel.
            return JSONArray()
        }
        throw Exception("Format JSON dari server tidak dikenali")
    }
}
