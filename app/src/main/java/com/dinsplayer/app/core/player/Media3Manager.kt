package com.dinsplayer.app.core.player

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.dinsplayer.app.data.models.PlayerState
import com.dinsplayer.app.features.player.ChannelItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object Media3Manager {

    private var player: ExoPlayer? = null
    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val state: StateFlow<PlayerState> = _state

    private val _currentTitle = MutableStateFlow("")
    val currentTitle: StateFlow<String> = _currentTitle

    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl

    private val _currentDrmScheme = MutableStateFlow<String?>(null)
    val currentDrmScheme: StateFlow<String?> = _currentDrmScheme

    private val _currentDrmLicenseUri = MutableStateFlow<String?>(null)
    val currentDrmLicenseUri: StateFlow<String?> = _currentDrmLicenseUri

    // Shared channel list for PlayerScreen in-player menu
    private val _activeChannels = MutableStateFlow<List<ChannelItem>>(emptyList())
    val activeChannels: StateFlow<List<ChannelItem>> = _activeChannels

    private val _activeChannelId = MutableStateFlow("")
    val activeChannelId: StateFlow<String> = _activeChannelId

    private val _streamFormat = MutableStateFlow("")
    val streamFormat: StateFlow<String> = _streamFormat

    /**
     * [currentChannelId] is optional on purpose: callers that only refresh the list
     * (e.g. LiveTvScreen after re-parsing the playlist) must not clear the channel
     * that is currently playing, or Prev/Next and the menu highlight reset to index 0.
     */
    fun setActiveChannels(channels: List<ChannelItem>, currentChannelId: String = "") {
        _activeChannels.value = channels
        if (currentChannelId.isNotBlank()) {
            _activeChannelId.value = currentChannelId
            return
        }
        if (channels.any { it.id == _activeChannelId.value }) return
        // M3uParser assigns fresh ids on every parse, so id matching alone loses the
        // playing channel on refresh. Fall back to the URL that is actually playing.
        _activeChannelId.value = channels.firstOrNull { it.url == _currentUrl.value }?.id ?: ""
    }

    fun setActiveChannelId(channelId: String) {
        _activeChannelId.value = channelId
    }

    private var baseOkHttpClient: okhttp3.OkHttpClient? = null
    private var baseDrmProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider? = null
    private var baseRetryPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy? = null
    private var appContext: android.content.Context? = null

    private val playExecutor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(3) { r ->
        Thread(r, "Media3PlayWorker").apply { isDaemon = true }
    }

    /** Inserted into the audio pipeline by [AudioThroughFfmpegRenderersFactory]. */
    private val audioOffsetProcessor = AudioOffsetProcessor()

    fun init(context: Context) {
        if (player != null) return
        this.appContext = context.applicationContext
        com.dinsplayer.app.data.PlayerSettings.load(context)
        audioOffsetProcessor.offsetMs =
            com.dinsplayer.app.data.PlayerSettings.audioSyncMs.coerceIn(-500L, 500L)

        val okHttpClient = StreamPlaybackFactory.createBaseOkHttpClient()
        this.baseOkHttpClient = okHttpClient
        this.baseDrmProvider = StreamPlaybackFactory.createDrmProvider(okHttpClient)

        val maxBufferMs = when (com.dinsplayer.app.data.PlayerSettings.bufferPreset) {
            com.dinsplayer.app.data.PlayerSettings.BUFFER_16S -> 16_000
            com.dinsplayer.app.data.PlayerSettings.BUFFER_64S -> 64_000
            else -> 32_000
        }
        val loadControl = StreamPlaybackFactory.createDefaultLoadControl(
            maxBufferMs = maxBufferMs,
            targetBufferBytes = 128 * 1024 * 1024,
        )

        val fastRetryPolicy = StreamPlaybackFactory.createFastRetryPolicy()
        this.baseRetryPolicy = fastRetryPolicy

        val renderersFactory = StreamPlaybackFactory.createRenderersFactory(context, audioOffsetProcessor)
        val trackSelector = StreamPlaybackFactory.createTrackSelector(context, cap720p = false)

        player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build().apply {
                setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
                setHandleAudioBecomingNoisy(true)

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_IDLE && player?.playerError != null) {
                            return
                        }

                        _state.value = when (playbackState) {
                            Player.STATE_IDLE -> PlayerState.Idle
                            Player.STATE_BUFFERING -> PlayerState.Loading
                            Player.STATE_READY -> if (playWhenReady) PlayerState.Playing else PlayerState.Paused
                            Player.STATE_ENDED -> {
                                val p = player
                                if (p != null && (p.isCurrentMediaItemLive || p.duration == androidx.media3.common.C.TIME_UNSET)) {
                                    p.seekToDefaultPosition()
                                    p.prepare()
                                    p.play()
                                    PlayerState.Loading
                                } else {
                                    PlayerState.Idle
                                }
                            }
                            else -> _state.value
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (playbackState == Player.STATE_READY) {
                            _state.value = if (isPlaying) PlayerState.Playing else PlayerState.Paused
                        }
                    }

                    // Diagnostics: proves whether an audio track was detected and who decodes it.
                    override fun onTracksChanged(tracks: Tracks) {
                        tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }.forEach { group ->
                            val f = group.getTrackFormat(0)
                            android.util.Log.i(
                                "Media3Manager",
                                "AUDIO track: ${f.sampleMimeType} lang=${f.language} " +
                                    "supported=${group.isTrackSupported(0)} support=${group.getTrackSupport(0)} " +
                                    "selected=${group.isSelected}"
                            )
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val p = player

                        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                            android.util.Log.w("Media3Manager", "Live stream behind window, re-preparing...")
                            p?.seekToDefaultPosition()
                            p?.prepare()
                            p?.play()
                            return
                        }

                        val cause = error.cause?.message ?: ""
                        val httpEx = error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                        val httpCode = httpEx?.responseCode ?: 0
                        val failedUrl = httpEx?.dataSpec?.uri?.toString() ?: ""

                        val detail = StreamPlaybackFactory.mapPlaybackError(error, activeStreamCount = 1)
                        _state.value = PlayerState.Error(detail)
                        android.util.Log.e("Media3Manager", "ExoPlayer Error: [${error.errorCodeName}] ${error.message} cause=$cause httpCode=$httpCode failedUrl=${failedUrl.take(120)}", error)
                    }
                })
            }
    }

    private val playSequence = java.util.concurrent.atomic.AtomicLong(0L)

    fun play(
        url: String,
        title: String = "",
        userAgent: String = "",
        headers: Map<String, String> = emptyMap(),
        drmScheme: String? = null,
        drmLicenseUri: String? = null,
        manifestType: String? = null
    ) {
        val p = player ?: return
        val currentSeq = playSequence.incrementAndGet()
        _state.value = PlayerState.Loading
        _currentTitle.value = title
        _currentUrl.value = url
        _currentDrmScheme.value = drmScheme
        _currentDrmLicenseUri.value = drmLicenseUri

        android.util.Log.i("Media3Manager", "play() called (#$currentSeq): url=$url drmScheme=$drmScheme drmLicenseUri=${drmLicenseUri?.take(60)} manifestType=$manifestType")

        playExecutor.execute {
            try {
                val pContext = appContext ?: throw IllegalStateException("appContext is null")
                val okClient = baseOkHttpClient ?: throw IllegalStateException("baseOkHttpClient is null")
                val drmProv = baseDrmProvider ?: throw IllegalStateException("baseDrmProvider is null")
                val retryPol = baseRetryPolicy ?: throw IllegalStateException("baseRetryPolicy is null")

                val built = StreamPlaybackFactory.resolveAndBuildMediaSource(
                    context = pContext,
                    okClient = okClient,
                    drmProvider = drmProv,
                    retryPolicy = retryPol,
                    url = url,
                    userAgent = userAgent,
                    headers = headers,
                    drmScheme = drmScheme,
                    drmLicenseUri = drmLicenseUri,
                    manifestType = manifestType,
                )

                if (currentSeq != playSequence.get()) return@execute

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (currentSeq != playSequence.get()) return@post
                    _streamFormat.value = built.streamFormat
                    p.setMediaSource(built.mediaSource)
                    p.prepare()
                    p.playWhenReady = true
                }
            } catch (e: Exception) {
                if (currentSeq != playSequence.get()) return@execute
                android.util.Log.e("Media3Manager", "Worker failed during play() setup (#$currentSeq)!", e)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (currentSeq != playSequence.get()) return@post
                    _state.value = PlayerState.Error("Gagal mempersiapkan channel: ${e.message}")
                }
            }
        }
    }

    fun pause()  { player?.pause() }
    fun resume() { player?.play() }

    fun stop() {
        player?.let {
            it.stop()
            // seekTo(0) on a live stream lands outside the moving live window and
            // re-prepare then fails with BEHIND_LIVE_WINDOW. Default position handles both.
            it.seekToDefaultPosition()
        }
        // Keep the URL/title around and the player's retry path will re-prepare a file
        // that may already be deleted from disk. Clear them so Idle really means idle.
        _currentUrl.value = ""
        _currentTitle.value = ""
        _state.value = PlayerState.Idle
    }

    fun getPlayer(): ExoPlayer? = player

    fun launchPlayer(context: android.content.Context) {
        val intent = android.content.Intent(context, com.dinsplayer.app.features.player.PlayerActivity::class.java)
        // Gunakan NEW_TASK | SINGLE_TOP agar tidak membuat double activity jika sudah jalan
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)
    }

    fun setAudioSyncMs(offsetMs: Long) {
        audioOffsetProcessor.offsetMs = offsetMs.coerceIn(-500L, 500L)
        // The processor recomputes its target every queueInput() call, so a live slider drag
        // converges on the new offset without re-preparing the stream.
    }

    fun release() {
        player?.release()
        player = null
        baseOkHttpClient = null
        baseDrmProvider = null
        baseRetryPolicy = null
        appContext = null
        _state.value = PlayerState.Idle
    }
}

