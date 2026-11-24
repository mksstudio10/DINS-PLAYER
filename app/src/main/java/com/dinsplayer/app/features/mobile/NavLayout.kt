package com.dinsplayer.app.features.mobile

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

internal const val TAB_BERANDA  = 0
internal const val TAB_LOKAL    = 1
internal const val TAB_LIVETV   = 2
internal const val TAB_PLAYLIST = 3
internal const val TAB_SETELAN  = 4

/**
 * Rail on TV and in landscape, bottom bar in portrait. TV is checked separately because some
 * panels report ORIENTATION_UNDEFINED when the device never rotates.
 */
internal fun isWideNav(isTv: Boolean, orientation: Int): Boolean =
    isTv || orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * Opens directly to Live TV on Android TV or landscape screens, while portrait phones start on Home.
 */
internal fun initialTabForLayout(wide: Boolean): Int =
    if (wide) TAB_LIVETV else TAB_BERANDA

/**
 * Returns [homeTab] when pressing Back from any other tab, or null when already at [homeTab]
 * so the shell can prompt to exit.
 */
internal fun resolveBackTab(currentTab: Int, homeTab: Int): Int? =
    if (currentTab != homeTab) homeTab else null

@Composable
internal fun rememberIsWideNav(): Boolean {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTv = remember(context) {
        (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager)
            .currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
    return isWideNav(isTv, configuration.orientation)
}
