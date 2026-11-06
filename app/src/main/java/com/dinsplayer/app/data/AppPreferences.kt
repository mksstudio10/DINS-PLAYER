package com.dinsplayer.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.settingsDataStore by preferencesDataStore("player_settings")

val KEY_HW_DECODING = booleanPreferencesKey("hw_decoding")
val KEY_BUFFER_SIZE = intPreferencesKey("buffer_size")
val KEY_AUDIO_SYNC = floatPreferencesKey("audio_sync")
val KEY_EPG_URLS   = androidx.datastore.preferences.core.stringSetPreferencesKey("epg_urls")
val KEY_WALLPAPER_MODE = stringPreferencesKey("wallpaper_mode")
val KEY_WALLPAPER_CUSTOM_URI = stringPreferencesKey("wallpaper_custom_uri")
val KEY_WALLPAPER_CUSTOM_REV = intPreferencesKey("wallpaper_custom_rev")
val KEY_WALLPAPER_SLOT = intPreferencesKey("wallpaper_slot")
val KEY_LIVETV_GRID = booleanPreferencesKey("livetv_grid_view")
val KEY_LOKAL_GRID = booleanPreferencesKey("lokal_grid_view")
val KEY_LOKAL_SORT = intPreferencesKey("lokal_sort_mode")
