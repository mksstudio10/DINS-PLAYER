package com.dinsplayer.app.core.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import com.dinsplayer.app.data.models.PlayerState
import com.dinsplayer.app.features.player.ChannelItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

enum class MultiLayoutMode(val slotCount: Int, val label: String) {
    SPLIT_2(2, "2 Layar"),
    FOCUS_3(3, "1+2 Fokus"),
    GRID_4(4, "2x2 Grid"),
}

fun MultiLayoutMode.localizedLabel(): String = when (this) {
    MultiLayoutMode.SPLIT_2 -> com.dinsplayer.app.core.i18n.S.layoutSplit2
    MultiLayoutMode.FOCUS_3 -> com.dinsplayer.app.core.i18n.S.layoutFocus3
    MultiLayoutMode.GRID_4 -> com.dinsplayer.app.core.i18n.S.layoutGrid4
}

data class MultiSlotState(
    val slotIndex: Int,
    val channel: ChannelItem? = null,
    val playerState: PlayerState = PlayerState.Idle,
    val streamFormat: String = "",
    val isAudioActive: Boolean = false,
    val resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
)

/** Logika pure-Kotlin Multi-Stream agar dapat diuji di JVM tanpa runtime Android. */
internal object MultiStreamLogic {

    fun resolveInitialLayout(channelCount: Int): MultiLayoutMode = when {
        channelCount >= 4 -> MultiLayoutMode.GRID_4
        channelCount == 3 -> MultiLayoutMode.FOCUS_3
        else -> MultiLayoutMode.SPLIT_2
    }

    fun applyAudioSelectionToSlots(
        currentSlots: List<MultiSlotState>,
        activeAudioSlot: Int?,
    ): List<MultiSlotState> = currentSlots.mapIndexed { idx, state ->
        state.copy(isAudioActive = activeAudioSlot != null && idx == activeAudioSlot)
    }

    fun swapSlotStates(
        currentSlots: List<MultiSlotState>,
        a: Int,
        b: Int,
        currentAudioSlot: Int?,
    ): Pair<List<MultiSlotState>, Int?> {
        if (a == b || a !in currentSlots.indices || b !in currentSlots.indices) {
            return currentSlots to currentAudioSlot
        }
        val newAudioSlot = when (currentAudioSlot) {
            a -> b
            b -> a
            else -> currentAudioSlot
        }
        val mutable = currentSlots.toMutableList()
        val slotA = mutable[a]
        val slotB = mutable[b]
        mutable[a] = slotB.copy(slotIndex = a, isAudioActive = newAudioSlot == a)
        mutable[b] = slotA.copy(slotIndex = b, isAudioActive = newAudioSlot == b)
        return mutable.toList() to newAudioSlot
    }
}

/**
 * Manajer sesi Multi-Stream, stateless per sesi (tidak disimpan ke disk).
 *
 * ATURAN AUDIO KERAS: hanya SATU slot yang boleh punya volume > 0 kapanpun.
 * Melanggar aturan ini bisa merusak hardware audio. [syncVolumes] adalah
 * satu-satunya tempat volume diatur.
 */
@androidx.media3.common.util.UnstableApi
object MultiStreamManager {

    const val MAX_SLOTS = 4

    private val _slots = MutableStateFlow(List(MAX_SLOTS) { MultiSlotState(it) })
    val slots: StateFlow<List<MultiSlotState>> = _slots.asStateFlow()

    private val _layoutMode = MutableStateFlow(MultiLayoutMode.SPLIT_2)
    val layoutMode: StateFlow<MultiLayoutMode> = _layoutMode.asStateFlow()

    private val _activeAudioSlotIndex = MutableStateFlow<Int?>(null)
    val activeAudioSlotIndex: StateFlow<Int?> = _activeAudioSlotIndex.asStateFlow()

    private val _cap720p = MutableStateFlow(true)
    val cap720p: StateFlow<Boolean> = _cap720p.asStateFlow()

    // Satu ExoPlayer + TrackSelector + AudioOffsetProcessor independen per slot
    private val players = arrayOfNulls<ExoPlayer>(MAX_SLOTS)
    private val trackSelectors = arrayOfNulls<DefaultTrackSelector>(MAX_SLOTS)
    private val audioProcessors = Array(MAX_SLOTS) { AudioOffsetProcessor() }
    private val slotSourceIndices = IntArray(MAX_SLOTS)
    private val slotDrmRetries = IntArray(MAX_SLOTS)
    private val pendingSlotFallbacks = arrayOfNulls<Runnable>(MAX_SLOTS)
    private val playerSequences = java.util.Collections.synchronizedMap(
        java.util.IdentityHashMap<ExoPlayer, AtomicLong>()
    )

    private var appContext: Context? = null
    private var sharedOkHttpClient: OkHttpClient? = null
    private var sharedDrmProvider: DrmSessionManagerProvider? = null
    private var sharedRetryPolicy: LoadErrorHandlingPolicy? = null

    private val mainHandler: Handler by lazy(LazyThreadSafetyMode.NONE) {
        Handler(Looper.getMainLooper())
    }
    private val playExecutors = Array(MAX_SLOTS) {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "MultiStream-Worker-$it").apply { isDaemon = true }
        }
    }

    private fun cancelSlotFallback(slotIndex: Int) {
        pendingSlotFallbacks[slotIndex]?.let { mainHandler.removeCallbacks(it) }
        pendingSlotFallbacks[slotIndex] = null
    }

    fun initSession(context: Context, initialChannels: List<ChannelItem> = emptyList()) {
        val ctx = context.applicationContext
        appContext = ctx
        com.dinsplayer.app.data.PlayerSettings.load(ctx)

        // Hentikan sesi sebelumnya secara sinkron jika sudah di main thread
        releaseAll()

        val okClient = StreamPlaybackFactory.createBaseOkHttpClient()
        sharedOkHttpClient = okClient
        sharedDrmProvider = StreamPlaybackFactory.createDrmProvider(okClient)
        sharedRetryPolicy = StreamPlaybackFactory.createFastRetryPolicy()

        val initialLayout = MultiStreamLogic.resolveInitialLayout(initialChannels.size)
        _slots.value = List(MAX_SLOTS) { MultiSlotState(it) }
        _activeAudioSlotIndex.value = null
        _layoutMode.value = initialLayout
        _cap720p.value = true

        val slotCount = initialLayout.slotCount
        for (i in 0 until slotCount) {
            buildPlayerForSlot(i, ctx)
        }

        initialChannels.take(slotCount).forEachIndexed { idx, ch ->
            playChannel(idx, ch)
        }
        if (initialChannels.isNotEmpty()) {
            selectAudioSlot(0)
        }
    }

    fun setLayout(mode: MultiLayoutMode) {
        val ctx = appContext ?: return
        val oldMode = _layoutMode.value
        if (mode == oldMode) return
        _layoutMode.value = mode

        if (mode.slotCount > oldMode.slotCount) {
            for (i in oldMode.slotCount until mode.slotCount) {
                if (players[i] == null) buildPlayerForSlot(i, ctx)
            }
        } else if (mode.slotCount < oldMode.slotCount) {
            for (i in mode.slotCount until oldMode.slotCount) {
                releaseSlot(i)
            }
            val audio = _activeAudioSlotIndex.value
            if (audio != null && audio >= mode.slotCount) {
                val fallback = (0 until mode.slotCount).firstOrNull { _slots.value[it].channel != null }
                if (fallback != null) selectAudioSlot(fallback) else muteAll()
            }
        }
        refreshTrackCaps()
    }

    fun playChannel(slotIndex: Int, channel: ChannelItem) {
        if (slotIndex !in 0 until MAX_SLOTS) return
        cancelSlotFallback(slotIndex)
        slotSourceIndices[slotIndex] = 0
        slotDrmRetries[slotIndex] = 0
        updateSlot(slotIndex) { it.copy(channel = channel, playerState = PlayerState.Loading) }
        refreshTrackCaps()
        loadSlotSource(slotIndex, channel, sourceIndex = 0)
    }

    private fun loadSlotSource(slotIndex: Int, channel: ChannelItem, sourceIndex: Int) {
        val ctx = appContext ?: return
        if (slotIndex !in 0 until MAX_SLOTS) return

        if (players[slotIndex] == null) {
            buildPlayerForSlot(slotIndex, ctx)
        }
        val targetPlayer = players[slotIndex] ?: return
        val seqCounter = playerSequences.getOrPut(targetPlayer) { AtomicLong(0L) }
        val seq = seqCounter.incrementAndGet()

        val sources = channel.resolvedSources()
        val safeIndex = sourceIndex.coerceIn(0, sources.lastIndex)
        val source = sources[safeIndex]
        slotSourceIndices[slotIndex] = safeIndex

        updateSlot(slotIndex) { it.copy(playerState = PlayerState.Loading) }

        val headers = buildMap {
            if (source.referer.isNotBlank()) put("Referer", source.referer)
            if (source.origin.isNotBlank()) put("Origin", source.origin)
        }

        playExecutors[slotIndex].execute {
            try {
                val okClient = sharedOkHttpClient ?: StreamPlaybackFactory.createBaseOkHttpClient()
                val drmProv = sharedDrmProvider ?: StreamPlaybackFactory.createDrmProvider(okClient)
                val retryPol = sharedRetryPolicy ?: StreamPlaybackFactory.createFastRetryPolicy()

                val built = StreamPlaybackFactory.resolveAndBuildMediaSource(
                    context = ctx,
                    okClient = okClient,
                    drmProvider = drmProv,
                    retryPolicy = retryPol,
                    url = source.url,
                    userAgent = source.userAgent,
                    headers = headers,
                    drmScheme = source.drmScheme.takeIf { it.isNotBlank() },
                    drmLicenseUri = source.drmLicenseUri,
                    manifestType = source.manifestType,
                )

                if (seq != seqCounter.get()) return@execute

                runOnMain {
                    val liveIdx = findSlotIndex(targetPlayer)
                    if (liveIdx < 0 || seq != seqCounter.get()) return@runOnMain
                    updateSlot(liveIdx) { it.copy(streamFormat = built.streamFormat) }
                    targetPlayer.setMediaSource(built.mediaSource)
                    targetPlayer.prepare()
                    targetPlayer.playWhenReady = true
                    syncVolumes()
                }
            } catch (e: Exception) {
                if (seq != seqCounter.get()) return@execute
                android.util.Log.e("MultiStreamManager", "Slot $slotIndex play failed", e)
                runOnMain {
                    val liveIdx = findSlotIndex(targetPlayer)
                    if (liveIdx < 0 || seq != seqCounter.get()) return@runOnMain
                    handleSlotFailure(
                        liveIdx,
                        targetPlayer,
                        seq,
                        seqCounter,
                        0,
                        "Gagal mempersiapkan channel: ${e.message}",
                    )
                }
            }
        }
    }

    private fun handleSlotFailure(
        slotIndex: Int,
        targetPlayer: ExoPlayer,
        seq: Long,
        seqCounter: AtomicLong,
        errorCode: Int,
        message: String,
    ) {
        if (seq != seqCounter.get()) return
        val channel = _slots.value.getOrNull(slotIndex)?.channel ?: return
        val sources = channel.resolvedSources()
        val curIdx = slotSourceIndices[slotIndex]

        when (StreamFallbackPolicy.decide(errorCode, curIdx, sources.size, slotDrmRetries[slotIndex])) {
            FallbackAction.REPREPARE_LIVE_WINDOW -> {
                targetPlayer.seekToDefaultPosition()
                targetPlayer.prepare()
                targetPlayer.play()
            }
            FallbackAction.RETRY_SAME_SOURCE -> {
                slotDrmRetries[slotIndex]++
                cancelSlotFallback(slotIndex)
                updateSlot(slotIndex) { it.copy(playerState = PlayerState.Loading) }
                val retryRunnable = Runnable {
                    val liveIdx = findSlotIndex(targetPlayer)
                    if (liveIdx < 0 || seq != seqCounter.get()) return@Runnable
                    val liveChannel = _slots.value.getOrNull(liveIdx)?.channel ?: return@Runnable
                    loadSlotSource(liveIdx, liveChannel, slotSourceIndices[liveIdx])
                }
                pendingSlotFallbacks[slotIndex] = retryRunnable
                mainHandler.postDelayed(retryRunnable, 1500L)
            }
            FallbackAction.SWITCH_NEXT_SOURCE -> {
                slotDrmRetries[slotIndex] = 0
                val nextIdx = curIdx + 1
                cancelSlotFallback(slotIndex)
                targetPlayer.stop()
                updateSlot(slotIndex) { it.copy(playerState = PlayerState.Loading) }
                val switchRunnable = Runnable {
                    val liveIdx = findSlotIndex(targetPlayer)
                    if (liveIdx < 0 || seq != seqCounter.get()) return@Runnable
                    val liveChannel = _slots.value.getOrNull(liveIdx)?.channel ?: return@Runnable
                    loadSlotSource(liveIdx, liveChannel, nextIdx)
                }
                pendingSlotFallbacks[slotIndex] = switchRunnable
                mainHandler.postDelayed(switchRunnable, 1000L)
            }
            FallbackAction.FAIL_ALL -> {
                cancelSlotFallback(slotIndex)
                updateSlot(slotIndex) { it.copy(playerState = PlayerState.Error(message)) }
            }
        }
    }

    /**
     * Satu-satunya cara mengaktifkan audio pada sebuah slot.
     * Semua slot lain langsung dimatikan bunyinya.
     */
    fun selectAudioSlot(slotIndex: Int) {
        if (slotIndex !in 0 until MAX_SLOTS) return
        _activeAudioSlotIndex.value = slotIndex
        _slots.value = MultiStreamLogic.applyAudioSelectionToSlots(_slots.value, slotIndex)
        syncVolumes()
    }

    fun toggleAudioMute(slotIndex: Int) {
        if (_activeAudioSlotIndex.value == slotIndex) {
            muteAll()
        } else {
            selectAudioSlot(slotIndex)
        }
    }

    fun muteAll() {
        _activeAudioSlotIndex.value = null
        _slots.value = MultiStreamLogic.applyAudioSelectionToSlots(_slots.value, null)
        syncVolumes()
    }

    fun setCap720p(enabled: Boolean) {
        _cap720p.value = enabled
        refreshTrackCaps()
    }

    fun toggleResizeMode(slotIndex: Int) {
        if (slotIndex !in 0 until MAX_SLOTS) return
        updateSlot(slotIndex) {
            val next = if (it.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            it.copy(resizeMode = next)
        }
    }

    fun swapSlots(a: Int, b: Int) {
        if (a == b || a !in 0 until MAX_SLOTS || b !in 0 until MAX_SLOTS) return

        val (swappedList, newAudioSlot) = MultiStreamLogic.swapSlotStates(_slots.value, a, b, _activeAudioSlotIndex.value)
        _slots.value = swappedList
        _activeAudioSlotIndex.value = newAudioSlot

        val pA = players[a]
        players[a] = players[b]
        players[b] = pA

        val tsA = trackSelectors[a]
        trackSelectors[a] = trackSelectors[b]
        trackSelectors[b] = tsA

        val apA = audioProcessors[a]
        audioProcessors[a] = audioProcessors[b]
        audioProcessors[b] = apA

        val srcIdxA = slotSourceIndices[a]
        slotSourceIndices[a] = slotSourceIndices[b]
        slotSourceIndices[b] = srcIdxA

        val drmRetryA = slotDrmRetries[a]
        slotDrmRetries[a] = slotDrmRetries[b]
        slotDrmRetries[b] = drmRetryA

        val fbA = pendingSlotFallbacks[a]
        pendingSlotFallbacks[a] = pendingSlotFallbacks[b]
        pendingSlotFallbacks[b] = fbA

        syncVolumes()
    }

    fun closeSlot(slotIndex: Int) {
        if (slotIndex !in 0 until MAX_SLOTS) return
        releaseSlot(slotIndex)
        if (_activeAudioSlotIndex.value == slotIndex) {
            val nextActive = _slots.value.firstOrNull { it.channel != null }?.slotIndex
            if (nextActive != null) selectAudioSlot(nextActive) else muteAll()
        }
        refreshTrackCaps()
    }

    fun getPlayer(slotIndex: Int): ExoPlayer? =
        if (slotIndex in 0 until MAX_SLOTS) players[slotIndex] else null

    fun releaseAll() {
        for (i in 0 until MAX_SLOTS) {
            releaseSlot(i)
        }
        _activeAudioSlotIndex.value = null
        _slots.value = List(MAX_SLOTS) { MultiSlotState(it) }
    }

    private fun buildPlayerForSlot(slotIndex: Int, context: Context) {
        val audioProcessor = audioProcessors[slotIndex]
        val renderersFactory = StreamPlaybackFactory.createRenderersFactory(context, audioProcessor)
        val populatedCount = _slots.value.count { it.channel != null }
        val trackSelector = StreamPlaybackFactory.createTrackSelector(
            context,
            cap720p = _cap720p.value && populatedCount >= 2
        )
        // Profil buffer ringan per slot: 8s / 24MB agar 4 stream stabil tanpa OOM
        val loadControl = StreamPlaybackFactory.createDefaultLoadControl(
            maxBufferMs = 8_000,
            targetBufferBytes = 24 * 1024 * 1024,
            minBufferMs = 3_000,
            bufferForPlaybackMs = 1_500,
            bufferForPlaybackAfterRebufferMs = 2_500,
        )

        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()

        player.setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
        player.setHandleAudioBecomingNoisy(false)
        player.volume = if (_activeAudioSlotIndex.value == slotIndex) 1.0f else 0.0f

        // Lookup slot index secara dinamis agar Listener tetap akurat setelah swapSlots()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val idx = findSlotIndex(player)
                if (idx < 0) return
                val state = when (playbackState) {
                    Player.STATE_IDLE ->
                        if (player.playerError != null || pendingSlotFallbacks[idx] != null || _slots.value.getOrNull(idx)?.channel == null) return
                        else PlayerState.Idle
                    Player.STATE_BUFFERING -> PlayerState.Loading
                    Player.STATE_READY -> {
                        cancelSlotFallback(idx)
                        slotDrmRetries[idx] = 0
                        if (player.playWhenReady) PlayerState.Playing else PlayerState.Paused
                    }
                    Player.STATE_ENDED -> {
                        if (player.isCurrentMediaItemLive || player.duration == androidx.media3.common.C.TIME_UNSET) {
                            player.seekToDefaultPosition()
                            player.prepare()
                            player.play()
                            PlayerState.Loading
                        } else PlayerState.Idle
                    }
                    else -> return
                }
                updateSlot(idx) { it.copy(playerState = state) }
            }

            override fun onPlayerError(error: PlaybackException) {
                val idx = findSlotIndex(player)
                if (idx < 0) return
                val activeCount = _slots.value.count {
                    it.playerState is PlayerState.Playing || it.playerState is PlayerState.Loading
                }
                val message = StreamPlaybackFactory.mapPlaybackError(error, activeCount)
                android.util.Log.e("MultiStreamManager", "Slot $idx error: [${error.errorCodeName}]", error)
                val seqCounter = playerSequences[player] ?: return
                handleSlotFailure(idx, player, seqCounter.get(), seqCounter, error.errorCode, message)
            }
        })

        players[slotIndex] = player
        trackSelectors[slotIndex] = trackSelector
        updateSlot(slotIndex) { it.copy(playerState = PlayerState.Idle) }
    }

    private fun releaseSlot(slotIndex: Int) {
        cancelSlotFallback(slotIndex)
        slotSourceIndices[slotIndex] = 0
        slotDrmRetries[slotIndex] = 0
        updateSlot(slotIndex) { MultiSlotState(slotIndex) }
        runOnMain {
            players[slotIndex]?.let { oldPlayer ->
                playerSequences[oldPlayer]?.incrementAndGet()
                playerSequences.remove(oldPlayer)
                oldPlayer.stop()
                oldPlayer.release()
            }
            players[slotIndex] = null
            trackSelectors[slotIndex] = null
        }
    }

    private fun refreshTrackCaps() {
        val populatedCount = _slots.value.count { it.channel != null }
        val shouldCap = _cap720p.value && populatedCount >= 2
        runOnMain {
            for (i in 0 until MAX_SLOTS) {
                val ts = trackSelectors[i] ?: continue
                val builder = ts.buildUponParameters()
                if (shouldCap) builder.setMaxVideoSize(1280, 720)
                else builder.clearVideoSizeConstraints()
                ts.parameters = builder.build()
            }
        }
    }

    /** Penegak invarian audio tunggal: hanya satu slot mendapat volume 1f, lainnya 0f. */
    private fun syncVolumes() {
        runOnMain {
            val activeSlot = _activeAudioSlotIndex.value
            for (i in 0 until MAX_SLOTS) {
                players[i]?.volume = if (i == activeSlot) 1.0f else 0.0f
            }
        }
    }

    private fun findSlotIndex(player: ExoPlayer): Int = players.indexOfFirst { it === player }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun updateSlot(slotIndex: Int, transform: (MultiSlotState) -> MultiSlotState) {
        _slots.value = _slots.value.toMutableList().also { list ->
            if (slotIndex in list.indices) list[slotIndex] = transform(list[slotIndex])
        }
    }
}
