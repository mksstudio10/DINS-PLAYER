package com.dinsplayer.app.features.player

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.focus.onFocusChanged
import com.dinsplayer.app.features.mobile.tvFocusRing
import com.dinsplayer.app.features.mobile.tvMarquee
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.border
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.preferences.core.edit
import androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView
import androidx.media3.exoplayer.video.spherical.applyVrMode
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import android.text.Layout
import android.graphics.Typeface
import com.dinsplayer.app.core.player.Media3Manager
import com.dinsplayer.app.features.multistream.MultiPlayerActivity
import com.dinsplayer.app.data.KEY_AUDIO_SYNC
import com.dinsplayer.app.data.KEY_BUFFER_SIZE
import com.dinsplayer.app.data.KEY_HW_DECODING
import com.dinsplayer.app.data.settingsDataStore
import com.dinsplayer.app.data.models.PlayerState
import com.dinsplayer.app.ui.theme.AccentBlue
import com.dinsplayer.app.ui.theme.BgDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational

import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

@Composable
fun PlayerScreen(
    title: String = "",
    isPipMode: Boolean = false,
    onBack: () -> Unit,
) {
    val playerState by Media3Manager.state.collectAsState()
    val context = LocalContext.current
    val activity = context as? androidx.activity.ComponentActivity

    // Handle system back button to prevent closing the whole app
    BackHandler {
        // Reset orientation before leaving player
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onBack()
    }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var showControls     by remember { mutableStateOf(true) }
    var showSettings     by remember { mutableStateOf(false) }
    var showChannelMenu  by remember { mutableStateOf(false) }
    var showVrDialog     by remember { mutableStateOf(false) }
    var currentVrMode    by remember { mutableStateOf(VrMode.NORMAL) }
    var isLocked         by remember { mutableStateOf(false) }
    var showUnlock       by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var seekAnimation    by remember { mutableStateOf<Pair<Boolean, String>?>(null) } // Pair(isForward, text)

    // Entering PiP must drop the overlay, otherwise the translucent control layer is baked into
    // the small window. Leaving PiP needs no branch: `isPipMode` is the source of truth, so the
    // taps and gestures below come back on their own.
    LaunchedEffect(isPipMode) {
        if (isPipMode) {
            showControls = false
            showChannelMenu = false
            showSettings = false
            showVrDialog = false
        }
    }

    val qScrollState     = rememberScrollState()
    var activeTab        by remember { mutableIntStateOf(0) }

    var currentPosition  by remember { mutableLongStateOf(0L) }
    var duration         by remember { mutableLongStateOf(0L) }
    var isLive           by remember { mutableStateOf(false) }
    var isMuted          by remember { mutableStateOf(false) }
    
    val configuration    = LocalConfiguration.current
    val isLandscape      = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    
    var clockText        by remember { mutableStateOf("") }
    // Aspect ratio cycles: FIT → FILL → ZOOM
    val aspectRatioCycle = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )
    val aspectRatioLabels = listOf("Fit", "Fill", "Zoom")
    var aspectRatioIndex by remember { mutableIntStateOf(0) }
    // Read channels and active channel from shared state
    val channels by Media3Manager.activeChannels.collectAsState()
    val activeChannelId by Media3Manager.activeChannelId.collectAsState()
    val streamFormat by Media3Manager.streamFormat.collectAsState()
    
    val epgData by com.dinsplayer.app.core.epg.EpgManager.epgData.collectAsState()
    val normalizedIndex by com.dinsplayer.app.core.epg.EpgManager.normalizedIndex.collectAsState()
    
    val activeChannel = remember(activeChannelId, channels) { channels.find { it.id == activeChannelId } }
    val isLiveTvMultiEligible = channels.isNotEmpty() &&
        activeChannelId.isNotBlank() &&
        activeChannel != null &&
        !activeChannel.groupTitle.equals("Lokal", ignoreCase = true) &&
        !com.dinsplayer.app.core.player.isVodUrl(activeChannel.url)
    val openMultiViewFromPlayer: () -> Unit = {
        MultiPlayerActivity.launch(
            context = context,
            initialChannelIds = listOf(activeChannelId),
            autoPickSlot = 1,
        )
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onBack()
    }
    
    var epgNowTrigger by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(30_000L)
            epgNowTrigger = System.currentTimeMillis() / 60_000L
        }
    }
    
    val currentAndNextEpg = remember(epgData, normalizedIndex, activeChannel, epgNowTrigger) {
        if (activeChannel != null) {
            val programs = com.dinsplayer.app.core.epg.EpgManager
                .resolve(activeChannel.tvgId, activeChannel.name, epgData, normalizedIndex)
                ?: emptyList()

            val nowMillis = System.currentTimeMillis()
            // Half-open interval: at an exact boundary, two programmes both matched the
            // old inclusive range and the "now playing" card could show the wrong one.
            val curr = programs.find { nowMillis >= it.startMillis && nowMillis < it.stopMillis }
            val nxt = if (curr != null) {
                val idx = programs.indexOf(curr)
                if (idx in 0 until programs.lastIndex) programs[idx + 1] else null
            } else null
            Pair(curr, nxt)
        } else Pair(null, null)
    }
    val currentEpg = currentAndNextEpg.first
    val nextEpg = currentAndNextEpg.second
    val timeFormat = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }

    val currentChannelIndex = remember(activeChannelId, channels) {
        channels.indexOfFirst { it.id == activeChannelId }
    }

    val playChannel: (ChannelItem) -> Unit = { ch ->
        Media3Manager.setActiveChannelId(ch.id)
        Media3Manager.play(
            url           = ch.url,
            title         = ch.name,
            userAgent     = ch.userAgent,
            headers       = buildMap {
                if (ch.referer.isNotBlank()) put("Referer", ch.referer)
                if (ch.origin.isNotBlank()) put("Origin", ch.origin)
            },
            drmScheme     = ch.drmScheme,
            drmLicenseUri = ch.drmLicenseUri,
            manifestType  = ch.manifestType.takeIf { it.isNotBlank() },
        )
    }

    val playNextChannel: () -> Unit = {
        if (channels.isNotEmpty()) {
            val nextIdx = if (currentChannelIndex in 0 until channels.size - 1) currentChannelIndex + 1 else 0
            playChannel(channels[nextIdx])
        }
    }

    val playPrevChannel: () -> Unit = {
        if (channels.isNotEmpty()) {
            val prevIdx = if (currentChannelIndex > 0) currentChannelIndex - 1 else channels.size - 1
            playChannel(channels[prevIdx])
        }
    }

    val rootFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    val menuFocusRequester = remember { FocusRequester() }
    val errorRetryFocusRequester = remember { FocusRequester() }
    val unlockFocusRequester = remember { FocusRequester() }

    androidx.activity.compose.BackHandler(enabled = isLocked) {
        showUnlock = !showUnlock
        lastInteractionTime = System.currentTimeMillis()
    }

    LaunchedEffect(Unit) {
        rootFocusRequester.requestFocus()
    }

    LaunchedEffect(isLocked, showUnlock) {
        if (isLocked) {
            if (showUnlock) {
                for (delayMs in listOf(40L, 120L, 250L)) {
                    delay(delayMs)
                    if (runCatching { unlockFocusRequester.requestFocus() }.isSuccess) break
                }
            } else {
                runCatching { rootFocusRequester.requestFocus() }
            }
        }
    }

    LaunchedEffect(showControls) {
        if (showControls) {
            if (!showChannelMenu && !showSettings && !showVrDialog && !isLocked) {
                delay(120L)
                try {
                    playPauseFocusRequester.requestFocus()
                } catch (_: Exception) {}
            }
        } else if (!showChannelMenu && !showSettings && !showVrDialog && !(isLocked && showUnlock)) {
            try {
                rootFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(showChannelMenu, showSettings, showVrDialog) {
        if (showChannelMenu || showSettings || showVrDialog) {
            showControls = false
        } else {
            delay(100L)
            try {
                if (showControls) {
                    playPauseFocusRequester.requestFocus()
                } else {
                    rootFocusRequester.requestFocus()
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(playerState) {
        if (playerState is PlayerState.Error) {
            delay(150L)
            try {
                errorRetryFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    // PlayerView reference for AspectRatio control
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    val currentTitle by Media3Manager.currentTitle.collectAsState()
    val currentUrl by Media3Manager.currentUrl.collectAsState()

    // Reset deteksi dan mode saat saluran atau URL media berganti
    var is360FromTracks by remember(activeChannelId, currentUrl) { mutableStateOf(false) }

    LaunchedEffect(activeChannelId, currentUrl) {
        currentVrMode = VrMode.NORMAL
    }

    // Evaluasi apakah suatu Format video adalah format 360°/VR
    fun isFormat360(format: androidx.media3.common.Format): Boolean {
        // 1. Proyeksi spherical resmi (Google sv3d / equirectangular projectionData)
        if (format.projectionData != null) return true

        // 2. Stereo mode VR (Top-Bottom, Left-Right, Stereo Mesh)
        // Perhatian: Format non-VR defaultnya adalah Format.NO_VALUE (-1) dan MONO (0)
        val isStereoVr = format.stereoMode == androidx.media3.common.C.STEREO_MODE_TOP_BOTTOM ||
                format.stereoMode == androidx.media3.common.C.STEREO_MODE_LEFT_RIGHT ||
                format.stereoMode == androidx.media3.common.C.STEREO_MODE_STEREO_MESH
        if (isStereoVr) return true

        // 3. Rasio aspek 2:1 presisi equirectangular 360 (misal 3840x1920, 2880x1440, 1920x960)
        if (format.width > 0 && format.height > 0 && format.width >= 1920 && (format.width == 2 * format.height)) {
            return true
        }

        return false
    }

    // Deteksi metadata 360 / stereo VR dari trek video ExoPlayer & tangkap subtitle cues
    DisposableEffect(activeChannelId, currentUrl) {
        val player = Media3Manager.getPlayer()
        fun checkTracks(tracks: androidx.media3.common.Tracks) {
            var has360 = false
            for (group in tracks.groups) {
                if (group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO) {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        if (isFormat360(format)) {
                            has360 = true
                            break
                        }
                    }
                }
                if (has360) break
            }
            is360FromTracks = has360
        }

        fun normalizeCues(cues: List<Cue>): List<Cue> {
            return cues.map { cue ->
                if (cue.text != null) {
                    // Normalisasi semua cue agar selalu terfokus simetris di tengah horizontal
                    cue.buildUpon()
                        .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setPosition(0.5f)
                        .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                        .build()
                } else {
                    cue
                }
            }
        }

        player?.currentTracks?.let { checkTracks(it) }
        player?.currentCues?.let {
            playerViewRef?.subtitleView?.setCues(normalizeCues(it.cues))
        }

        val trackListener = object : androidx.media3.common.Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                checkTracks(tracks)
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0 && videoSize.width >= 1920 && (videoSize.width == 2 * videoSize.height)) {
                    is360FromTracks = true
                }
            }
            override fun onCues(cueGroup: CueGroup) {
                val normalized = normalizeCues(cueGroup.cues)
                playerViewRef?.subtitleView?.setCues(normalized)
            }
        }
        player?.addListener(trackListener)
        onDispose {
            player?.removeListener(trackListener)
            playerViewRef?.subtitleView?.setCues(emptyList())
        }
    }

    // Heuristik deteksi cerdas jika judul atau URL mengindikasikan video 360/VR
    // (Aman dari bentrok kata resolusi 360p seperti 360p-av1 atau 360p.mp4)
    val vrRegex = remember {
        Regex("""(?i)(360\s*(?:°|deg|degree|panorama)|360\s*[-_]?vr|vr\s*[-_]?360|180\s*[-_]?vr|vr\s*[-_]?180|equirectangular|spherical|\bvr\s*(?:box|mode|video|experience)\b|(?<!\w)vr(?!\w)|(?<!\w)360(?![a-zA-Z0-9]))""")
    }

    val isHeuristic360 = remember(title, currentTitle, activeChannel, currentUrl) {
        val combined = "$title $currentTitle ${activeChannel?.name ?: ""} $currentUrl"
        vrRegex.containsMatchIn(combined)
    }

    val is360Detected = is360FromTracks || isHeuristic360

    // Saat kembali ke NORMAL, pastikan PlayerView mendapat player kembali
    LaunchedEffect(currentVrMode) {
        if (currentVrMode == VrMode.NORMAL) {
            playerViewRef?.player = Media3Manager.getPlayer()
        }
    }

    // Update clock every second
    LaunchedEffect(Unit) {
        val days   = arrayOf("Min", "Sen", "Sel", "Rab", "Kam", "Jum", "Sab")
        val months = arrayOf("Jan","Feb","Mar","Apr","Mei","Jun","Jul","Agu","Sep","Okt","Nov","Des")
        while (coroutineContext.isActive) {
            val now   = java.time.LocalDateTime.now()
            val day   = days[now.dayOfWeek.value % 7]        // Mon=1..Sun=7 → index 1..0
            val month = months[now.monthValue - 1]
            clockText = "%s, %02d %s %04d | %02d:%02d:%02d".format(
                day, now.dayOfMonth, month, now.year,
                now.hour, now.minute, now.second
            )
            delay(1000L)
        }
    }

    val scope = rememberCoroutineScope()

    // Hide system bars (status + nav) in fullscreen/landscape; restore in portrait
    DisposableEffect(isLandscape) {
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (isLandscape) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            // Restore bars when leaving PlayerScreen
            val w = activity?.window ?: return@onDispose
            WindowInsetsControllerCompat(w, w.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Auto-hide controls or unlock button after 3.5 seconds of no interaction
    LaunchedEffect(showControls, showUnlock, lastInteractionTime) {
        if (showControls || showUnlock) {
            delay(3500L)
            if (!showSettings && !showChannelMenu) {
                showControls = false
                showUnlock = false
            }
        }
    }
    
    // Auto-hide seek animation
    LaunchedEffect(seekAnimation) {
        if (seekAnimation != null) {
            delay(800L)
            seekAnimation = null
        }
    }

    // Poll ExoPlayer position every 500ms
    LaunchedEffect(Unit) {
        while (coroutineContext.isActive) {
            val player = Media3Manager.getPlayer()
            if (player != null) {
                currentPosition = player.currentPosition.coerceAtLeast(0L)
                duration        = player.duration.coerceAtLeast(0L)
                // Live detection via ExoPlayer's own signal (matches zentaratv_lite)
                isLive          = player.isCurrentMediaItemLive
                if (!is360FromTracks) {
                    for (group in player.currentTracks.groups) {
                        if (group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO) {
                            for (i in 0 until group.length) {
                                val f = group.getTrackFormat(i)
                                if (isFormat360(f)) {
                                    is360FromTracks = true
                                    break
                                }
                            }
                        }
                        if (is360FromTracks) break
                    }
                    val videoSize = player.videoSize
                    if (videoSize.width > 0 && videoSize.height > 0 && videoSize.width >= 1920 && (videoSize.width == 2 * videoSize.height)) {
                        is360FromTracks = true
                    }
                }
            }
            delay(500L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    lastInteractionTime = System.currentTimeMillis()

                    when {
                        showVrDialog -> {
                            if (event.key == Key.Back || event.key == Key.Escape) {
                                showVrDialog = false
                                return@onPreviewKeyEvent true
                            }
                            return@onPreviewKeyEvent false
                        }
                        showSettings -> {
                            if (event.key == Key.Back || event.key == Key.Escape) {
                                showSettings = false
                                activeTab = 0
                                return@onPreviewKeyEvent true
                            }
                            return@onPreviewKeyEvent false
                        }
                        showChannelMenu -> {
                            if (event.key == Key.Back || event.key == Key.Escape) {
                                showChannelMenu = false
                                return@onPreviewKeyEvent true
                            }
                            return@onPreviewKeyEvent false
                        }
                        isLocked -> {
                            if (!showUnlock) {
                                showUnlock = true
                                lastInteractionTime = System.currentTimeMillis()
                                return@onPreviewKeyEvent true
                            }
                            when (event.key) {
                                Key.DirectionCenter, Key.Enter -> {
                                    isLocked = false
                                    showUnlock = false
                                    showControls = true
                                    lastInteractionTime = System.currentTimeMillis()
                                    return@onPreviewKeyEvent true
                                }
                                Key.Back, Key.Escape -> {
                                    showUnlock = false
                                    return@onPreviewKeyEvent true
                                }
                                else -> {
                                    lastInteractionTime = System.currentTimeMillis()
                                    runCatching { unlockFocusRequester.requestFocus() }
                                    return@onPreviewKeyEvent true
                                }
                            }
                        }
                        !showControls -> {
                            when (event.key) {
                                Key.DirectionCenter, Key.Enter -> {
                                    showControls = true
                                    return@onPreviewKeyEvent true
                                }
                                Key.DirectionUp -> {
                                    showChannelMenu = true
                                    return@onPreviewKeyEvent true
                                }
                                Key.DirectionDown -> {
                                    showControls = true
                                    return@onPreviewKeyEvent true
                                }
                                Key.DirectionLeft -> {
                                    if (!isLive && duration > 0) {
                                        val p = Media3Manager.getPlayer()
                                        if (p != null) {
                                            val newPos = (p.currentPosition - 10_000L).coerceAtLeast(0L)
                                            p.seekTo(newPos)
                                            seekAnimation = Pair(false, "-10s")
                                        }
                                    } else {
                                        playPrevChannel()
                                    }
                                    showControls = true
                                    return@onPreviewKeyEvent true
                                }
                                Key.DirectionRight -> {
                                    if (!isLive && duration > 0) {
                                        val p = Media3Manager.getPlayer()
                                        if (p != null) {
                                            val newPos = (p.currentPosition + 10_000L).coerceAtMost(duration)
                                            p.seekTo(newPos)
                                            seekAnimation = Pair(true, "+10s")
                                        }
                                    } else {
                                        playNextChannel()
                                    }
                                    showControls = true
                                    return@onPreviewKeyEvent true
                                }
                                Key.MediaPlay, Key.MediaPause, Key.MediaPlayPause -> {
                                    val isPlaying = playerState is PlayerState.Playing
                                    if (isPlaying) Media3Manager.pause() else Media3Manager.resume()
                                    showControls = true
                                    return@onPreviewKeyEvent true
                                }
                                Key.MediaNext, Key.ChannelUp -> {
                                    playNextChannel()
                                    showControls = true
                                    return@onPreviewKeyEvent true
                                }
                                Key.MediaPrevious, Key.ChannelDown -> {
                                    playPrevChannel()
                                    showControls = true
                                    return@onPreviewKeyEvent true
                                }
                                Key.Back, Key.Escape -> {
                                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                    onBack()
                                    return@onPreviewKeyEvent true
                                }
                                else -> return@onPreviewKeyEvent false
                            }
                        }
                        else -> {
                            if (event.key == Key.Back || event.key == Key.Escape) {
                                showControls = false
                                return@onPreviewKeyEvent true
                            }
                            if (event.key == Key.MediaPlayPause) {
                                val isPlaying = playerState is PlayerState.Playing
                                if (isPlaying) Media3Manager.pause() else Media3Manager.resume()
                                return@onPreviewKeyEvent true
                            }
                            if (event.key == Key.ChannelUp || event.key == Key.MediaNext) {
                                playNextChannel()
                                return@onPreviewKeyEvent true
                            }
                            if (event.key == Key.ChannelDown || event.key == Key.MediaPrevious) {
                                playPrevChannel()
                                return@onPreviewKeyEvent true
                            }
                            return@onPreviewKeyEvent false
                        }
                    }
                }
                false
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        lastInteractionTime = System.currentTimeMillis()
                        if (showChannelMenu) {
                            showChannelMenu = false
                        } else if (!isPipMode) {
                            if (isLocked) {
                                showUnlock = !showUnlock
                            } else {
                                showControls = !showControls
                                if (showControls) showSettings = false
                            }
                        }
                    },
                    onDoubleTap = { offset ->
                        if (!isLocked && !showChannelMenu && !isPipMode) {
                            lastInteractionTime = System.currentTimeMillis()
                            val player = Media3Manager.getPlayer() ?: return@detectTapGestures
                            if (player.isCurrentMediaItemLive || player.duration == androidx.media3.common.C.TIME_UNSET) return@detectTapGestures

                            val isForward = offset.x > size.width.toFloat() / 2f
                            val seekBy = 10_000L
                            if (isForward) {
                                player.seekTo((player.currentPosition + seekBy).coerceAtMost(player.duration))
                                seekAnimation = Pair(true, "+10s")
                            } else {
                                player.seekTo((player.currentPosition - seekBy).coerceAtLeast(0L))
                                seekAnimation = Pair(false, "-10s")
                            }
                        }
                    }
                )
            }
    ) {
        // Video surface
        val videoHeightFraction by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (showChannelMenu && !isLandscape) 0.35f else 1f,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = if (showChannelMenu) 280 else 240,
                easing = if (showChannelMenu) androidx.compose.animation.core.FastOutSlowInEasing else androidx.compose.animation.core.FastOutLinearInEasing
            ),
            label = "videoHeight"
        )
        
        if (currentVrMode == VrMode.NORMAL) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = Media3Manager.getPlayer()
                        useController = false
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        subtitleView?.apply {
                            setUserDefaultStyle()
                            setUserDefaultTextSize()
                            setStyle(
                                CaptionStyleCompat(
                                    android.graphics.Color.WHITE,
                                    android.graphics.Color.TRANSPARENT,
                                    android.graphics.Color.TRANSPARENT,
                                    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                    android.graphics.Color.BLACK,
                                    Typeface.DEFAULT_BOLD
                                )
                            )
                        }
                        playerViewRef = this
                    }
                },
                update = { pv ->
                    pv.resizeMode = aspectRatioCycle[aspectRatioIndex]
                    if (pv.player == null) {
                        pv.player = Media3Manager.getPlayer()
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .fillMaxHeight(videoHeightFraction)
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    SphericalGLSurfaceView(ctx).apply {
                        // Copot player dari PlayerView agar tidak rebutan surface
                        playerViewRef?.player = null
                        // VideoSurfaceListener: begitu GL surface siap, pasang ke player
                        addVideoSurfaceListener(object : SphericalGLSurfaceView.VideoSurfaceListener {
                            override fun onVideoSurfaceCreated(surface: android.view.Surface) {
                                val p = Media3Manager.getPlayer() ?: return
                                p.setVideoSurface(surface)
                                // Pasang listener frame setelah surface terpasang
                                p.setVideoFrameMetadataListener(videoFrameMetadataListener)
                                p.setCameraMotionListener(cameraMotionListener)
                            }
                            override fun onVideoSurfaceDestroyed(surface: android.view.Surface) {
                                val p = Media3Manager.getPlayer() ?: return
                                p.clearVideoFrameMetadataListener(videoFrameMetadataListener)
                                p.clearCameraMotionListener(cameraMotionListener)
                                p.clearVideoSurface()
                            }
                        })
                        applyVrMode(currentVrMode)
                    }
                },
                update = { vrView ->
                    vrView.applyVrMode(currentVrMode)
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .fillMaxHeight(videoHeightFraction)
            )
        }



        // VR Mode Split-Screen Center Divider & Reticle Crosshairs
        if (currentVrMode == VrMode.VR_SPLIT) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .width(3.dp)
                    .fillMaxHeight(videoHeightFraction)
                    .background(Color.Black)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(0.5f)
                    .fillMaxHeight(videoHeightFraction),
                contentAlignment = Alignment.Center
            ) {
                Text("+", color = Color.White.copy(alpha = 0.3f), fontSize = 24.sp, fontWeight = FontWeight.Light)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxWidth(0.5f)
                    .fillMaxHeight(videoHeightFraction),
                contentAlignment = Alignment.Center
            ) {
                Text("+", color = Color.White.copy(alpha = 0.3f), fontSize = 24.sp, fontWeight = FontWeight.Light)
            }
        }

        // Loading overlay
        if (playerState is PlayerState.Loading) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .fillMaxHeight(videoHeightFraction),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = title.ifEmpty { "Memuat..." },
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        }

        // Error overlay
        if (playerState is PlayerState.Error) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .fillMaxHeight(videoHeightFraction),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.ErrorOutline, null, tint = Color.Red, modifier = Modifier.size(48.dp))
                    Text(
                        text = title.ifBlank { activeChannel?.name ?: "Stream" },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Text(
                        text = (playerState as PlayerState.Error).message,
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                val ch = activeChannel
                                if (ch != null) {
                                    playChannel(ch)
                                } else if (currentUrl.isNotBlank()) {
                                    Media3Manager.play(url = currentUrl, title = currentTitle)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .tvFocusRing(RoundedCornerShape(10.dp))
                                .focusRequester(errorRetryFocusRequester)
                        ) {
                            Text("Coba Lagi", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(
                            onClick = onBack,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.tvFocusRing(RoundedCornerShape(10.dp))
                        ) {
                            Text("Kembali", color = Color.White.copy(alpha = 0.8f))
                        }
                    }
                }
            }
        }

        // Seek Animation Overlay
        seekAnimation?.let { anim ->
            androidx.compose.animation.AnimatedVisibility(
                visible = true,
                enter = fadeIn(animationSpec = tween(120)),
                exit  = fadeOut(animationSpec = tween(150)),
                modifier = Modifier
                    .align(if (anim.first) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 48.dp)
                    .fillMaxHeight(videoHeightFraction)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        if (anim.first) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(48.dp)
                    )
                    Text(anim.second, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }

        // Lock Screen Unlock Button
        if (isLocked) {
            androidx.compose.animation.AnimatedVisibility(
                visible = showUnlock,
                enter = fadeIn(animationSpec = tween(150)),
                exit  = fadeOut(animationSpec = tween(200)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                var isUnlockFocused by remember { mutableStateOf(false) }
                IconButton(
                    onClick = { 
                        isLocked = false
                        showUnlock = false
                        showControls = true
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    modifier = Modifier
                        .size(68.dp)
                        .focusRequester(unlockFocusRequester)
                        .onFocusChanged { isUnlockFocused = it.isFocused || it.hasFocus }
                        .border(
                            width = if (isUnlockFocused) 2.5.dp else 1.5.dp,
                            color = if (isUnlockFocused) Color.White else AccentBlue.copy(alpha = 0.7f),
                            shape = CircleShape
                        )
                        .clip(CircleShape)
                        .background(
                            if (isUnlockFocused) AccentBlue.copy(alpha = 0.45f)
                            else Color.Black.copy(alpha = 0.72f)
                        )
                ) {
                    Icon(Icons.Filled.LockOpen, "Buka Kunci", tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }


        // Controls overlay

        AnimatedVisibility(
            visible = showControls && !showChannelMenu && !isLocked,
            enter = fadeIn(animationSpec = tween(180, easing = androidx.compose.animation.core.LinearEasing)),
            exit  = fadeOut(animationSpec = tween(220, easing = androidx.compose.animation.core.LinearEasing)),
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .fillMaxHeight(videoHeightFraction)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Gradient top scrim
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                            )
                        )
                )

                // Gradient bottom scrim
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                            )
                        )
                )

                // Top bar: date/time centered
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .align(Alignment.TopCenter),
                ) {
                    // Clock centered
                    Text(
                        text = clockText,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                // LIVE dot (top-left) & Channel Title & EPG Schedule (with '<' button to the left in Portrait mode)
                val displayChannelTitle = title.ifBlank { activeChannel?.name ?: currentTitle }
                val hasChannelInfo = displayChannelTitle.isNotBlank() || isLive || streamFormat.isNotEmpty() || currentEpg != null
                if (!isLandscape || hasChannelInfo) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(start = 12.dp, top = if (isLandscape) 16.dp else 26.dp, end = 16.dp)
                            .fillMaxWidth(if (isLandscape) 0.5f else 0.92f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (!isLandscape) {
                            IconButton(
                                onClick = {
                                    Media3Manager.stop()
                                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                    onBack()
                                },
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.45f))
                                    .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                                    .tvFocusRing(CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ChevronLeft,
                                    contentDescription = "Kembali",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }

                        if (hasChannelInfo) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (isLive) {
                                        Box(Modifier.size(7.dp).background(Color.Red, RoundedCornerShape(50)))
                                    }
                                    val liveText = if (isLive) "LIVE" else ""
                                    val separator = if (isLive && streamFormat.isNotEmpty()) " • " else ""
                                    Text(
                                        text = "$liveText$separator$streamFormat",
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // Judul Channel
                                if (displayChannelTitle.isNotBlank()) {
                                    Text(
                                        text = displayChannelTitle,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Clip,
                                        modifier = Modifier.tvMarquee()
                                    )
                                }
                                
                                // EPG Sedang Tayang di bawah judul channel
                                if (currentEpg != null) {
                                    val currTime = "${timeFormat.format(java.util.Date(currentEpg.startMillis))}-${timeFormat.format(java.util.Date(currentEpg.stopMillis))}"
                                    Text(
                                        text = "Sedang Tayang: ${currentEpg.title} ($currTime)",
                                        color = Color.White.copy(alpha = 0.9f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Clip,
                                        modifier = Modifier.tvMarquee()
                                    )
                                    
                                    if (nextEpg != null) {
                                        val nextTime = "${timeFormat.format(java.util.Date(nextEpg.startMillis))}-${timeFormat.format(java.util.Date(nextEpg.stopMillis))}"
                                        Text(
                                            text = "Selanjutnya: ${nextEpg.title} ($nextTime)",
                                            color = Color.White.copy(alpha = 0.7f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Clip,
                                            modifier = Modifier.tvMarquee()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom controls — two rows
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Bottom controls: portrait=two rows, landscape=single row
                    val isPlaying  = playerState is PlayerState.Playing
                    val ctrlBg     = Color.White.copy(alpha = 0.14f)
                    val pillBg     = Color.White.copy(alpha = 0.14f)
                    val activeMenuBg = AccentBlue.copy(alpha = 0.25f)
                    val ctrlSize   = 40.dp
                    val iconSize   = 20.dp

                    // VOD progress bar (portrait and landscape)
                    if (!isLive && duration > 0) {
                        val progress = (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatTime(currentPosition),
                                color = Color.White,
                                fontSize = 11.sp,
                                modifier = Modifier.width(42.dp)
                            )
                            @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                            Slider(
                                value = progress,
                                onValueChange = { frac -> Media3Manager.getPlayer()?.seekTo((frac * duration).toLong()) },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp)
                                    .height(24.dp), // Slimmer slider height
                                thumb = {
                                    androidx.compose.material3.SliderDefaults.Thumb(
                                        interactionSource = remember { MutableInteractionSource() },
                                        colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = AccentBlue),
                                        modifier = Modifier.size(12.dp) // Smaller thumb
                                    )
                                },
                                track = { sliderState ->
                                    androidx.compose.material3.SliderDefaults.Track(
                                        colors = androidx.compose.material3.SliderDefaults.colors(
                                            activeTrackColor = AccentBlue,
                                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                        ),
                                        sliderState = sliderState,
                                        modifier = Modifier.height(3.dp) // Thinner track
                                    )
                                }
                            )
                            Text(
                                text = formatTime(duration),
                                color = Color.White,
                                fontSize = 11.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                modifier = Modifier.width(42.dp)
                            )
                        }
                    }

                    if (isLandscape) {
                        // LANDSCAPE / TV: single unified row with tvFocusRing on every control
                        val lsCtrlSize = 44.dp
                        val lsIconSize = 22.dp
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Left: Back (<) + Channel Menu
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        Media3Manager.stop()
                                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                        onBack()
                                    },
                                    modifier = Modifier
                                        .size(lsCtrlSize)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.45f))
                                        .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                                        .tvFocusRing(CircleShape)
                                ) {
                                    Icon(
                                        Icons.Filled.ChevronLeft,
                                        contentDescription = "Kembali",
                                        tint = Color.White,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }

                                IconButton(
                                    onClick = { showChannelMenu = !showChannelMenu },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (showChannelMenu) activeMenuBg else pillBg)
                                        .tvFocusRing(RoundedCornerShape(12.dp))
                                        .focusRequester(menuFocusRequester)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.FormatListBulleted,
                                        contentDescription = "Menu Saluran",
                                        tint = if (showChannelMenu) AccentBlue else Color.White,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }

                            // Center: Prev Channel · Play/Pause · Next Channel
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                IconButton(
                                    onClick = playPrevChannel,
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (channels.isNotEmpty()) pillBg else pillBg.copy(alpha = 0.5f))
                                        .tvFocusRing(RoundedCornerShape(12.dp))
                                ) {
                                    Icon(
                                        Icons.Filled.SkipPrevious,
                                        contentDescription = "Saluran Sebelumnya",
                                        tint = Color.White.copy(alpha = if (channels.isNotEmpty()) 1f else 0.4f),
                                        modifier = Modifier.size(26.dp)
                                    )
                                }

                                IconButton(
                                    onClick = { if (isPlaying) Media3Manager.pause() else Media3Manager.resume() },
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(pillBg)
                                        .tvFocusRing(RoundedCornerShape(14.dp))
                                        .focusRequester(playPauseFocusRequester)
                                ) {
                                    if (playerState is PlayerState.Loading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            color = Color.White,
                                            strokeWidth = 2.5.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                            contentDescription = if (isPlaying) "Jeda" else "Putar",
                                            tint = Color.White,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = playNextChannel,
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (channels.isNotEmpty()) pillBg else pillBg.copy(alpha = 0.5f))
                                        .tvFocusRing(RoundedCornerShape(12.dp))
                                ) {
                                    Icon(
                                        Icons.Filled.SkipNext,
                                        contentDescription = "Saluran Berikutnya",
                                        tint = Color.White.copy(alpha = if (channels.isNotEmpty()) 1f else 0.4f),
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }

                            // Right: Utilities (Aspect ratio, VR, Lock, PiP, Settings)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                IconButton(
                                    onClick = { aspectRatioIndex = (aspectRatioIndex + 1) % aspectRatioCycle.size },
                                    modifier = Modifier
                                        .size(lsCtrlSize)
                                        .clip(CircleShape)
                                        .background(ctrlBg)
                                        .tvFocusRing(CircleShape)
                                ) {
                                    Icon(
                                        Icons.Filled.AspectRatio,
                                        contentDescription = "Rasio: ${aspectRatioLabels[aspectRatioIndex]}",
                                        tint = AccentBlue,
                                        modifier = Modifier.size(lsIconSize)
                                    )
                                }

                                if (is360Detected || currentVrMode != VrMode.NORMAL) {
                                    IconButton(
                                        onClick = { showVrDialog = true },
                                        modifier = Modifier
                                            .size(lsCtrlSize)
                                            .clip(CircleShape)
                                            .background(if (currentVrMode != VrMode.NORMAL) AccentBlue.copy(alpha = 0.35f) else ctrlBg)
                                            .tvFocusRing(CircleShape)
                                    ) {
                                        Icon(
                                            Icons.Filled.Vrpano,
                                            contentDescription = "Mode 360 / VR",
                                            tint = if (currentVrMode != VrMode.NORMAL) AccentBlue else Color.White,
                                            modifier = Modifier.size(lsIconSize)
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        isLocked = true
                                        showControls = false
                                        showUnlock = true
                                        lastInteractionTime = System.currentTimeMillis()
                                    },
                                    modifier = Modifier
                                        .size(lsCtrlSize)
                                        .clip(CircleShape)
                                        .background(ctrlBg)
                                        .tvFocusRing(CircleShape)
                                ) {
                                    Icon(
                                        Icons.Filled.Lock,
                                        contentDescription = "Kunci",
                                        tint = Color.White,
                                        modifier = Modifier.size(lsIconSize)
                                    )
                                }

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                                    IconButton(
                                        onClick = {
                                            try {
                                                activity?.enterPictureInPictureMode(
                                                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                                                )
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        },
                                        modifier = Modifier
                                            .size(lsCtrlSize)
                                            .clip(CircleShape)
                                            .background(ctrlBg)
                                            .tvFocusRing(CircleShape)
                                    ) {
                                        Icon(
                                            Icons.Filled.PictureInPictureAlt,
                                            contentDescription = "PiP",
                                            tint = Color.White,
                                            modifier = Modifier.size(lsIconSize)
                                        )
                                    }
                                }

                                // Multi TV - hanya tampil saat Live TV aktif
                                if (isLiveTvMultiEligible) {
                                    IconButton(
                                        onClick = openMultiViewFromPlayer,
                                        modifier = Modifier
                                            .size(lsCtrlSize)
                                            .clip(CircleShape)
                                            .background(ctrlBg)
                                            .tvFocusRing(CircleShape)
                                    ) {
                                        Icon(
                                            Icons.Filled.Dashboard,
                                            contentDescription = "Multi TV",
                                            tint = Color.White,
                                            modifier = Modifier.size(lsIconSize)
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = { showSettings = !showSettings },
                                    modifier = Modifier
                                        .size(lsCtrlSize)
                                        .clip(CircleShape)
                                        .background(ctrlBg)
                                        .tvFocusRing(CircleShape)
                                ) {
                                    Icon(
                                        Icons.Filled.Settings,
                                        contentDescription = "Setelan",
                                        tint = Color.White,
                                        modifier = Modifier.size(lsIconSize)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                    },
                                    modifier = Modifier
                                        .size(lsCtrlSize)
                                        .clip(CircleShape)
                                        .background(ctrlBg)
                                        .tvFocusRing(CircleShape)
                                ) {
                                    Icon(
                                        Icons.Filled.FullscreenExit,
                                        contentDescription = "Keluar Layar Penuh",
                                        tint = Color.White,
                                        modifier = Modifier.size(lsIconSize)
                                    )
                                }
                            }
                        }
                    } else {
                        // Row 1: utility icons (centered, grouped)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = { aspectRatioIndex = (aspectRatioIndex + 1) % aspectRatioCycle.size },
                                modifier = Modifier.size(ctrlSize).clip(CircleShape).background(ctrlBg).tvFocusRing(CircleShape)
                            ) {
                                Icon(Icons.Filled.AspectRatio, aspectRatioLabels[aspectRatioIndex], tint = AccentBlue, modifier = Modifier.size(iconSize))
                            }
                            if (is360Detected || currentVrMode != VrMode.NORMAL) {
                                Spacer(Modifier.width(14.dp))
                                IconButton(
                                    onClick = { showVrDialog = true },
                                    modifier = Modifier.size(ctrlSize).clip(CircleShape)
                                        .background(if (currentVrMode != VrMode.NORMAL) AccentBlue.copy(alpha = 0.35f) else ctrlBg)
                                        .tvFocusRing(CircleShape)
                                ) {
                                    Icon(Icons.Filled.Vrpano, "Mode 360 / VR", tint = if (currentVrMode != VrMode.NORMAL) AccentBlue else Color.White, modifier = Modifier.size(iconSize))
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            IconButton(
                                onClick = {
                                    isLocked = true
                                    showControls = false
                                    showUnlock = true
                                    lastInteractionTime = System.currentTimeMillis()
                                },
                                modifier = Modifier.size(ctrlSize).clip(CircleShape).background(ctrlBg).tvFocusRing(CircleShape)
                            ) {
                                Icon(Icons.Filled.Lock, "Kunci", tint = Color.White, modifier = Modifier.size(iconSize))
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                                Spacer(Modifier.width(14.dp))
                                IconButton(
                                    onClick = {
                                        try {
                                            activity?.enterPictureInPictureMode(
                                                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                                            )
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    },
                                    modifier = Modifier.size(ctrlSize).clip(CircleShape).background(ctrlBg).tvFocusRing(CircleShape)
                                ) {
                                    Icon(Icons.Filled.PictureInPictureAlt, "PiP", tint = Color.White, modifier = Modifier.size(iconSize))
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            // Multi TV - hanya tampil saat Live TV aktif
                            if (isLiveTvMultiEligible) {
                                IconButton(
                                    onClick = openMultiViewFromPlayer,
                                    modifier = Modifier.size(ctrlSize).clip(CircleShape).background(ctrlBg).tvFocusRing(CircleShape)
                                ) {
                                    Icon(Icons.Filled.Dashboard, "Multi TV", tint = Color.White, modifier = Modifier.size(iconSize))
                                }
                                Spacer(Modifier.width(14.dp))
                            }
                            IconButton(
                                onClick = { showSettings = !showSettings },
                                modifier = Modifier.size(ctrlSize).clip(CircleShape).background(ctrlBg).tvFocusRing(CircleShape)
                            ) {
                                Icon(Icons.Filled.Settings, "Setelan", tint = Color.White, modifier = Modifier.size(iconSize))
                            }
                            Spacer(Modifier.width(14.dp))
                            AndroidView(
                                factory = { context ->
                                    MediaRouteButton(context).apply { CastButtonFactory.setUpMediaRouteButton(context, this) }
                                },
                                modifier = Modifier.size(ctrlSize).clip(CircleShape).background(ctrlBg).tvFocusRing(CircleShape)
                            )
                            Spacer(Modifier.width(14.dp))
                            IconButton(
                                onClick = { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE },
                                modifier = Modifier.size(ctrlSize).clip(CircleShape).background(ctrlBg).tvFocusRing(CircleShape)
                            ) {
                                Icon(Icons.Filled.Fullscreen, "Layar Penuh", tint = Color.White, modifier = Modifier.size(iconSize))
                            }
                        }
                        
                        Spacer(Modifier.height(16.dp))

                        // Row 2: Menu | Prev · Play · Next
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        ) {
                            // Menu button on the bottom-left
                            IconButton(
                                onClick = { showChannelMenu = !showChannelMenu },
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (showChannelMenu) activeMenuBg else pillBg)
                                    .tvFocusRing(RoundedCornerShape(12.dp))
                            ) {
                                Icon(Icons.AutoMirrored.Filled.FormatListBulleted, "Menu", tint = if (showChannelMenu) AccentBlue else Color.White, modifier = Modifier.size(26.dp))
                            }

                            // Prev · Play · Next perfectly centered
                            Row(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = {
                                        if (channels.isNotEmpty() && currentChannelIndex > 0) {
                                            val prev = channels[currentChannelIndex - 1]
                                            Media3Manager.setActiveChannelId(prev.id)
                                            Media3Manager.play(
                                                url = prev.url,
                                                title = prev.name,
                                                userAgent = prev.userAgent,
                                                headers = buildMap {
                                                    if (prev.referer.isNotBlank()) put("Referer", prev.referer)
                                                    if (prev.origin.isNotBlank()) put("Origin", prev.origin)
                                                },
                                                drmScheme = prev.drmScheme,
                                                drmLicenseUri = prev.drmLicenseUri,
                                                manifestType = prev.manifestType.takeIf { it.isNotBlank() },
                                            )
                                        }
                                    },
                                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                                        .background(if (channels.isNotEmpty() && currentChannelIndex > 0) pillBg else pillBg.copy(alpha = 0.5f))
                                        .tvFocusRing(RoundedCornerShape(12.dp))
                                ) {
                                    Icon(Icons.Filled.SkipPrevious, "Sebelumnya", tint = Color.White.copy(alpha = if (channels.isNotEmpty() && currentChannelIndex > 0) 1f else 0.4f), modifier = Modifier.size(24.dp))
                                }
                                
                                IconButton(
                                    onClick = { if (isPlaying) Media3Manager.pause() else Media3Manager.resume() },
                                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(pillBg).tvFocusRing(RoundedCornerShape(14.dp))
                                ) {
                                    if (playerState is PlayerState.Loading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(22.dp),
                                            color = Color.White,
                                            strokeWidth = 2.5.dp
                                        )
                                    } else {
                                        Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (isPlaying) "Jeda" else "Putar", tint = Color.White, modifier = Modifier.size(30.dp))
                                    }
                                }
                                
                                IconButton(
                                    onClick = {
                                        if (channels.isNotEmpty() && currentChannelIndex < channels.size - 1) {
                                            val next = channels[currentChannelIndex + 1]
                                            Media3Manager.setActiveChannelId(next.id)
                                            Media3Manager.play(
                                                url = next.url,
                                                title = next.name,
                                                userAgent = next.userAgent,
                                                headers = buildMap {
                                                    if (next.referer.isNotBlank()) put("Referer", next.referer)
                                                    if (next.origin.isNotBlank()) put("Origin", next.origin)
                                                },
                                                drmScheme = next.drmScheme,
                                                drmLicenseUri = next.drmLicenseUri,
                                                manifestType = next.manifestType.takeIf { it.isNotBlank() },
                                            )
                                        }
                                    },
                                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                                        .background(if (channels.isNotEmpty() && currentChannelIndex < channels.size - 1) pillBg else pillBg.copy(alpha = 0.5f))
                                        .tvFocusRing(RoundedCornerShape(12.dp))
                                ) {
                                    Icon(Icons.Filled.SkipNext, "Berikutnya", tint = Color.White.copy(alpha = if (channels.isNotEmpty() && currentChannelIndex < channels.size - 1) 1f else 0.4f), modifier = Modifier.size(24.dp))
                                }
                            }
                        }

                        // Bottom padding for portrait
                        Spacer(Modifier.height(16.dp))
                    } // end if-else landscape/portrait
                } // end Column
            } // end Box(fillMaxSize) in AnimatedVisibility
        } // end AnimatedVisibility

        // Channel menu overlay (opens when Menu button is pressed)
        PlayerChannelMenu(
            visible         = showChannelMenu,
            channels        = channels,
            // Pass the real id. Deriving it from currentChannelIndex highlighted row 0
            // whenever the playing channel was missing from the list.
            activeChannelId = activeChannelId,
            isLandscape     = isLandscape,
            onChannelClick  = { ch ->
                Media3Manager.setActiveChannelId(ch.id)
                Media3Manager.play(
                    url           = ch.url,
                    title         = ch.name,
                    userAgent     = ch.userAgent,
                    headers       = buildMap {
                        if (ch.referer.isNotBlank()) put("Referer", ch.referer)
                        if (ch.origin.isNotBlank()) put("Origin", ch.origin)
                    },
                    drmScheme     = ch.drmScheme,
                    drmLicenseUri = ch.drmLicenseUri,
                    manifestType  = ch.manifestType.takeIf { it.isNotBlank() },
                )
            },
            onDismiss       = { showChannelMenu = false },
        )

        // Quality Selector Dialog
        if (showSettings) {
            val exoPlayer = Media3Manager.getPlayer()

            if (exoPlayer != null) {
                val tabFocusRequesters = remember { List(4) { FocusRequester() } }
                val firstOptionFocusRequester = remember { FocusRequester() }
                val closeSettingsFocusRequester = remember { FocusRequester() }

                LaunchedEffect(Unit) {
                    for (delayMs in listOf(50L, 120L, 250L, 400L)) {
                        delay(delayMs)
                        val targetTab = activeTab.coerceIn(0, 3)
                        if (runCatching { tabFocusRequesters[targetTab].requestFocus() }.isSuccess) {
                            break
                        }
                    }
                }

                Dialog(
                    onDismissRequest = { showSettings = false; activeTab = 0 },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // Use pointerInput instead of clickable so the backdrop never steals D-pad focus
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    showSettings = false
                                    activeTab = 0
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(min = 320.dp, max = 520.dp)
                                .fillMaxWidth(0.85f)
                                .background(BgDialog, RoundedCornerShape(20.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
                                .padding(24.dp)
                                .verticalScroll(qScrollState)
                                .pointerInput(Unit) {
                                    detectTapGestures { /* consume taps inside dialog panel */ }
                                }
                        ) {
                            val videoTracks = exoPlayer.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
                            val audioTracks = exoPlayer.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                            val textTracks = exoPlayer.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }

                            val videoTrackList = mutableListOf<Triple<androidx.media3.common.TrackGroup, Int, androidx.media3.common.Format>>()
                            videoTracks.forEach { g -> val grp = g.mediaTrackGroup; for (i in 0 until grp.length) videoTrackList.add(Triple(grp, i, grp.getFormat(i))) }

                            val audioTrackList = mutableListOf<Pair<Triple<androidx.media3.common.TrackGroup, Int, androidx.media3.common.Format>, Boolean>>()
                            audioTracks.forEach { g -> val grp = g.mediaTrackGroup; for (i in 0 until grp.length) audioTrackList.add(Pair(Triple(grp, i, grp.getFormat(i)), g.isTrackSelected(i))) }

                            val textTrackList = mutableListOf<Pair<Triple<androidx.media3.common.TrackGroup, Int, androidx.media3.common.Format>, Boolean>>()
                            textTracks.forEach { g -> val grp = g.mediaTrackGroup; for (i in 0 until grp.length) textTrackList.add(Pair(Triple(grp, i, grp.getFormat(i)), g.isTrackSelected(i))) }

                            val currentParams = exoPlayer.trackSelectionParameters
                            val hasVideoOverride = currentParams.overrides.keys.any { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
                            val hasAudioOverride = currentParams.overrides.keys.any { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                            val hasTextOverride = currentParams.overrides.keys.any { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
                            val textDisabled = currentParams.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT)

                            val currentTabHasOptions = when (activeTab) {
                                0 -> true
                                1 -> audioTrackList.isNotEmpty()
                                2 -> textTrackList.isNotEmpty()
                                3 -> true
                                else -> false
                            }

                            // Header
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Settings, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Pengaturan Kualitas & Audio",
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { showSettings = false; activeTab = 0 },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .focusRequester(closeSettingsFocusRequester)
                                        .onPreviewKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                                val targetTab = activeTab.coerceIn(0, 3)
                                                runCatching { tabFocusRequesters[targetTab].requestFocus() }
                                                return@onPreviewKeyEvent true
                                            }
                                            false
                                        }
                                        .tvFocusRing(CircleShape)
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Tutup",
                                        tint = Color.White.copy(alpha = 0.75f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // Tabs
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val tabs = listOf("Video", "Audio", "Subtitle", "Mode VR")
                                tabs.forEachIndexed { index, label ->
                                    val modeColor = AccentBlue
                                    val isSelectedTab = activeTab == index
                                    var isTabFocused by remember { mutableStateOf(false) }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .focusRequester(tabFocusRequesters[index])
                                            .onFocusChanged { isTabFocused = it.isFocused || it.hasFocus }
                                            .border(
                                                width = if (isTabFocused) 2.5.dp else if (isSelectedTab) 1.dp else 0.dp,
                                                color = if (isTabFocused) Color.White else if (isSelectedTab) modeColor else Color.Transparent,
                                                shape = RoundedCornerShape(9.dp)
                                            )
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(
                                                when {
                                                    isTabFocused  -> modeColor.copy(alpha = 0.42f)
                                                    isSelectedTab -> modeColor.copy(alpha = 0.22f)
                                                    else          -> Color.Transparent
                                                }
                                            )
                                            .onPreviewKeyEvent { event ->
                                                if (event.type == KeyEventType.KeyDown) {
                                                    when (event.key) {
                                                        Key.DirectionLeft -> {
                                                            if (index > 0) {
                                                                activeTab = index - 1
                                                                runCatching { tabFocusRequesters[index - 1].requestFocus() }
                                                            }
                                                            return@onPreviewKeyEvent true
                                                        }
                                                        Key.DirectionRight -> {
                                                            if (index < tabs.lastIndex) {
                                                                activeTab = index + 1
                                                                runCatching { tabFocusRequesters[index + 1].requestFocus() }
                                                            }
                                                            return@onPreviewKeyEvent true
                                                        }
                                                        Key.DirectionDown -> {
                                                            if (currentTabHasOptions) {
                                                                runCatching { firstOptionFocusRequester.requestFocus() }
                                                            }
                                                            return@onPreviewKeyEvent true
                                                        }
                                                        Key.DirectionUp -> {
                                                            runCatching { closeSettingsFocusRequester.requestFocus() }
                                                            return@onPreviewKeyEvent true
                                                        }
                                                        else -> {}
                                                    }
                                                }
                                                false
                                            }
                                            .clickable { activeTab = index }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            label,
                                            color = if (isTabFocused) Color.White else if (isSelectedTab) modeColor else Color.White.copy(alpha = 0.65f),
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelectedTab || isTabFocused) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            @Composable
                            fun TrackOptionRow(
                                label: String,
                                isSelected: Boolean,
                                isFirst: Boolean = false,
                                onClick: () -> Unit
                            ) {
                                var isRowFocused by remember { mutableStateOf(false) }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .then(if (isFirst) Modifier.focusRequester(firstOptionFocusRequester) else Modifier)
                                        .onFocusChanged { isRowFocused = it.isFocused || it.hasFocus }
                                        .border(
                                            width = if (isRowFocused) 2.5.dp else if (isSelected) 1.dp else 0.dp,
                                            color = if (isRowFocused) Color.White else if (isSelected) AccentBlue.copy(alpha = 0.5f) else Color.Transparent,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            when {
                                                isRowFocused -> AccentBlue.copy(alpha = 0.35f)
                                                isSelected   -> AccentBlue.copy(alpha = 0.18f)
                                                else         -> Color.Transparent
                                            }
                                        )
                                        .onPreviewKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && isFirst) {
                                                val targetTab = activeTab.coerceIn(0, 3)
                                                runCatching { tabFocusRequesters[targetTab].requestFocus() }
                                                return@onPreviewKeyEvent true
                                            }
                                            false
                                        }
                                        .selectable(selected = isSelected, onClick = onClick)
                                        .padding(horizontal = 12.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isRowFocused) Color.White else if (isSelected) AccentBlue else Color.White.copy(alpha = 0.5f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        label,
                                        color = if (isSelected || isRowFocused) Color.White else Color.White.copy(alpha = 0.78f),
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected || isRowFocused) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            when (activeTab) {
                                0 -> {
                                    TrackOptionRow("Otomatis (ABR)", isSelected = !hasVideoOverride, isFirst = true) {
                                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO).build()
                                        showSettings = false
                                    }
                                    videoTrackList.forEach { triple ->
                                        val group = triple.first; val trackIndex = triple.second; val format = triple.third
                                        val isSelected = hasVideoOverride && currentParams.overrides[group]?.trackIndices?.contains(trackIndex) == true
                                        val label = buildString { if (format.height > 0) append("${format.height}p"); if (format.bitrate > 0) append(" (${format.bitrate / 1000} kbps)"); if (isEmpty()) append("Track ${trackIndex + 1}") }
                                        TrackOptionRow(label, isSelected) {
                                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO).addOverride(androidx.media3.common.TrackSelectionOverride(group, listOf(trackIndex))).build()
                                            showSettings = false
                                        }
                                    }
                                }
                                1 -> {
                                    if (audioTrackList.isNotEmpty()) {
                                        TrackOptionRow("Otomatis (Default)", isSelected = !hasAudioOverride, isFirst = true) {
                                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO).build()
                                            showSettings = false
                                        }
                                        audioTrackList.forEach { tuple ->
                                            val group = tuple.first.first; val trackIndex = tuple.first.second; val format = tuple.first.third
                                            val isSelected = if (hasAudioOverride) currentParams.overrides[group]?.trackIndices?.contains(trackIndex) == true else tuple.second
                                            val label = buildString { append(format.language?.uppercase() ?: "Audio ${trackIndex + 1}"); if (format.channelCount > 0) append(" (${format.channelCount}ch)"); if (format.bitrate > 0) append(" · ${format.bitrate / 1000}kbps") }
                                            TrackOptionRow(label, isSelected) {
                                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO).addOverride(androidx.media3.common.TrackSelectionOverride(group, listOf(trackIndex))).build()
                                                showSettings = false
                                            }
                                        }
                                    } else {
                                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                            Text("Tidak ada pilihan trek audio tambahan", color = Color.Gray, fontSize = 14.sp)
                                        }
                                    }
                                }
                                2 -> {
                                    if (textTrackList.isNotEmpty()) {
                                        TrackOptionRow("Nonaktif", isSelected = textDisabled, isFirst = true) {
                                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT).setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build()
                                            showSettings = false
                                        }
                                        textTrackList.forEach { tuple ->
                                            val group = tuple.first.first; val trackIndex = tuple.first.second; val format = tuple.first.third
                                            val isSelected = !textDisabled && if (hasTextOverride) currentParams.overrides[group]?.trackIndices?.contains(trackIndex) == true else tuple.second
                                            val label = buildString { append(format.language?.uppercase() ?: "Subtitle ${trackIndex + 1}"); if (!format.label.isNullOrBlank()) append(" · ${format.label}") }
                                            TrackOptionRow(label, isSelected) {
                                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false).clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT).addOverride(androidx.media3.common.TrackSelectionOverride(group, listOf(trackIndex))).build()
                                                showSettings = false
                                            }
                                        }
                                    } else {
                                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                            Text("Tidak ada pilihan subtitle", color = Color.Gray, fontSize = 14.sp)
                                        }
                                    }
                                }
                                3 -> {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (is360Detected) AccentBlue.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f),
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                if (is360Detected) Icons.Filled.CheckCircle else Icons.Filled.Info,
                                                contentDescription = null,
                                                tint = if (is360Detected) AccentBlue else Color.White.copy(alpha = 0.6f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                if (is360Detected) "Format 360° terdeteksi otomatis dari stream video."
                                                else "Metadata 360° tidak terdeteksi otomatis. Anda tetap dapat mengaktifkan mode VR di bawah ini.",
                                                color = Color.White.copy(alpha = 0.85f),
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp
                                            )
                                        }
                                    }

                                    val vrModes = listOf(
                                        Pair(VrMode.NORMAL, "2D Standar (Nonaktif)"),
                                        Pair(VrMode.TOUCH, "Mode Sentuh (Geser Layar 360°)"),
                                        Pair(VrMode.MOTION, "Mode Sensor (Gyroscope Ponsel)"),
                                        Pair(VrMode.VR_SPLIT, "Mode VR Box (Layar Terbelah Kiri / Kanan)")
                                    )
                                    vrModes.forEachIndexed { idx, (mode, label) ->
                                        val isSelected = currentVrMode == mode
                                        TrackOptionRow(label, isSelected, isFirst = idx == 0) {
                                            if (mode == VrMode.VR_SPLIT) {
                                                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                            }
                                            currentVrMode = mode
                                            showSettings = false
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 360° / VR Mode Selection Dialog
        if (showVrDialog) {
            VrModeDialog(
                currentMode = currentVrMode,
                is360Detected = is360Detected,
                onSelectMode = { mode ->
                    if (mode == VrMode.VR_SPLIT) {
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }
                    currentVrMode = mode
                },
                onDismiss = { showVrDialog = false }
            )
        }
    } // end outer Box
}

@Composable
fun VrModeDialog(
    currentMode: VrMode,
    is360Detected: Boolean = false,
    onSelectMode: (VrMode) -> Unit,
    onDismiss: () -> Unit
) {
    val activeModeFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        for (delayMs in listOf(50L, 120L, 250L, 400L)) {
            delay(delayMs)
            if (runCatching { activeModeFocusRequester.requestFocus() }.isSuccess) {
                break
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .pointerInput(Unit) {
                    detectTapGestures { onDismiss() }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 320.dp, max = 500.dp)
                    .fillMaxWidth(0.9f)
                    .background(BgDialog, RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
                    .padding(20.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { /* consume taps inside dialog panel */ }
                    }
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(AccentBlue.copy(alpha = 0.18f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Vrpano,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Mode Tampilan & 360°",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (is360Detected) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(AccentBlue.copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        "360° AKTIF",
                                        color = AccentBlue,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Text(
                            if (is360Detected) "Format 360° terdeteksi dari stream" else "Pilih sudut pandang video player",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp).tvFocusRing(CircleShape)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Tutup",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                val modes = listOf(
                    Triple(VrMode.NORMAL, Icons.Filled.Tv, "2D"),
                    Triple(VrMode.TOUCH, Icons.Filled.TouchApp, "SENTUH"),
                    Triple(VrMode.MOTION, Icons.Filled.ScreenRotation, "SENSOR"),
                    Triple(VrMode.VR_SPLIT, Icons.Filled.ViewInAr, "VR BOX")
                )

                modes.forEach { (mode, icon, badge) ->
                    val isSelected = currentMode == mode
                    var isRowFocused by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .then(if (isSelected) Modifier.focusRequester(activeModeFocusRequester) else Modifier)
                            .onFocusChanged { isRowFocused = it.isFocused || it.hasFocus }
                            .border(
                                width = if (isRowFocused) 2.5.dp else 1.dp,
                                color = if (isRowFocused) Color.White else if (isSelected) AccentBlue else Color.White.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                when {
                                    isRowFocused -> AccentBlue.copy(alpha = 0.35f)
                                    isSelected   -> AccentBlue.copy(alpha = 0.18f)
                                    else         -> Color.White.copy(alpha = 0.04f)
                                }
                            )
                            .clickable {
                                onSelectMode(mode)
                                onDismiss()
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(
                                    if (isSelected || isRowFocused) AccentBlue.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                                    RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = if (isRowFocused) Color.White else if (isSelected) AccentBlue else Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    mode.title,
                                    color = if (isSelected || isRowFocused) Color.White else Color.White.copy(alpha = 0.9f),
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected || isRowFocused) FontWeight.Bold else FontWeight.SemiBold
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (isSelected || isRowFocused) AccentBlue.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.1f),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        badge,
                                        color = if (isRowFocused) Color.White else if (isSelected) AccentBlue else Color.White.copy(alpha = 0.6f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                mode.description,
                                color = if (isRowFocused) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        Icon(
                            imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isRowFocused) Color.White else if (isSelected) AccentBlue else Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .tvFocusRing(RoundedCornerShape(12.dp))
                ) {
                    Text("Tutup", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}
private fun formatTime(ms: Long): String {
    val seconds = (ms / 1000) % 60
    val minutes = (ms / 60000) % 60
    val hours   = ms / 3600000
    return if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}
