package com.dinsplayer.app.features.multistream

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dinsplayer.app.core.player.Media3Manager
import com.dinsplayer.app.core.player.MultiStreamManager
import com.dinsplayer.app.ui.theme.DINSPLAYERTheme

class MultiPlayerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        hideSystemBars()

        val initialAutoPickSlot = if (savedInstanceState == null) {
            // Hentikan single-stream sebelum membuat decoder multi-slot
            Media3Manager.stop()

            val initialIds = intent.getStringArrayListExtra(EXTRA_CHANNEL_IDS) ?: arrayListOf()
            val allChannels = Media3Manager.activeChannels.value
            val initialChannels = initialIds.mapNotNull { id -> allChannels.find { it.id == id } }

            MultiStreamManager.initSession(applicationContext, initialChannels)
            intent.getIntExtra(EXTRA_AUTO_PICK_SLOT, -1)
        } else {
            -1
        }

        setContent {
            DINSPLAYERTheme {
                MultiStreamScreen(
                    initialAutoPickSlot = initialAutoPickSlot,
                    onBack = { finish() },
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            MultiStreamManager.releaseAll()
        }
    }

    companion object {
        private const val EXTRA_CHANNEL_IDS = "extra_channel_ids"
        private const val EXTRA_AUTO_PICK_SLOT = "extra_auto_pick_slot"

        fun launch(
            context: Context,
            initialChannelIds: List<String> = emptyList(),
            autoPickSlot: Int = -1,
        ) {
            val intent = Intent(context, MultiPlayerActivity::class.java).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                putStringArrayListExtra(EXTRA_CHANNEL_IDS, ArrayList(initialChannelIds))
                if (autoPickSlot in 0..3) {
                    putExtra(EXTRA_AUTO_PICK_SLOT, autoPickSlot)
                }
            }
            context.startActivity(intent)
        }
    }
}
