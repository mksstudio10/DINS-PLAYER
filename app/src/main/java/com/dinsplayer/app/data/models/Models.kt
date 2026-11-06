package com.dinsplayer.app.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

// ── Playlist ──────────────────────────────────────────────────────────────
enum class PlaylistType { M3U_URL, M3U_FILE, XTREAM }

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val source: String,       // URL or file path
    val type: PlaylistType,
    val username: String = "",
    val password: String = "",
    val serverUrl: String = "",
    // Written by nothing yet. Kept so the existing schema does not need a migration;
    // the playlist card no longer renders them.
    val channelCount: Int = 0,
    val vodCount: Int = 0,
    val isActive: Boolean = true,
    val userAgent: String? = null,
    val customHeaders: String = "",   // format: "Key: Value\nKey2: Value2"
    val addedAt: Long = System.currentTimeMillis(),
    val lastRefreshed: Long = 0L,
)

// ── Channel (IPTV live) ────────────────────────────────────────────────────
data class Channel(
    val id: Long = 0,
    val playlistId: Long,
    val name: String,
    val url: String,
    val logoUrl: String = "",
    val groupTitle: String = "",
    val tvgId: String = "",
    val drmKey: String = "",        // "kid:key" format from KODIPROP
    val drmScheme: String = "",     // clearkey | widevine | playready
    val userAgent: String = "",
    val referer: String = "",
    val origin: String = "",        // from #EXTVLCOPT:http-origin
    val drmLicenseUri: String? = null,
    val manifestType: String = "", // dash | hls | "" (auto-detect)
)

// ── VOD / Local video ─────────────────────────────────────────────────────
data class VideoFile(
    val id: Long,
    val name: String,
    val path: String,
    val folderName: String,
    val duration: Long,         // milliseconds
    val size: Long,             // bytes
    val dateAdded: Long,
    val thumbnailUri: String = "",
)

data class VideoFolder(
    val name: String,
    val path: String,
    val videoCount: Int,
    val totalSize: Long,        // bytes
    val thumbnailUri: String = "",
)

// ── Stream test form state ─────────────────────────────────────────────────
data class StreamTestForm(
    val url: String = "",
    val xForwardedFor: String = "",
    val referer: String = "",
    val origin: String = "",
    val drmLicense: String = "",
    val customHeaders: String = "",
    val userAgent: UserAgentOption = UserAgentOption.DEFAULT,
    val customUserAgent: String = "",   // dipakai saat userAgent == CUSTOM
    val drmScheme: DrmSchemeOption = DrmSchemeOption.AUTO,
    val showCustomHeaders: Boolean = false,
)

enum class UserAgentOption(val label: String, val value: String) {
    DEFAULT("Default", "EMPTY_UA"),
    CUSTOM("Custom", ""),
    CHROME("Chrome", "Mozilla/5.0 (Linux; Android 16; Pixel 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"),
    DINSPLAYER("DinsPlayer", "DinsPlayer/1.0 (Android) Chrome/129.0.0.0 Safari/537.36"),
    VLC("VLC", "VLC/3.0.18 LibVLC/3.0.18"),
    TIVIMATE("TiviMate", "TiviMate/4.7.0"),
}

enum class DrmSchemeOption(val label: String, val schemeUuid: String) {
    AUTO("Auto", ""),
    CLEARKEY("ClearKey", "e2719d58-a985-b3c9-781a-b030af78d30e"),
    WIDEVINE("Widevine", "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"),
    PLAYREADY("PlayReady", "9a04f079-9840-4286-ab92-e65be0885f95"),
}

// ── Player state ───────────────────────────────────────────────────────────
sealed class PlayerState {
    object Idle : PlayerState()
    object Loading : PlayerState()
    object Playing : PlayerState()
    object Paused : PlayerState()
    data class Error(val message: String, val code: Int = 0) : PlayerState()
}

// ── Net error classification ───────────────────────────────────────────────
sealed class NetError {
    object Timeout : NetError()
    object SecurityMitm : NetError()
    data class HttpError(val code: Int) : NetError()
    data class Unknown(val message: String) : NetError()
}
