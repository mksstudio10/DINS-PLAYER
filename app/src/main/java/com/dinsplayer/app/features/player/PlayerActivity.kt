package com.dinsplayer.app.features.player

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.dinsplayer.app.data.models.PlayerState
import com.dinsplayer.app.core.player.Media3Manager
import com.dinsplayer.app.ui.theme.DINSPLAYERTheme

class PlayerActivity : AppCompatActivity() {

    private var isInPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(
                android.graphics.Color.parseColor("#000000")
            )
        )

        addOnPictureInPictureModeChangedListener { info ->
            isInPip = info.isInPictureInPictureMode
        }

        Media3Manager.init(applicationContext)
        handleIntent(intent)

        setContent {
            DINSPLAYERTheme {
                val currentTitle by Media3Manager.currentTitle.collectAsState()

                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    PlayerScreen(
                        title = currentTitle,
                        isPipMode = isInPip,
                        onBack = {
                            Media3Manager.stop()
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        val urlStr = uri.toString()
        if (urlStr.isNotBlank()) {
            val title = uri.lastPathSegment?.substringAfterLast("/") ?: "Video"
            Media3Manager.play(url = urlStr, title = title)
        }
    }

    /**
     * Home press drops the app into a floating window.
     *
     * Two guards, both load bearing. `state` must be `Playing`: entering PiP while idle shows an
     * empty window, and entering it again while already in PiP throws. And the hardware check
     * matters because Android TV, where this app also runs, has no PiP at all, so the feature
     * flag from the manifest is not enough on its own.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (isInPictureInPictureMode) return
        if (Media3Manager.state.value != PlayerState.Playing) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
