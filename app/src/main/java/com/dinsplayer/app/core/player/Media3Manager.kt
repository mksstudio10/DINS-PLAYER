package com.dinsplayer.app.core.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.dinsplayer.app.data.models.PlayerState
import com.dinsplayer.app.data.models.StreamSource
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

    private val _streamSources = MutableStateFlow<List<StreamSource>>(emptyList())
    val streamSources: StateFlow<List<StreamSource>> = _streamSources

    private val _currentSourceIndex = MutableStateFlow(0)
    val currentSourceIndex: StateFlow<Int> = _currentSourceIndex

    private val _fallbackStatus = MutableStateFlow("")
    val fallbackStatus: StateFlow<String> = _fallbackStatus

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
        // playing channel on refresh. Fall back to any URL belonging to the channel.
        val playingUrl = _currentUrl.value
        _activeChannelId.value = channels.firstOrNull { ch ->
            ch.url == playingUrl || ch.streamSources.any { it.url == playingUrl }
        }?.id ?: ""
    }

    fun setActiveChannelId(channelId: String) {
        _activeChannelId.value = channelId
    }

    private var baseOkHttpClient: okhttp3.OkHttpClient? = null
    private var baseDrmProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider? = null
    private var baseRetryPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy? = null
    private var appContext: android.content.Context? = null

    private val mainHandler: Handler by lazy(LazyThreadSafetyMode.NONE) {
        Handler(Looper.getMainLooper())
    }
    private var pendingFallbackRunnable: Runnable? = null
    private var bufferingTimeoutRunnable: Runnable? = null
    private var currentBaseHeaders: Map<String, String> = emptyMap()
    private var drmRetryCount: Int = 0

    private val playExecutor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(3) { r ->
        Thread(r, "Media3PlayWorker").apply { isDaemon = true }
    }

    /** Inserted into the audio pipeline by [AudioThroughFfmpegRenderersFactory]. */
    private val audioOffsetProcessor = AudioOffsetProcessor()

    private fun cancelPendingTimers() {
        pendingFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingFallbackRunnable = null
        bufferingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        bufferingTimeoutRunnable = null
    }

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
                        if (playbackState == Player.STATE_IDLE &&
                            (player?.playerError != null || pendingFallbackRunnable != null)
                        ) {
                            return
                        }

                        if (playbackState == Player.STATE_READY) {
                            cancelPendingTimers()
                            drmRetryCount = 0
                            _fallbackStatus.value = ""
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
                        val cause = error.cause?.message ?: ""
                        val httpEx = error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                        val httpCode = httpEx?.responseCode ?: 0
                        val failedUrl = httpEx?.dataSpec?.uri?.toString() ?: ""

                        val detail = StreamPlaybackFactory.mapPlaybackError(error, activeStreamCount = 1)
                        android.util.Log.e("Media3Manager", "ExoPlayer Error: [${error.errorCodeName}] ${error.message} cause=$cause httpCode=$httpCode failedUrl=${failedUrl.take(120)}", error)
                        handleSourceFailure(error.errorCode, detail, playSequence.get())
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
        val singleSource = StreamSource(
            url = url,
            serverName = "Server 1",
            userAgent = userAgent,
            drmScheme = drmScheme.orEmpty(),
            drmLicenseUri = drmLicenseUri,
            manifestType = manifestType.orEmpty(),
        )
        playSources(
            sources = listOf(singleSource),
            title = title,
            startIndex = 0,
            extraHeaders = headers,
        )
    }

    fun playSources(
        sources: List<StreamSource>,
        title: String = "",
        startIndex: Int = 0,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        if (sources.isEmpty() || player == null) return
        val currentSeq = playSequence.incrementAndGet()
        cancelPendingTimers()
        _streamSources.value = sources
        _currentTitle.value = title
        currentBaseHeaders = extraHeaders
        drmRetryCount = 0
        _fallbackStatus.value = ""
        startSource(startIndex.coerceIn(0, sources.lastIndex), currentSeq)
    }

    fun switchSource(index: Int) {
        val sources = _streamSources.value
        if (player == null || index !in sources.indices) return
        val currentSeq = playSequence.incrementAndGet()
        cancelPendingTimers()
        drmRetryCount = 0
        _fallbackStatus.value = ""
        startSource(index, currentSeq)
    }

    fun retryCurrentChannel() {
        val sources = _streamSources.value
        if (sources.isNotEmpty()) {
            playSources(sources = sources, title = _currentTitle.value, startIndex = 0, extraHeaders = currentBaseHeaders)
        } else {
            val url = _currentUrl.value
            if (url.isNotBlank()) {
                play(
                    url = url,
                    title = _currentTitle.value,
                    headers = currentBaseHeaders,
                    drmScheme = _currentDrmScheme.value,
                    drmLicenseUri = _currentDrmLicenseUri.value,
                )
            }
        }
    }

    private fun startSource(index: Int, seq: Long) {
        val p = player ?: return
        val sources = _streamSources.value
        val source = sources.getOrNull(index) ?: return

        _currentSourceIndex.value = index
        _state.value = PlayerState.Loading
        _currentUrl.value = source.url
        _currentDrmScheme.value = source.drmScheme.takeIf { it.isNotBlank() }
        _currentDrmLicenseUri.value = source.drmLicenseUri

        scheduleBufferingTimeout(seq)

        val mergedHeaders = buildMap<String, String> {
            putAll(currentBaseHeaders)
            if (source.referer.isNotBlank() && keys.none { it.equals("Referer", ignoreCase = true) }) {
                put("Referer", source.referer)
            }
            if (source.origin.isNotBlank() && keys.none { it.equals("Origin", ignoreCase = true) }) {
                put("Origin", source.origin)
            }
        }

        android.util.Log.i(
            "Media3Manager",
            "startSource() (#$seq, ${index + 1}/${sources.size} ${source.serverName}): " +
                "url=${source.url} drmScheme=${source.drmScheme} manifestType=${source.manifestType}"
        )

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
                    url = source.url,
                    userAgent = source.userAgent,
                    headers = mergedHeaders,
                    drmScheme = source.drmScheme.takeIf { it.isNotBlank() },
                    drmLicenseUri = source.drmLicenseUri,
                    manifestType = source.manifestType.takeIf { it.isNotBlank() },
                )

                if (seq != playSequence.get()) return@execute

                mainHandler.post {
                    if (seq != playSequence.get()) return@post
                    _streamFormat.value = built.streamFormat
                    p.setMediaSource(built.mediaSource)
                    p.prepare()
                    p.playWhenReady = true
                }
            } catch (e: Exception) {
                if (seq != playSequence.get()) return@execute
                android.util.Log.e("Media3Manager", "Worker failed during startSource() (#$seq)!", e)
                mainHandler.post {
                    if (seq != playSequence.get()) return@post
                    handleSourceFailure(0, "Gagal mempersiapkan channel: ${e.message}", seq)
                }
            }
        }
    }

    private fun scheduleBufferingTimeout(seq: Long) {
        bufferingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        if (_currentSourceIndex.value + 1 >= _streamSources.value.size) {
            bufferingTimeoutRunnable = null
            return
        }
        val runnable = Runnable {
            if (seq != playSequence.get()) return@Runnable
            if (_state.value is PlayerState.Loading) {
                android.util.Log.w(
                    "Media3Manager",
                    "Buffering timeout on source #${_currentSourceIndex.value + 1} — switching to backup"
                )
                handleSourceFailure(
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    "Timeout",
                    seq,
                )
            }
        }
        bufferingTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, 25_000L)
    }

    private fun handleSourceFailure(errorCode: Int, detail: String, seq: Long) {
        if (seq != playSequence.get()) return
        cancelPendingTimers()
        val p = player ?: return
        val sources = _streamSources.value
        val curIdx = _currentSourceIndex.value
        val strings = com.dinsplayer.app.core.i18n.S

        when (StreamFallbackPolicy.decide(errorCode, curIdx, sources.size, drmRetryCount)) {
            FallbackAction.REPREPARE_LIVE_WINDOW -> {
                android.util.Log.w("Media3Manager", "Live stream behind window, re-preparing...")
                p.seekToDefaultPosition()
                p.prepare()
                p.play()
            }
            FallbackAction.RETRY_SAME_SOURCE -> {
                drmRetryCount++
                _state.value = PlayerState.Loading
                _fallbackStatus.value = strings.fmtRetryingStream(
                    drmRetryCount,
                    StreamFallbackPolicy.MAX_TRANSIENT_DRM_RETRIES,
                )
                val retryRunnable = Runnable {
                    if (seq != playSequence.get()) return@Runnable
                    startSource(curIdx, seq)
                }
                pendingFallbackRunnable = retryRunnable
                mainHandler.postDelayed(retryRunnable, 1500L)
            }
            FallbackAction.SWITCH_NEXT_SOURCE -> {
                drmRetryCount = 0
                val nextIdx = curIdx + 1
                _state.value = PlayerState.Loading
                _fallbackStatus.value = strings.fmtSwitchingBackupServer(curIdx + 1, nextIdx + 1)
                p.stop()
                val switchRunnable = Runnable {
                    if (seq != playSequence.get()) return@Runnable
                    startSource(nextIdx, seq)
                }
                pendingFallbackRunnable = switchRunnable
                mainHandler.postDelayed(switchRunnable, 1200L)
            }
            FallbackAction.FAIL_ALL -> {
                _fallbackStatus.value = ""
                _state.value = PlayerState.Error(
                    if (sources.size > 1) "${strings.allServersFailed}\n($detail)" else detail
                )
            }
        }
    }

    fun pause()  { player?.pause() }
    fun resume() { player?.play() }

    fun stop() {
        playSequence.incrementAndGet()
        cancelPendingTimers()
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
        _streamSources.value = emptyList()
        _currentSourceIndex.value = 0
        _fallbackStatus.value = ""
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
        playSequence.incrementAndGet()
        cancelPendingTimers()
        player?.release()
        player = null
        baseOkHttpClient = null
        baseDrmProvider = null
        baseRetryPolicy = null
        appContext = null
        _streamSources.value = emptyList()
        _currentSourceIndex.value = 0
        _fallbackStatus.value = ""
        _state.value = PlayerState.Idle
    }
}

