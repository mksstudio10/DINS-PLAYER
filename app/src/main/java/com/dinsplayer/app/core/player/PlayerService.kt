package com.dinsplayer.app.core.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    companion object {
        private const val CHANNEL_ID = "dins_player_playback"
        private const val NOTIF_ID   = 1001
    }

    override fun onCreate() {
        super.onCreate()

        Media3Manager.init(applicationContext)
        val player = Media3Manager.getPlayer() ?: return

        mediaSession = MediaSession.Builder(this, player).build()

        createNotificationChannel()
        // Media3 handles the foreground service notification automatically
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pemutaran Media",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Notifikasi pemutaran DINS Player" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
