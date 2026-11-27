package com.dinsplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.dinsplayer.app.core.player.Media3Manager
import com.dinsplayer.app.features.mobile.MobileAppScreen
import com.dinsplayer.app.features.playlist.PlaylistImport
import com.dinsplayer.app.features.playlist.PlaylistImportRequest
import com.dinsplayer.app.ui.theme.DINSPLAYERTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : AppCompatActivity() {

    private val pendingImport = MutableStateFlow<PlaylistImportRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstances++

        // WakeLock aman: Jaga layar tetap menyala selama Activity terbuka
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            )
        )
        Media3Manager.init(applicationContext)

        // A recreated activity gets the same launch intent back; extract it only on a fresh start.
        if (savedInstanceState == null) {
            PlaylistImport.extract(this, intent)?.let { pendingImport.value = it }
        }

        setContent {
            val importRequest by pendingImport.collectAsState()
            DINSPLAYERTheme {
                MobileAppScreen(
                    importRequest = importRequest,
                    onImportConsumed = { pendingImport.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PlaylistImport.extract(this, intent)?.let { pendingImport.value = it }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeInstances = (activeInstances - 1).coerceAtLeast(0)
        if (isFinishing && activeInstances == 0) {
            Media3Manager.release()
        }
    }

    companion object {
        private var activeInstances = 0
    }
}