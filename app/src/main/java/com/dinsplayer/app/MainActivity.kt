package com.dinsplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dinsplayer.app.core.player.Media3Manager
import com.dinsplayer.app.features.mobile.MobileAppScreen
import com.dinsplayer.app.features.playlist.PlaylistImport
import com.dinsplayer.app.ui.theme.DINSPLAYERTheme

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // WakeLock aman: Jaga layar tetap menyala selama Activity terbuka
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(
                android.graphics.Color.parseColor("#0F2847")
            ),
            navigationBarStyle = SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            )
        )
        Media3Manager.init(applicationContext)

        // A recreated activity gets the same launch intent back; queue it only on a fresh start.
        if (savedInstanceState == null) PlaylistImport.offer(intent)

        setContent {
            DINSPLAYERTheme {
                MobileAppScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PlaylistImport.offer(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            Media3Manager.release()
        }
    }
}