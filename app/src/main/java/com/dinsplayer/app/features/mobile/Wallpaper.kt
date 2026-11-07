package com.dinsplayer.app.features.mobile

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.edit
import coil.compose.AsyncImage
import com.dinsplayer.app.data.KEY_WALLPAPER_CUSTOM_REV
import com.dinsplayer.app.data.KEY_WALLPAPER_CUSTOM_URI
import com.dinsplayer.app.data.KEY_WALLPAPER_MODE
import com.dinsplayer.app.data.KEY_WALLPAPER_SLOT
import com.dinsplayer.app.data.settingsDataStore
import com.dinsplayer.app.ui.theme.BgBase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** User-facing wallpaper choice. Stored as its `key` string in DataStore. */
enum class WallpaperMode(val key: String, val label: String, val hint: String) {
    DEFAULT(
        "default",
        "Default",
        "Sembilan foto bawaan, berganti setiap aplikasi dibuka",
    ),
    CUSTOM(
        "custom",
        "Custom",
        "Satu foto pilihan Anda dari galeri",
    ),
    OFF(
        "off",
        "Nonaktif",
        "Latar gelap polos, tanpa foto",
    );

    companion object {
        fun fromKey(key: String?): WallpaperMode =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * Bundled wallpapers in `assets/wallpapers`.
 *
 * The folder cannot be listed at runtime from Compose, so the names are spelled out. Adding a
 * file there also means adding a line here.
 */
private val WALLPAPERS = listOf(
    "anders-jilden-cYrMQA7a3Wc-unsplash",
    "andrew-wang-KZiT6uXQqT4-unsplash",
    "dan-freeman-wAn4RfmXtxU-unsplash",
    "jms-kFHz9Xh3PPU-unsplash",
    "jonatan-pie-3l3RwQdHRHg-unsplash",
    "jonatan-pie-h8nxGssjQXs-unsplash",
    "nasa-Q1p7bh3SHj8-unsplash",
    "urban-vintage-78A265wPiO4-unsplash",
    "wade-meng-LgCj9qcrfhI-unsplash",
)

/**
 * The dark veil over every wallpaper.
 *
 * The photos run from near-black to bright daylight, so the app's text tokens cannot be trusted
 * to stay readable on them. This pulls every frame toward the base colour and keeps white body
 * text legible. Applied to custom photos too, since a user's image can be arbitrarily bright.
 *
 * Opacity is fixed by contrast, not taste, and it is why the photo reads as a texture rather than
 * a picture. Header subtitles put `TextSecondary` (#9E9E9E) straight on this layer. Relative
 * luminance of that grey is 0.342, so 4.5:1 caps the background at 0.0371, which is byte 54. A
 * white photo composited under this veil lands at 255 - 245a, so the floor is a = 0.82. The
 * middle band carries the subtitles and holds 0.85 for margin; the edges only carry bold titles
 * and the near-opaque nav bar, so they run darker without hurting anything.
 *
 * Anything lighter than 0.82 fails R-25, and no amount of taste fixes that: the grey itself is
 * the ceiling. Showing the photo properly needs per-text-block scrims, not a thinner global veil.
 *
 * `TextMuted` (#616161) fails AA on every surface this app ships, wallpaper or not, so it is out
 * of scope here and tracked separately.
 */
internal val VEIL_ALPHAS = listOf(0xB8, 0xA3, 0xCC)

private val VEIL = Brush.verticalGradient(
    VEIL_ALPHAS.map { Color(0xFF101622).copy(alpha = it / 255f) }
)

/**
 * Wallpaper behind every screen.
 *
 * In [WallpaperMode.DEFAULT] the image advances once per app launch. The position is kept in
 * DataStore rather than in memory because the rotation is launch-to-launch: `remember` alone would
 * restart at the first image every time the process dies. One small preference write per launch.
 *
 * The mode and the custom URI are read as flows so a change in Setelan lands immediately without
 * a restart. The rotating slot is captured once in `remember`, so switching tabs never swaps the
 * image mid-session.
 */
@Composable
fun AppWallpaper(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val mode by remember(context) {
        context.settingsDataStore.data.map { WallpaperMode.fromKey(it[KEY_WALLPAPER_MODE]) }
    }.collectAsState(initial = WallpaperMode.DEFAULT)

    val customUri by remember(context) {
        context.settingsDataStore.data.map { it[KEY_WALLPAPER_CUSTOM_URI] }
    }.collectAsState(initial = null)

    val customRev by remember(context) {
        context.settingsDataStore.data.map { it[KEY_WALLPAPER_CUSTOM_REV] ?: 0 }
    }.collectAsState(initial = 0)

    var slot by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val prefs = context.settingsDataStore
        val saved = prefs.data.first()[KEY_WALLPAPER_SLOT] ?: 0
        slot = saved
        prefs.edit { it[KEY_WALLPAPER_SLOT] = saved + 1 }
    }

    // A stored URI can become unreadable: the user deleted the photo, cleared the provider's
    // data, or a provider refused persistence on pick. Track the failure rather than showing the
    // veil over nothing. Keyed on the revision counter as well, so re-picking the same photo
    // retries instead of sticking to the old failure for the life of the process.
    var customFailed by remember(customUri, customRev) { mutableStateOf(false) }
    val bundled =
        "file:///android_asset/wallpapers/${WALLPAPERS[slot % WALLPAPERS.size]}.webp"

    val model: Any? = when (mode) {
        WallpaperMode.DEFAULT -> bundled
        WallpaperMode.CUSTOM ->
            customUri?.takeIf { !customFailed }?.let(Uri::parse) ?: bundled
        WallpaperMode.OFF -> null
    }

    Box(modifier.fillMaxSize()) {
        if (model == null) {
            Box(Modifier.fillMaxSize().background(BgBase))
        } else {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { customFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(VEIL))
        }
    }
}
