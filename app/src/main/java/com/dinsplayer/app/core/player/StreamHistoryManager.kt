package com.dinsplayer.app.core.player

import android.content.Context
import android.content.SharedPreferences
import com.dinsplayer.app.data.models.DrmSchemeOption
import com.dinsplayer.app.data.models.StreamTestForm
import com.dinsplayer.app.data.models.UserAgentOption
import org.json.JSONArray
import org.json.JSONObject

object StreamHistoryManager {
    private const val PREF_NAME = "stream_history_prefs"
    private const val KEY_HISTORY = "history_list"
    private const val MAX_HISTORY_SIZE = 50

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getHistory(context: Context): List<StreamTestForm> {
        val prefs = getPrefs(context)
        val jsonStr = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        val list = mutableListOf<StreamTestForm>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val form = StreamTestForm(
                    url = obj.optString("url", ""),
                    drmLicense = obj.optString("drmLicense", ""),
                    referer = obj.optString("referer", ""),
                    origin = obj.optString("origin", ""),
                    xForwardedFor = obj.optString("xForwardedFor", ""),
                    customHeaders = obj.optString("customHeaders", ""),
                    userAgent = UserAgentOption.values().find { it.name == obj.optString("userAgent") } ?: UserAgentOption.DEFAULT,
                    customUserAgent = obj.optString("customUserAgent", ""),
                    drmScheme = DrmSchemeOption.values().find { it.name == obj.optString("drmScheme") } ?: DrmSchemeOption.AUTO,
                    showCustomHeaders = obj.optBoolean("showCustomHeaders", false)
                )
                list.add(form)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveHistoryItem(context: Context, form: StreamTestForm) {
        val list = getHistory(context).toMutableList()
        // Hapus jika sudah ada URL yang sama agar dipindah ke atas
        list.removeAll { it.url == form.url }
        list.add(0, form)
        if (list.size > MAX_HISTORY_SIZE) {
            list.removeAt(list.lastIndex)
        }
        saveAll(context, list)
    }

    fun removeHistoryItem(context: Context, form: StreamTestForm) {
        val list = getHistory(context).toMutableList()
        list.removeAll { it.url == form.url }
        saveAll(context, list)
    }

    private fun saveAll(context: Context, list: List<StreamTestForm>) {
        val jsonArray = JSONArray()
        list.forEach { form ->
            val obj = JSONObject()
            obj.put("url", form.url)
            obj.put("drmLicense", form.drmLicense)
            obj.put("referer", form.referer)
            obj.put("origin", form.origin)
            obj.put("xForwardedFor", form.xForwardedFor)
            obj.put("customHeaders", form.customHeaders)
            obj.put("userAgent", form.userAgent.name)
            obj.put("customUserAgent", form.customUserAgent)
            obj.put("drmScheme", form.drmScheme.name)
            obj.put("showCustomHeaders", form.showCustomHeaders)
            jsonArray.put(obj)
        }
        getPrefs(context).edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
    }
}
