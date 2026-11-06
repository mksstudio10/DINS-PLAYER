package com.dinsplayer.app.data

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Player preferences cached in memory.
 *
 * [com.dinsplayer.app.core.player.Media3Manager] builds the ExoPlayer instance on whatever
 * thread first calls [init], and DataStore has no synchronous read. Blocking there would risk
 * an ANR, so the values are loaded once from Application.onCreate (before any Activity) and
 * refreshed by the settings screen on every change.
 */
object PlayerSettings {

    const val BUFFER_16S = 0
    const val BUFFER_32S = 1
    const val BUFFER_64S = 2

    @Volatile
    var bufferPreset: Int = BUFFER_32S
        private set

    @Volatile
    var hwDecode: Boolean = true
        private set

    @Volatile
    var audioSyncMs: Long = 0L
        private set

    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        try {
            runBlocking {
                val p = context.applicationContext.settingsDataStore.data.first()
                bufferPreset = p[KEY_BUFFER_SIZE] ?: BUFFER_32S
                hwDecode = p[KEY_HW_DECODING] ?: true
                audioSyncMs = (p[KEY_AUDIO_SYNC] ?: 0f).toLong()
            }
            loaded = true
        } catch (e: Exception) {
            // Unreadable prefs must not stop playback; the defaults above are usable.
            android.util.Log.w("PlayerSettings", "load failed: ${e.message}")
        }
    }

    fun updateBuffer(preset: Int) { bufferPreset = preset }
    fun updateHwDecode(enabled: Boolean) { hwDecode = enabled }
    fun updateAudioSync(ms: Long) { audioSyncMs = ms }
}
