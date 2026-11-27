package com.dinsplayer.app.features.playlist

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class PlaylistImportRequest(
    val id: Long,
    val uri: Uri,
)

/**
 * Extracts an incoming playlist from "Open with" (ACTION_VIEW) or Share sheet (ACTION_SEND)
 * intents. Stateless so each Activity instance owns its own pending request and a background
 * Activity cannot consume an import meant for a newly launched Activity.
 */
object PlaylistImport {
    private val nextId = AtomicLong(0L)
    private val urlRegex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    fun extract(context: Context, intent: Intent?): PlaylistImportRequest? {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            Intent.ACTION_SEND -> uriFromShare(context, intent)
            else -> null
        } ?: return null
        return PlaylistImportRequest(id = nextId.incrementAndGet(), uri = uri)
    }

    private fun uriFromShare(context: Context, intent: Intent): Uri? {
        // A shared file arrives as EXTRA_STREAM, some apps only fill ClipData or data.
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { return it }
        intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri?.let { return it }
        intent.data?.let { return it }

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return null

        // Pasted playlist content: park it in a file so it follows the same path as a picked file.
        if (text.contains("#EXTM3U") || text.contains("#EXTINF:")) {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "shared_playlist_${System.currentTimeMillis()}.m3u")
            file.writeText(text, Charsets.UTF_8)
            return Uri.fromFile(file)
        }

        // A shared link, possibly with a caption around it.
        return urlRegex.find(text)?.value?.trimEnd('.', ',', ')', ']')?.let { Uri.parse(it) }
    }
}
