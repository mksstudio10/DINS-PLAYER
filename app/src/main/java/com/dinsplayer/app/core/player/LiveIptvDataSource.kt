package com.dinsplayer.app.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

// EOF error message. Media3Manager's retry policy matches on this exact string.
const val LIVE_EOF_RECONNECT_MESSAGE = "Unexpected EOF on Live stream - forcing reconnect"

private val VOD_EXTENSIONS = listOf(
    ".mp4", ".mkv", ".avi", ".webm", ".mov", ".m4v", ".flv", ".wmv", ".mpg", ".mpeg", ".mp3", ".m4a"
)

/**
 * True for VOD containers (local media files, Xtream /movie/ entries).
 * Everything else in IPTV context — `.ts`, extensionless progressive streams — is live.
 *
 * Local content:// and file:// URIs are always VOD: they have no extension, and treating
 * them as live would throw EOF-reconnect at the end of every video, playing it forever.
 */
internal fun isVodUrl(rawUrl: String): Boolean {
    val lower = rawUrl.lowercase()
    if (lower.startsWith("content://") || lower.startsWith("file://")) return true
    val path = lower.substringBefore('?').substringBefore('#')
    return VOD_EXTENSIONS.any { path.endsWith(it) }
}

/**
 * A wrapper DataSource that intercepts EOF (-1) for Live streams and throws an IOException.
 * This forces ExoPlayer's DefaultLoadErrorHandlingPolicy to treat the server disconnect as a network error,
 * which triggers seamless internal retries without destroying the player state or causing UI flashes.
 *
 * Media3Manager only wraps progressive sources with this class — DASH/HLS are left alone because
 * ExoPlayer already reconnects segments itself.
 */
class LiveIptvDataSource(private val upstream: DataSource) : DataSource {
    private var isLive: Boolean = true

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        // URL-shape sniffing was removed: Xtream live URLs look like
        // `$server/live/$user/$pass/$id.ts`, which matched none of the old patterns,
        // so isLive stayed false and EOF was never intercepted.
        isLive = !isVodUrl(dataSpec.uri.toString())
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = upstream.read(buffer, offset, length)

        // Instead of gracefully ending the stream, throw an IOException.
        // ExoPlayer catches this, backs off, and calls open() again seamlessly.
        if (bytesRead == C.RESULT_END_OF_INPUT && isLive) {
            throw IOException(LIVE_EOF_RECONNECT_MESSAGE)
        }

        return bytesRead
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        upstream.close()
    }
}
