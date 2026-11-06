package com.dinsplayer.app.features.mobile

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Rail on TV and in landscape, bottom bar in portrait. TV is checked separately because some
 * panels report ORIENTATION_UNDEFINED when the device never rotates.
 */
internal fun isWideNav(isTv: Boolean, orientation: Int): Boolean =
    isTv || orientation == Configuration.ORIENTATION_LANDSCAPE

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
