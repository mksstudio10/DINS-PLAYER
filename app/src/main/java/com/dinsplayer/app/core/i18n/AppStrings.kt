package com.dinsplayer.app.core.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppLanguage(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val isRtl: Boolean = false,
) {
    ENGLISH("en", "English", "English"),
    INDONESIAN("id", "Bahasa Indonesia", "Indonesian"),
    SPANISH("es", "Español", "Spanish"),
    FRENCH("fr", "Français", "French"),
    GERMAN("de", "Deutsch", "German"),
    ITALIAN("it", "Italiano", "Italian"),
    PORTUGUESE("pt", "Português", "Portuguese"),
    RUSSIAN("ru", "Русский", "Russian"),
    ARABIC("ar", "العربية", "Arabic", isRtl = true);

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ENGLISH
    }
}

internal object AppLocaleState {
    var currentLanguage by mutableStateOf(AppLanguage.ENGLISH)
}

val S: AppStrings
    get() = stringsForLanguage(AppLocaleState.currentLanguage)

fun stringsForLanguage(language: AppLanguage): AppStrings = when (language) {
    AppLanguage.ENGLISH -> EnglishStrings
    AppLanguage.INDONESIAN -> IndonesianStrings
    AppLanguage.SPANISH -> SpanishStrings
    AppLanguage.FRENCH -> FrenchStrings
    AppLanguage.GERMAN -> GermanStrings
    AppLanguage.ITALIAN -> ItalianStrings
    AppLanguage.PORTUGUESE -> PortugueseStrings
    AppLanguage.RUSSIAN -> RussianStrings
    AppLanguage.ARABIC -> ArabicStrings
}

data class AppStrings(
    // Common
    val cancel: String,
    val save: String,
    val apply: String,
    val close: String,
    val delete: String,
    val open: String,
    val edit: String,
    val add: String,
    val retry: String,
    val back: String,
    val exit: String,
    val pick: String,
    val change: String,
    val active: String,
    val reset: String,
    val loading: String,
    val errorOccurred: String,

    // Navigation
    val navHome: String,
    val navLocal: String,
    val navLiveTv: String,
    val navPlaylist: String,
    val navSettings: String,
    val exitAppTitle: String,
    val exitAppMessage: String,

    // Home (Beranda)
    val history: String,
    val profile: String,
    val profileComingSoon: String,
    val streamUrlPlaceholder: String,
    val paste: String,
    val play: String,
    val pause: String,
    val streamTestTitle: String,
    val advancedOptions: String,
    val customHeaders: String,
    val drmLicensePlaceholder: String,
    val scanQrFillWeb: String,
    val phoneBrowser: String,
    val playStream: String,

    // Local Media
    val localTitle: String,
    val localGrantAccess: String,
    val localEmptyHint: String,
    val localPermissionTitle: String,
    val sortDate: String,
    val sortName: String,
    val sortSize: String,
    val confirmDeleteTitle: String,

    // Live TV & Multi TV
    val liveTvTitle: String,
    val allGroup: String,
    val searchChannels: String,
    val listViewDesc: String,
    val gridViewDesc: String,
    val selectChannelsMenu: String,
    val openBlankScreenMenu: String,
    val selectChannelsTitle: String,
    val selectMinTwo: String,
    val readyForMultiTv: String,
    val start: String,
    val noChannels: String,
    val addPlaylistHint: String,
    val channelNotFound: String,
    val resetFilter: String,
    val multiTvTitle: String,
    val layoutSplit2: String,
    val layoutFocus3: String,
    val layoutGrid4: String,
    val muteAll: String,
    val mute: String,
    val audio: String,
    val replace: String,
    val primary: String,
    val fit: String,
    val zoom: String,
    val fullscreen: String,
    val lockScreen: String,
    val screenLockedHint: String,
    val emptySlot: String,
    val tapToAddChannel: String,
    val openLiveTvFirst: String,
    val exitMultiTvTitle: String,
    val exitMultiTvMessage: String,

    // Playlist
    val playlistTitle: String,
    val noActivePlaylist: String,
    val searchPlaylists: String,
    val noPlaylistsYet: String,
    val addPlaylist: String,
    val playlistNotFound: String,
    val confirmDeletePlaylistPrefix: String,
    val checking: String,
    val downloadM3u: String,
    val playlistActivate: String,
    val reorderPlaylist: String,
    val reorderHintTv: String,
    val moreOptions: String,

    // Player & Channel Menu
    val noPlaylistLoaded: String,
    val noChannelFoundShort: String,
    val noAudioTracks: String,
    val noSubtitles: String,

    // Settings
    val settingsTitle: String,
    val settingsSubtitle: String,
    val groupPlayback: String,
    val hwAccelTitle: String,
    val hwAccelOnSub: String,
    val hwAccelOffSub: String,
    val bufferTitle: String,
    val bufferSub: String,
    val audioSyncTitle: String,
    val audioSyncSub: String,
    val normal: String,
    val groupDisplay: String,
    val languageTitle: String,
    val languageDialogTitle: String,
    val languageDialogSubtitle: String,
    val themeColorTitle: String,
    val themeActivePrefix: String,
    val themeDefaultSuffix: String,
    val themeCustomLabel: String,
    val themeCustomDialogTitle: String,
    val themeCustomDialogSub: String,
    val themePreviewTitle: String,
    val themePreviewActive: String,
    val themePreviewPlay: String,
    val themeHueLabel: String,
    val themeSatLabel: String,
    val themeHexLabel: String,
    val themeHexError: String,
    val themeResetBlue: String,
    val wallpaperDefault: String,
    val wallpaperDefaultHint: String,
    val wallpaperCustom: String,
    val wallpaperCustomHint: String,
    val wallpaperOff: String,
    val wallpaperOffHint: String,
    val changeWallpaperTitle: String,
    val noPhotoSelected: String,
    val photoSaved: String,
    val noPhotoPickerToast: String,
    val groupEpg: String,
    val epgSourceTitle: String,
    val manageAndCheck: String,
    val epgDialogSubEmpty: String,
    val checkAll: String,
    val checkingLabel: String,
    val checkingConnection: String,
    val notCheckedYet: String,
    val epgValidLabel: String,
    val epgNoSource: String,
    val epgInvalidUrl: String,
    val epgNotXmltv: String,
    val epgEmptyXmltv: String,
    val epgEmptyResponse: String,
    val epgDomainError: String,
    val epgTimeoutError: String,
    val epgSslError: String,
    val epgConnectFail: String,
    val groupAbout: String,
    val appTagline: String,
    val tosTitle: String,
    val tosSub: String,
    val disclaimerTitle: String,
    val disclaimerBody: String,
    val tosDialogSub: String,
    val tosAcceptClose: String,

    // Preset colors
    val presetBlue: String,
    val presetEmerald: String,
    val presetCrimson: String,
    val presetViolet: String,
    val presetAmber: String,
    val presetTeal: String,
    val presetRose: String,
    val presetOrange: String,

    // Formatters
    val fmtChannels: (Int) -> String,
    val fmtSelectedOf4: (Int) -> String,
    val fmtSelectedOf4Channels: (Int) -> String,
    val fmtPickSlot: (Int) -> String,
    val fmtSlotN: (Int) -> String,
    val fmtBufferSeconds: (Int) -> String,
    val fmtActivePlaylist: (String) -> String,
    val fmtStartMulti: (Int) -> String,
    val fmtEpgSummaryChecking: (Int) -> String,
    val fmtEpgSummarySaved: (Int) -> String,
    val fmtEpgSummaryActiveOnly: (Int, Int) -> String,
    val fmtEpgSummaryErrorOnly: (Int, Int) -> String,
    val fmtEpgSummaryMixed: (Int, Int, Int) -> String,
    val fmtEpgActiveHttp: (Int) -> String,
    val fmtEpgActivePrograms: (Int) -> String,
    val languageNames: Map<AppLanguage, String>,
)
