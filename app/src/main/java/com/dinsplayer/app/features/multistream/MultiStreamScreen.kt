@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.dinsplayer.app.features.multistream

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dinsplayer.app.R
import com.dinsplayer.app.core.player.Media3Manager
import com.dinsplayer.app.core.player.MultiLayoutMode
import com.dinsplayer.app.core.player.MultiSlotState
import com.dinsplayer.app.core.player.MultiStreamManager
import com.dinsplayer.app.data.models.PlayerState
import com.dinsplayer.app.features.mobile.tvFocusRing
import com.dinsplayer.app.features.mobile.tvMarquee
import com.dinsplayer.app.features.player.ChannelItem
import com.dinsplayer.app.features.player.PlayerChannelMenu
import com.dinsplayer.app.ui.theme.AccentBlue
import com.dinsplayer.app.ui.theme.AccentBlueDim
import com.dinsplayer.app.ui.theme.AccentBlueSoft
import com.dinsplayer.app.ui.theme.BgBase
import com.dinsplayer.app.ui.theme.BgDeep
import com.dinsplayer.app.ui.theme.BgDialog
import com.dinsplayer.app.ui.theme.BgElevated
import com.dinsplayer.app.ui.theme.BgSurface
import com.dinsplayer.app.ui.theme.ColorError
import com.dinsplayer.app.ui.theme.DividerColor
import com.dinsplayer.app.ui.theme.TextMuted
import com.dinsplayer.app.ui.theme.TextPrimary
import com.dinsplayer.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MultiStreamScreen(
    initialAutoPickSlot: Int = -1,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val isTv = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    val slots by MultiStreamManager.slots.collectAsState()
    val layoutMode by MultiStreamManager.layoutMode.collectAsState()
    val activeAudioSlot by MultiStreamManager.activeAudioSlotIndex.collectAsState()
    val cap720p by MultiStreamManager.cap720p.collectAsState()
    val allChannels by Media3Manager.activeChannels.collectAsState()

    var showHud by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var showUnlockHint by remember { mutableStateOf(false) }
    var unlockHintTimestamp by remember { mutableLongStateOf(0L) }
    var selectedSlotIndex by remember { mutableStateOf<Int?>(null) }
    var showExitDialog by remember { mutableStateOf(false) }
    var hudLastShownTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    var channelPickerSlot by remember { mutableStateOf<Int?>(null) }
    var lastPickerSlot by remember {
        mutableIntStateOf(if (initialAutoPickSlot in 0 until MultiStreamManager.MAX_SLOTS) initialAutoPickSlot else 0)
    }
    var clockText by remember { mutableStateOf("") }

    val unlockFocusRequester = remember { FocusRequester() }

    LaunchedEffect(initialAutoPickSlot) {
        if (initialAutoPickSlot in 0 until MultiStreamManager.MAX_SLOTS &&
            slots.getOrNull(initialAutoPickSlot)?.channel == null
        ) {
            lastPickerSlot = initialAutoPickSlot
            channelPickerSlot = initialAutoPickSlot
        }
    }

    LaunchedEffect(channelPickerSlot) {
        channelPickerSlot?.let { lastPickerSlot = it }
    }

    LaunchedEffect(Unit) {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (isActive) {
            clockText = formatter.format(Date())
            delay(15_000L)
        }
    }

    LaunchedEffect(showHud, hudLastShownTime, showExitDialog, channelPickerSlot, isLocked) {
        if (showHud && !showExitDialog && channelPickerSlot == null && !isLocked) {
            delay(5000L)
            if (System.currentTimeMillis() - hudLastShownTime >= 4900L &&
                !showExitDialog && channelPickerSlot == null
            ) {
                showHud = false
            }
        }
    }

    LaunchedEffect(showUnlockHint, unlockHintTimestamp, isLocked) {
        if (isLocked && showUnlockHint) {
            runCatching { unlockFocusRequester.requestFocus() }
            delay(3500L)
            if (System.currentTimeMillis() - unlockHintTimestamp >= 3400L) {
                showUnlockHint = false
            }
        }
    }

    fun showHudBriefly() {
        hudLastShownTime = System.currentTimeMillis()
        showHud = true
    }

    fun triggerUnlockHint() {
        unlockHintTimestamp = System.currentTimeMillis()
        showUnlockHint = true
    }

    fun handleExitRequest() {
        val hasActiveSlot = slots.any { it.channel != null }
        if (hasActiveSlot) {
            showExitDialog = true
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            onBack()
        }
    }

    fun openFullscreenChannel(ch: ChannelItem) {
        Media3Manager.init(context.applicationContext)
        MultiStreamManager.releaseAll()
        val headers = buildMap<String, String> {
            if (ch.referer.isNotBlank()) put("Referer", ch.referer)
            if (ch.origin.isNotBlank()) put("Origin", ch.origin)
        }
        Media3Manager.setActiveChannelId(ch.id)
        Media3Manager.play(
            url = ch.url,
            title = ch.name,
            userAgent = ch.userAgent,
            headers = headers,
            drmScheme = ch.drmScheme?.takeIf { it.isNotBlank() },
            drmLicenseUri = ch.drmLicenseUri,
            manifestType = ch.manifestType,
        )
        Media3Manager.launchPlayer(context)
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onBack()
    }

    BackHandler {
        when {
            channelPickerSlot != null -> channelPickerSlot = null
            showExitDialog -> showExitDialog = false
            isLocked -> {
                if (showUnlockHint) {
                    isLocked = false
                    showUnlockHint = false
                    showHudBriefly()
                } else {
                    triggerUnlockHint()
                }
            }
            else -> handleExitRequest()
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
    ) {
        MultiStreamLayout(
            layoutMode = layoutMode,
            isLandscape = isLandscape,
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
        ) { slotIndex ->
            val slot = slots.getOrNull(slotIndex) ?: return@MultiStreamLayout
            StreamTile(
                slotIndex = slotIndex,
                slotState = slot,
                isSelected = selectedSlotIndex == slotIndex,
                hasChannelsAvailable = allChannels.isNotEmpty(),
                showSlotBadge = showHud,
                isLocked = isLocked,
                onTapEmpty = {
                    if (isLocked) {
                        triggerUnlockHint()
                    } else {
                        selectedSlotIndex = slotIndex
                        channelPickerSlot = slotIndex
                    }
                },
                onTapOccupied = {
                    if (isLocked) {
                        triggerUnlockHint()
                    } else {
                        selectedSlotIndex = slotIndex
                        MultiStreamManager.selectAudioSlot(slotIndex)
                        showHudBriefly()
                    }
                },
                onDoubleTapOccupied = {
                    if (!isLocked) {
                        if (layoutMode == MultiLayoutMode.FOCUS_3 && slotIndex != 0) {
                            MultiStreamManager.swapSlots(slotIndex, 0)
                            selectedSlotIndex = 0
                        } else {
                            MultiStreamManager.toggleResizeMode(slotIndex)
                            selectedSlotIndex = slotIndex
                        }
                        showHudBriefly()
                    }
                },
                onLongPressOccupied = {
                    if (isLocked) {
                        triggerUnlockHint()
                    } else {
                        selectedSlotIndex = slotIndex
                        showHudBriefly()
                    }
                },
                onOpenPicker = {
                    if (!isLocked) {
                        selectedSlotIndex = slotIndex
                        channelPickerSlot = slotIndex
                    }
                },
                onRetry = {
                    if (!isLocked) {
                        slot.channel?.let { MultiStreamManager.playChannel(slotIndex, it) }
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = showHud && !isLocked && channelPickerSlot == null,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(260)),
        ) {
            MultiHud(
                selectedSlotIndex = selectedSlotIndex,
                slots = slots,
                layoutMode = layoutMode,
                cap720p = cap720p,
                activeAudioSlot = activeAudioSlot,
                clockText = clockText,
                showRotateButton = !isTv,
                isLandscape = isLandscape,
                onBack = { handleExitRequest() },
                onToggleRotate = {
                    showHudBriefly()
                    activity?.requestedOrientation = if (isLandscape) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                },
                onLockScreen = {
                    isLocked = true
                    showHud = false
                    triggerUnlockHint()
                },
                onSelectLayout = { mode ->
                    MultiStreamManager.setLayout(mode)
                    if (selectedSlotIndex != null && selectedSlotIndex!! >= mode.slotCount) {
                        selectedSlotIndex = null
                    }
                    showHudBriefly()
                },
                onToggleSlotAudio = { idx ->
                    MultiStreamManager.toggleAudioMute(idx)
                    showHudBriefly()
                },
                onMuteAll = {
                    MultiStreamManager.muteAll()
                    showHudBriefly()
                },
                onToggleCap720p = {
                    MultiStreamManager.setCap720p(!cap720p)
                    showHudBriefly()
                },
                onReplaceChannel = { idx ->
                    channelPickerSlot = idx
                },
                onMakePrimary = { idx ->
                    if (idx != 0) {
                        MultiStreamManager.swapSlots(idx, 0)
                        selectedSlotIndex = 0
                        showHudBriefly()
                    }
                },
                onToggleResizeMode = { idx ->
                    MultiStreamManager.toggleResizeMode(idx)
                    showHudBriefly()
                },
                onFullscreenSlot = { idx ->
                    slots.getOrNull(idx)?.channel?.let { openFullscreenChannel(it) }
                },
                onCloseSlot = { idx ->
                    MultiStreamManager.closeSlot(idx)
                    if (selectedSlotIndex == idx) selectedSlotIndex = null
                    showHudBriefly()
                },
            )
        }

        // Unlock pill shown while screen is locked
        AnimatedVisibility(
            visible = isLocked && showUnlockHint,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(220)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(BgElevated.copy(alpha = 0.94f))
                    .border(1.dp, DividerColor, RoundedCornerShape(24.dp))
                    .focusRequester(unlockFocusRequester)
                    .tvFocusRing(RoundedCornerShape(24.dp))
                    .clickable {
                        isLocked = false
                        showUnlockHint = false
                        showHudBriefly()
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.LockOpen,
                    contentDescription = "Buka kunci layar",
                    tint = AccentBlue,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Layar Terkunci - Ketuk untuk Buka",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (showExitDialog) {
            SlotExitConfirmDialog(
                onConfirm = {
                    showExitDialog = false
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    onBack()
                },
                onDismiss = { showExitDialog = false },
            )
        }

        // Keep mounted so AnimatedVisibility slide/fade transitions play smoothly
        PlayerChannelMenu(
            visible = channelPickerSlot != null,
            channels = allChannels,
            activeChannelId = slots.getOrNull(lastPickerSlot)?.channel?.id ?: "",
            isLandscape = isLandscape,
            headerLabel = "Pilih Saluran - Slot ${lastPickerSlot + 1}",
            onChannelClick = { ch ->
                MultiStreamManager.playChannel(lastPickerSlot, ch)
                if (MultiStreamManager.activeAudioSlotIndex.value == null) {
                    MultiStreamManager.selectAudioSlot(lastPickerSlot)
                }
                selectedSlotIndex = lastPickerSlot
                channelPickerSlot = null
                showHudBriefly()
            },
            onDismiss = { channelPickerSlot = null },
        )
    }
}

@Composable
fun MultiStreamLayout(
    layoutMode: MultiLayoutMode,
    isLandscape: Boolean,
    modifier: Modifier = Modifier,
    tiles: @Composable (Int) -> Unit,
) {
    val gap = 6.dp
    when (layoutMode) {
        MultiLayoutMode.SPLIT_2 -> {
            if (isLandscape) {
                Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { tiles(0) }
                    Box(Modifier.weight(1f).fillMaxHeight()) { tiles(1) }
                }
            } else {
                Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { tiles(0) }
                    Box(Modifier.weight(1f).fillMaxWidth()) { tiles(1) }
                }
            }
        }
        MultiLayoutMode.FOCUS_3 -> {
            if (isLandscape) {
                Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Box(Modifier.weight(2f).fillMaxHeight()) { tiles(0) }
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { tiles(1) }
                        Box(Modifier.weight(1f).fillMaxWidth()) { tiles(2) }
                    }
                }
            } else {
                Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
                    Box(Modifier.weight(2f).fillMaxWidth()) { tiles(0) }
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { tiles(1) }
                        Box(Modifier.weight(1f).fillMaxHeight()) { tiles(2) }
                    }
                }
            }
        }
        MultiLayoutMode.GRID_4 -> {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { tiles(0) }
                    Box(Modifier.weight(1f).fillMaxHeight()) { tiles(1) }
                }
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { tiles(2) }
                    Box(Modifier.weight(1f).fillMaxHeight()) { tiles(3) }
                }
            }
        }
    }
}

@Composable
fun StreamTile(
    slotIndex: Int,
    slotState: MultiSlotState,
    isSelected: Boolean,
    hasChannelsAvailable: Boolean,
    showSlotBadge: Boolean,
    isLocked: Boolean,
    onTapEmpty: () -> Unit,
    onTapOccupied: () -> Unit,
    onDoubleTapOccupied: () -> Unit,
    onLongPressOccupied: () -> Unit,
    onOpenPicker: () -> Unit,
    onRetry: () -> Unit,
) {
    val tileShape = RoundedCornerShape(12.dp)
    val isOccupied = slotState.channel != null

    val borderWidth = when {
        slotState.isAudioActive -> 2.5.dp
        isSelected && !isLocked -> 1.5.dp
        else -> 1.dp
    }
    val borderColor = when {
        slotState.isAudioActive -> AccentBlue
        isSelected && !isLocked -> AccentBlueDim
        else -> DividerColor
    }

    val interactionModifier = if (isOccupied) {
        Modifier.combinedClickable(
            onClick = onTapOccupied,
            onDoubleClick = onDoubleTapOccupied,
            onLongClick = onLongPressOccupied,
        )
    } else {
        Modifier.combinedClickable(
            onClick = onTapEmpty,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(tileShape)
            .border(borderWidth, borderColor, tileShape)
            .tvFocusRing(tileShape)
            .then(interactionModifier)
    ) {
        if (!isOccupied) {
            SlotEmptyCard(
                slotIndex = slotIndex,
                hasChannelsAvailable = hasChannelsAvailable,
            )
        } else {
            val channel = slotState.channel!!
            when (val state = slotState.playerState) {
                is PlayerState.Error -> {
                    SlotErrorCard(
                        channelName = channel.name,
                        message = state.message,
                        onRetry = onRetry,
                        onReplace = onOpenPicker,
                    )
                }
                else -> {
                    PlayerTileView(
                        slotIndex = slotIndex,
                        resizeMode = slotState.resizeMode,
                    )
                    if (state is PlayerState.Loading || state is PlayerState.Idle || state is PlayerState.Paused) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = AccentBlue,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                    // Bottom scrim with channel label
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomStart)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.80f))
                                )
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = channel.name,
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier
                                    .weight(1f)
                                    .tvMarquee(),
                            )
                            if (slotState.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                                TileMicroTag(label = "ZOOM", accent = false)
                            }
                        }
                    }
                }
            }
        }

        // Top-left slot number tag when HUD visible or occupied error
        if (showSlotBadge || isOccupied) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isSelected && !isLocked) AccentBlue.copy(alpha = 0.90f)
                        else BgDeep.copy(alpha = 0.72f)
                    )
                    .border(
                        width = 0.5.dp,
                        color = if (isSelected && !isLocked) AccentBlue else DividerColor,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "${slotIndex + 1}",
                    color = TextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // Top-right audio indicator badge when slot is unmuted
        if (slotState.isAudioActive) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(AccentBlue)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Audio aktif",
                    tint = Color.White,
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    text = "AUDIO",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun TileMicroTag(label: String, accent: Boolean) {
    Text(
        text = label,
        color = if (accent) Color.White else TextSecondary,
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (accent) AccentBlue else BgElevated.copy(alpha = 0.85f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun PlayerTileView(
    slotIndex: Int,
    resizeMode: Int,
) {
    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.multi_player_texture_view, null) as PlayerView).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                isFocusable = false
                isFocusableInTouchMode = false
                this.resizeMode = resizeMode
            }
        },
        update = { playerView ->
            val exoPlayer = MultiStreamManager.getPlayer(slotIndex)
            if (playerView.player != exoPlayer) {
                playerView.player = exoPlayer
            }
            if (playerView.resizeMode != resizeMode) {
                playerView.resizeMode = resizeMode
            }
        },
        onRelease = { playerView ->
            playerView.player = null
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun SlotEmptyCard(
    slotIndex: Int,
    hasChannelsAvailable: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(BgSurface)
                    .border(1.dp, DividerColor, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Tambah saluran di Slot ${slotIndex + 1}",
                    tint = AccentBlue,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = "Slot ${slotIndex + 1}",
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (hasChannelsAvailable) "Ketuk untuk tambah saluran" else "Buka daftar Live TV dulu",
                color = TextMuted,
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SlotErrorCard(
    channelName: String,
    message: String,
    onRetry: () -> Unit,
    onReplace: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = ColorError,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = channelName,
                color = TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = message,
                color = TextSecondary,
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactActionPill(
                    icon = Icons.Default.Refresh,
                    label = "Coba Lagi",
                    accent = true,
                    onClick = onRetry,
                )
                CompactActionPill(
                    icon = Icons.Default.SwapHoriz,
                    label = "Ganti",
                    accent = false,
                    onClick = onReplace,
                )
            }
        }
    }
}

@Composable
private fun CompactActionPill(
    icon: ImageVector,
    label: String,
    accent: Boolean,
    onClick: () -> Unit,
) {
    val pillShape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .clip(pillShape)
            .background(if (accent) AccentBlueSoft else BgSurface)
            .border(1.dp, if (accent) AccentBlue else DividerColor, pillShape)
            .tvFocusRing(pillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (accent) AccentBlue else TextPrimary,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = label,
            color = if (accent) AccentBlue else TextPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
fun MultiHud(
    selectedSlotIndex: Int?,
    slots: List<MultiSlotState>,
    layoutMode: MultiLayoutMode,
    cap720p: Boolean,
    activeAudioSlot: Int?,
    clockText: String,
    showRotateButton: Boolean,
    isLandscape: Boolean,
    onBack: () -> Unit,
    onToggleRotate: () -> Unit,
    onLockScreen: () -> Unit,
    onSelectLayout: (MultiLayoutMode) -> Unit,
    onToggleSlotAudio: (Int) -> Unit,
    onMuteAll: () -> Unit,
    onToggleCap720p: () -> Unit,
    onReplaceChannel: (Int) -> Unit,
    onMakePrimary: (Int) -> Unit,
    onToggleResizeMode: (Int) -> Unit,
    onFullscreenSlot: (Int) -> Unit,
    onCloseSlot: (Int) -> Unit,
) {
    val populatedCount = slots.take(layoutMode.slotCount).count { it.channel != null }
    val selectedSlot = selectedSlotIndex?.takeIf { it in 0 until layoutMode.slotCount }?.let { idx ->
        slots.getOrNull(idx)?.takeIf { it.channel != null }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Top floating control bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BgDeep.copy(alpha = 0.88f))
                    .border(1.dp, DividerColor, RoundedCornerShape(14.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.tvFocusRing(CircleShape)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Kembali",
                        tint = TextPrimary,
                    )
                }
                Text(
                    text = "Multi TV",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$populatedCount/${layoutMode.slotCount}",
                    color = AccentBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AccentBlueSoft)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
                Spacer(Modifier.weight(1f))
                if (clockText.isNotEmpty()) {
                    Text(
                        text = clockText,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                if (showRotateButton) {
                    IconButton(onClick = onToggleRotate, modifier = Modifier.tvFocusRing(CircleShape)) {
                        Icon(
                            Icons.Default.ScreenRotation,
                            contentDescription = if (isLandscape) "Mode Potret" else "Mode Lanskap",
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                IconButton(onClick = onLockScreen, modifier = Modifier.tvFocusRing(CircleShape)) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Kunci Layar",
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // Bottom dock: Selected slot action bar + Layout & Audio dock
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selectedSlot != null) {
                SelectedSlotActionBar(
                    slotState = selectedSlot,
                    showMakePrimary = layoutMode == MultiLayoutMode.FOCUS_3 && selectedSlot.slotIndex != 0,
                    onToggleAudio = { onToggleSlotAudio(selectedSlot.slotIndex) },
                    onReplace = { onReplaceChannel(selectedSlot.slotIndex) },
                    onMakePrimary = { onMakePrimary(selectedSlot.slotIndex) },
                    onToggleResize = { onToggleResizeMode(selectedSlot.slotIndex) },
                    onFullscreen = { onFullscreenSlot(selectedSlot.slotIndex) },
                    onClose = { onCloseSlot(selectedSlot.slotIndex) },
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BgDeep.copy(alpha = 0.90f))
                    .border(1.dp, DividerColor, RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Layout switcher + Mute All + Max 720p
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MultiLayoutMode.entries.forEach { mode ->
                        val active = mode == layoutMode
                        val modeIcon = when (mode) {
                            MultiLayoutMode.SPLIT_2 -> Icons.Default.ViewAgenda
                            MultiLayoutMode.FOCUS_3 -> Icons.Default.Dashboard
                            MultiLayoutMode.GRID_4 -> Icons.Default.GridView
                        }
                        DockToggleChip(
                            icon = modeIcon,
                            label = mode.label,
                            active = active,
                            onClick = { onSelectLayout(mode) },
                        )
                    }

                    DockToggleChip(
                        icon = if (activeAudioSlot == null) {
                            Icons.AutoMirrored.Filled.VolumeOff
                        } else {
                            Icons.AutoMirrored.Filled.VolumeUp
                        },
                        label = "Bisu Semua",
                        active = activeAudioSlot == null,
                        onClick = onMuteAll,
                    )

                    DockToggleChip(
                        icon = Icons.Default.Hd,
                        label = "Max 720p",
                        active = cap720p,
                        onClick = onToggleCap720p,
                    )
                }

                // Single-Audio exclusivity selector row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (i in 0 until layoutMode.slotCount) {
                        val slot = slots.getOrNull(i)
                        val isAudio = activeAudioSlot == i
                        val isSlotFocused = selectedSlotIndex == i
                        val slotChannelName = slot?.channel?.name
                        val shape = RoundedCornerShape(10.dp)

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(shape)
                                .background(
                                    when {
                                        isAudio -> AccentBlueSoft
                                        isSlotFocused -> BgElevated
                                        else -> BgSurface.copy(alpha = 0.85f)
                                    }
                                )
                                .border(
                                    width = if (isAudio) 1.5.dp else 1.dp,
                                    color = if (isAudio) AccentBlue else DividerColor,
                                    shape = shape,
                                )
                                .tvFocusRing(shape)
                                .clickable {
                                    if (slot?.channel == null) {
                                        onReplaceChannel(i)
                                    } else {
                                        onToggleSlotAudio(i)
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        if (isAudio) {
                                            Icons.AutoMirrored.Filled.VolumeUp
                                        } else {
                                            Icons.AutoMirrored.Filled.VolumeOff
                                        },
                                        contentDescription = null,
                                        tint = if (isAudio) AccentBlue else TextMuted,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Text(
                                        text = "Slot ${i + 1}",
                                        fontSize = 10.sp,
                                        fontWeight = if (isAudio) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (isAudio) AccentBlue else TextPrimary,
                                        maxLines = 1,
                                    )
                                }
                                Text(
                                    text = slotChannelName ?: "Kosong",
                                    fontSize = 9.sp,
                                    color = if (isAudio) TextPrimary else TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DockToggleChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (active) AccentBlueSoft else BgSurface)
            .border(1.dp, if (active) AccentBlue else DividerColor, shape)
            .tvFocusRing(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) AccentBlue else TextSecondary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            color = if (active) AccentBlue else TextSecondary,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun SelectedSlotActionBar(
    slotState: MultiSlotState,
    showMakePrimary: Boolean,
    onToggleAudio: () -> Unit,
    onReplace: () -> Unit,
    onMakePrimary: () -> Unit,
    onToggleResize: () -> Unit,
    onFullscreen: () -> Unit,
    onClose: () -> Unit,
) {
    val barShape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(barShape)
            .background(BgElevated.copy(alpha = 0.94f))
            .border(1.dp, DividerColor, barShape)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Slot ${slotState.slotIndex + 1}",
            color = AccentBlue,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        slotState.channel?.name?.let { name ->
            Text(
                text = name,
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        CompactActionPill(
            icon = if (slotState.isAudioActive) {
                Icons.AutoMirrored.Filled.VolumeUp
            } else {
                Icons.AutoMirrored.Filled.VolumeOff
            },
            label = if (slotState.isAudioActive) "Bisukan" else "Audio",
            accent = slotState.isAudioActive,
            onClick = onToggleAudio,
        )
        CompactActionPill(
            icon = Icons.Default.SwapHoriz,
            label = "Ganti",
            accent = false,
            onClick = onReplace,
        )
        if (showMakePrimary) {
            CompactActionPill(
                icon = Icons.Default.Star,
                label = "Utama",
                accent = false,
                onClick = onMakePrimary,
            )
        }
        CompactActionPill(
            icon = Icons.Default.AspectRatio,
            label = if (slotState.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) "Zoom" else "Fit",
            accent = slotState.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            onClick = onToggleResize,
        )
        CompactActionPill(
            icon = Icons.Default.Fullscreen,
            label = "Layar Penuh",
            accent = false,
            onClick = onFullscreen,
        )
        CompactActionPill(
            icon = Icons.Default.Close,
            label = "Tutup",
            accent = false,
            onClick = onClose,
        )
    }
}

@Composable
fun SlotExitConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keluar Multi TV?") },
        text = { Text("Semua siaran pada Multi TV akan dihentikan.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Keluar", color = ColorError, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        },
        containerColor = BgDialog,
    )
}
