package com.dinsplayer.app.features.playlist

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds a playlist file handed to the app from outside ("Open with" from a file manager or
 * chat app) until the UI has moved to the add-playlist form and picked it up.
 */
object PlaylistImport {
    private val _pending = MutableStateFlow<Uri?>(null)
    val pending: StateFlow<Uri?> = _pending

    /** Returns true when [intent] carried a file and it was queued. */
    fun offer(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        _pending.value = uri
        return true
    }

    fun consume() {
        _pending.value = null
    }
}

