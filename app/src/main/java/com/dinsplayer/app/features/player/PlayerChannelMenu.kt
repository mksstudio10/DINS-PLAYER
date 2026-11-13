package com.dinsplayer.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import com.dinsplayer.app.R
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.dinsplayer.app.features.mobile.tvFocusRing
import com.dinsplayer.app.features.mobile.tvMarquee
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dinsplayer.app.core.player.isVodUrl
import com.dinsplayer.app.ui.theme.BgBase
import com.dinsplayer.app.ui.theme.BgDialog
import com.dinsplayer.app.ui.theme.BgElevated
import com.dinsplayer.app.ui.theme.BgSurface
import com.dinsplayer.app.ui.theme.ColorError
import com.dinsplayer.app.ui.theme.DividerColor
import com.dinsplayer.app.ui.theme.ElectricBlue
import com.dinsplayer.app.ui.theme.TextPrimary
import com.dinsplayer.app.ui.theme.TextSecondary

private val MenuBgPanel     = BgDialog
private val MenuBgHeader    = BgBase
private val MenuBgRow       = BgElevated
private val MenuBgRowActive = BgSurface
private val MenuCyan        = ElectricBlue
private val MenuTextWhite   = TextPrimary
private val MenuTextMuted   = TextSecondary
private val MenuDivider     = DividerColor
private val MenuLiveRed     = ColorError

private val epgTimeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

@Synchronized
private fun formatEpgTimeRange(startMillis: Long, stopMillis: Long): String {
    val start = epgTimeFormat.format(java.util.Date(startMillis))
    val stop = epgTimeFormat.format(java.util.Date(stopMillis))
    return "$start - $stop"
}

/**
 * Lightweight channel item passed to the menu.
 * Real data hookup happens in the next pass.
 */
data class ChannelItem(
    val id: String,
    val name: String,
    val groupTitle: String,
    val logoUrl: String,
    val url: String,
    val userAgent: String = "",
    val referer: String = "",
    val origin: String = "",
    val drmScheme: String? = null,
    val drmLicenseUri: String? = null,
    val manifestType: String = "",
    val tvgId: String = ""
)

/**
 * Overlay channel-list panel shown inside the player.
 *
 * Portrait  -> bottom half of the screen.
 * Landscape -> right 65% of the screen, category list on the left side.
 */
@Composable
fun PlayerChannelMenu(
    visible: Boolean,
    channels: List<ChannelItem>,
    activeChannelId: String,
    isLandscape: Boolean,
    onChannelClick: (ChannelItem) -> Unit,
    onDismiss: () -> Unit,
    headerLabel: String? = null,
) {
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    androidx.activity.compose.BackHandler(enabled = visible) {
        keyboardController?.hide()
        onDismiss()
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)),
        exit = fadeOut(animationSpec = tween(240, easing = androidx.compose.animation.core.FastOutLinearInEasing)),
    ) {
        // Dim background (uses pointerInput instead of clickable so the backdrop never steals D-pad focus)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .pointerInput(Unit) {
                    detectTapGestures {
                        keyboardController?.hide()
                        onDismiss()
                    }
                },
            contentAlignment = if (isLandscape) Alignment.CenterEnd else Alignment.BottomCenter
        ) {
            if (isLandscape) {
                Box(
                    modifier = Modifier
                        .animateEnterExit(
                            enter = slideInHorizontally(
                                animationSpec = tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                            ) { it },
                            exit = slideOutHorizontally(
                                animationSpec = tween(240, easing = androidx.compose.animation.core.FastOutLinearInEasing)
                            ) { it }
                        )
                        .fillMaxHeight()
                        .fillMaxWidth(0.65f)
                        .background(MenuBgPanel)
                        .pointerInput(isLandscape) {
                            detectHorizontalDragGestures { change, dragAmount ->
                                if (dragAmount > 20f) {
                                    change.consume()
                                    keyboardController?.hide()
                                    onDismiss()
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { /* consume taps so backdrop doesn't dismiss */ }
                        }
                ) {
                    LandscapeLayout(channels, activeChannelId, headerLabel, onChannelClick, onDismiss)
                }
            } else {
                Column(
                    modifier = Modifier
                        .animateEnterExit(
                            enter = slideInVertically(
                                animationSpec = tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                            ) { it } + fadeIn(animationSpec = tween(200)),
                            exit = slideOutVertically(
                                animationSpec = tween(240, easing = androidx.compose.animation.core.FastOutLinearInEasing)
                            ) { it } + fadeOut(animationSpec = tween(240))
                        )
                        .fillMaxWidth()
                        .fillMaxHeight(0.68f)
                        .pointerInput(isLandscape) {
                            detectVerticalDragGestures { change, dragAmount ->
                                if (dragAmount > 20f) {
                                    change.consume()
                                    keyboardController?.hide()
                                    onDismiss()
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { /* consume taps so backdrop doesn't dismiss */ }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                            .background(MenuBgPanel)
                    ) {
                        PortraitLayout(channels, activeChannelId, headerLabel, onChannelClick, onDismiss)
                    }
                }
            }
        }
    }
}

// Portrait: header + category chips + list
@Composable
private fun ChannelMenuHeaderStrip(
    headerLabel: String,
    compact: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MenuBgHeader)
            .padding(
                horizontal = if (compact) 12.dp else 14.dp,
                vertical = if (compact) 9.dp else 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(if (compact) 14.dp else 16.dp)
                .background(MenuCyan, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = headerLabel,
            color = MenuTextWhite,
            fontSize = if (compact) 13.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    HorizontalDivider(color = MenuDivider, thickness = 0.5.dp)
}

@Composable
private fun PortraitLayout(
    channels: List<ChannelItem>,
    activeChannelId: String,
    headerLabel: String? = null,
    onChannelClick: (ChannelItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = remember(channels) {
        listOf("Semua") + channels.map { it.groupTitle }.filter { it.isNotBlank() }.distinct().sorted()
    }
    var selectedGroup by remember { mutableStateOf("Semua") }
    var searchQuery   by remember { mutableStateOf("") }
    val focusManager  = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val epgData by com.dinsplayer.app.core.epg.EpgManager.epgData.collectAsState()
    val normalizedIndex by com.dinsplayer.app.core.epg.EpgManager.normalizedIndex.collectAsState()

    val channelListFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val closeButtonFocusRequester = remember { FocusRequester() }

    // Pre-compute current program per EPG channel key — updated once per epgData change
    val now = System.currentTimeMillis()
    val epgNowMap: Map<String, com.dinsplayer.app.core.epg.EpgProgram> = remember(epgData, now / 60_000L) {
        epgData.mapNotNull { (key, programs) ->
            programs.find { it.startMillis <= now && now < it.stopMillis }?.let { key to it }
        }.toMap()
    }

    val filtered = remember(selectedGroup, searchQuery, channels) {
        channels.filter { ch ->
            val g = selectedGroup == "Semua" || ch.groupTitle == selectedGroup
            val s = searchQuery.isBlank() || ch.name.contains(searchQuery, ignoreCase = true)
            g && s
        }
    }
    
    val initialIndex = remember(filtered, activeChannelId) {
        filtered.indexOfFirst { it.id == activeChannelId }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    LaunchedEffect(activeChannelId) {
        val index = filtered.indexOfFirst { it.id == activeChannelId }
        if (index >= 0 && index != listState.firstVisibleItemIndex) {
            listState.scrollToItem(index)
        }
    }

    val focusTargetChannelId = remember(filtered, activeChannelId, listState.firstVisibleItemIndex) {
        val visibleIds = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            filtered.getOrNull(info.index)?.id
        }
        when {
            activeChannelId in visibleIds -> activeChannelId
            visibleIds.isNotEmpty() -> visibleIds.first()
            else -> filtered.getOrNull(initialIndex)?.id ?: filtered.firstOrNull()?.id
        }
    }

    LaunchedEffect(Unit) {
        for (delayMs in listOf(50L, 120L, 250L, 400L)) {
            delay(delayMs)
            val focused = if (filtered.isNotEmpty()) {
                runCatching { channelListFocusRequester.requestFocus() }.isSuccess
            } else {
                runCatching { closeButtonFocusRequester.requestFocus() }.isSuccess
            }
            if (focused) break
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!headerLabel.isNullOrBlank()) {
            ChannelMenuHeaderStrip(headerLabel = headerLabel, compact = false)
        }
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MenuBgHeader)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            var isSearchFocused by remember { mutableStateOf(false) }
            androidx.compose.foundation.text.BasicTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .focusRequester(searchFocusRequester)
                    .onFocusChanged { isSearchFocused = it.isFocused || it.hasFocus }
                    .background(MenuBgRow, RoundedCornerShape(8.dp))
                    .border(
                        width = if (isSearchFocused) 2.dp else 1.dp,
                        color = if (isSearchFocused) Color.White else MenuDivider,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp),
                textStyle = androidx.compose.ui.text.TextStyle(color = MenuTextWhite, fontSize = 14.sp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboardController?.hide()
                    if (filtered.isNotEmpty()) runCatching { channelListFocusRequester.requestFocus() }
                }),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MenuCyan),
                decorationBox = { innerTextField ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Filled.Search, null, tint = MenuTextMuted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) Text("Cari saluran...", color = MenuTextMuted, fontSize = 14.sp)
                            innerTextField()
                        }
                    }
                }
            )
            IconButton(
                onClick = {
                    keyboardController?.hide()
                    onDismiss()
                },
                modifier = Modifier
                    .size(40.dp)
                    .focusRequester(closeButtonFocusRequester)
                    .tvFocusRing(CircleShape)
            ) {
                Icon(Icons.Filled.Close, "Tutup", tint = MenuTextMuted, modifier = Modifier.size(20.dp))
            }
        }

        // Category chips
        if (groups.size > 1) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MenuBgHeader)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(groups, key = { it }) { grp ->
                    val active = grp == selectedGroup
                    var isChipFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .onFocusChanged { isChipFocused = it.isFocused || it.hasFocus }
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isChipFocused) MenuCyan.copy(alpha = 0.32f) else if (active) MenuCyan.copy(alpha = 0.18f) else MenuBgRow)
                            .tvFocusRing(shape = RoundedCornerShape(16.dp), color = Color.White, width = 2.5.dp)
                            .clickable { selectedGroup = grp }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = grp,
                            color = if (isChipFocused) Color.White else if (active) MenuCyan else MenuTextMuted,
                            fontSize = 12.sp,
                            fontWeight = if (active || isChipFocused) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
            HorizontalDivider(color = MenuDivider, thickness = 0.5.dp)
        }

        // Channel list
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = if (channels.isEmpty()) "Belum ada playlist Live TV aktif" else "Tidak ada saluran ditemukan",
                    color = MenuTextMuted,
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(filtered, key = { _, ch -> ch.id }, contentType = { _, _ -> "menu_channel" }) { _, ch ->
                    val epgProgram = remember(ch.id, epgNowMap) {
                        com.dinsplayer.app.core.epg.EpgManager.resolve(ch.tvgId, ch.name, epgNowMap, normalizedIndex)
                    }
                    MenuChannelRow(
                        channel = ch,
                        isActive = ch.id == activeChannelId,
                        epgProgram = epgProgram,
                        modifier = if (ch.id == focusTargetChannelId) Modifier.focusRequester(channelListFocusRequester) else Modifier,
                        // D-pad kiri dari channel list portrait menutup panel
                        onNavigateLeft = { keyboardController?.hide(); onDismiss(); true },
                    ) {
                        keyboardController?.hide()
                        onChannelClick(ch)
                    }
                    HorizontalDivider(color = MenuDivider, thickness = 0.5.dp)
                }
            }
        }
    }
}

// Landscape: left category column + right channel list
@Composable
private fun LandscapeLayout(
    channels: List<ChannelItem>,
    activeChannelId: String,
    headerLabel: String? = null,
    onChannelClick: (ChannelItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = remember(channels) {
        listOf("Semua") + channels.map { it.groupTitle }.filter { it.isNotBlank() }.distinct().sorted()
    }
    var selectedGroup by remember { mutableStateOf("Semua") }
    var searchQuery   by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val epgData by com.dinsplayer.app.core.epg.EpgManager.epgData.collectAsState()
    val normalizedIndex by com.dinsplayer.app.core.epg.EpgManager.normalizedIndex.collectAsState()

    val categoryListFocusRequester = remember { FocusRequester() }
    val channelListFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val closeButtonFocusRequester = remember { FocusRequester() }

    val now2 = System.currentTimeMillis()
    val epgNowMap: Map<String, com.dinsplayer.app.core.epg.EpgProgram> = remember(epgData, now2 / 60_000L) {
        epgData.mapNotNull { (key, programs) ->
            programs.find { it.startMillis <= now2 && now2 < it.stopMillis }?.let { key to it }
        }.toMap()
    }

    val filtered = remember(selectedGroup, searchQuery, channels) {
        channels.filter { ch ->
            val g = selectedGroup == "Semua" || ch.groupTitle == selectedGroup
            val s = searchQuery.isBlank() || ch.name.contains(searchQuery, ignoreCase = true)
            g && s
        }
    }
    
    val initialIndex = remember(filtered, activeChannelId) {
        filtered.indexOfFirst { it.id == activeChannelId }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    LaunchedEffect(activeChannelId) {
        val index = filtered.indexOfFirst { it.id == activeChannelId }
        if (index >= 0 && index != listState.firstVisibleItemIndex) {
            listState.scrollToItem(index)
        }
    }

    val initialGroupIndex = remember(groups, selectedGroup) {
        groups.indexOf(selectedGroup).coerceAtLeast(0)
    }
    val categoryListState = rememberLazyListState(initialFirstVisibleItemIndex = initialGroupIndex)

    val focusTargetGroup = remember(groups, selectedGroup, categoryListState.firstVisibleItemIndex) {
        val visibleGroups = categoryListState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            groups.getOrNull(info.index)
        }
        when {
            selectedGroup in visibleGroups -> selectedGroup
            visibleGroups.isNotEmpty() -> visibleGroups.first()
            else -> groups.getOrNull(initialGroupIndex) ?: groups.firstOrNull()
        }
    }

    val focusTargetChannelId = remember(filtered, activeChannelId, listState.firstVisibleItemIndex) {
        val visibleIds = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            filtered.getOrNull(info.index)?.id
        }
        when {
            activeChannelId in visibleIds -> activeChannelId
            visibleIds.isNotEmpty() -> visibleIds.first()
            else -> filtered.getOrNull(initialIndex)?.id ?: filtered.firstOrNull()?.id
        }
    }

    LaunchedEffect(Unit) {
        for (delayMs in listOf(50L, 120L, 250L, 400L)) {
            delay(delayMs)
            val focused = if (filtered.isNotEmpty()) {
                runCatching { channelListFocusRequester.requestFocus() }.isSuccess
            } else {
                runCatching { categoryListFocusRequester.requestFocus() }.isSuccess
            }
            if (focused) break
        }
    }

    val coroutineScope = rememberCoroutineScope()

    Row(modifier = Modifier.fillMaxSize()) {
        // Left: category list
        Column(
            modifier = Modifier
                .width(160.dp)
                .fillMaxHeight()
                .background(MenuBgHeader)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Kategori", color = MenuTextWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                IconButton(
                    onClick = {
                        keyboardController?.hide()
                        onDismiss()
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .focusRequester(closeButtonFocusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionLeft -> {
                                        keyboardController?.hide()
                                        onDismiss()
                                        return@onPreviewKeyEvent true
                                    }
                                    Key.DirectionDown -> {
                                        runCatching { categoryListFocusRequester.requestFocus() }
                                        return@onPreviewKeyEvent true
                                    }
                                    Key.DirectionRight -> {
                                        if (filtered.isNotEmpty()) {
                                            runCatching { channelListFocusRequester.requestFocus() }
                                        } else {
                                            runCatching { searchFocusRequester.requestFocus() }
                                        }
                                        return@onPreviewKeyEvent true
                                    }
                                    else -> {}
                                }
                            }
                            false
                        }
                        .tvFocusRing(CircleShape)
                ) {
                    Icon(Icons.Filled.Close, "Tutup", tint = MenuTextMuted, modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider(color = MenuDivider, thickness = 0.5.dp)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = categoryListState
            ) {
                itemsIndexed(groups, key = { _, grp -> grp }) { idx, grp ->
                    val active = grp == selectedGroup
                    var isFocused by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .then(if (grp == focusTargetGroup) Modifier.focusRequester(categoryListFocusRequester) else Modifier)
                            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
                            .border(
                                width = if (isFocused) 2.5.dp else if (active) 1.dp else 0.dp,
                                color = if (isFocused) Color.White else if (active) MenuCyan.copy(alpha = 0.45f) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    isFocused -> MenuCyan.copy(alpha = 0.35f)
                                    active    -> MenuCyan.copy(alpha = 0.18f)
                                    else      -> Color.Transparent
                                }
                            )
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionLeft -> {
                                            keyboardController?.hide()
                                            onDismiss()
                                            return@onPreviewKeyEvent true
                                        }
                                        Key.DirectionRight -> {
                                            if (filtered.isNotEmpty()) {
                                                runCatching { channelListFocusRequester.requestFocus() }
                                            } else {
                                                runCatching { searchFocusRequester.requestFocus() }
                                            }
                                            return@onPreviewKeyEvent true
                                        }
                                        Key.DirectionUp -> {
                                            if (idx == 0) {
                                                runCatching { closeButtonFocusRequester.requestFocus() }
                                                return@onPreviewKeyEvent true
                                            }
                                        }
                                        else -> {}
                                    }
                                }
                                false
                            }
                            .clickable {
                                selectedGroup = grp
                                coroutineScope.launch {
                                    listState.scrollToItem(0)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (active) {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .height(16.dp)
                                    .background(if (isFocused) Color.White else MenuCyan, RoundedCornerShape(2.dp))
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = grp,
                            color = if (isFocused) Color.White else if (active) MenuCyan else MenuTextMuted,
                            fontSize = 12.sp,
                            fontWeight = if (active || isFocused) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider(color = MenuDivider, thickness = 0.5.dp)
                }
            }
        }

        Box(Modifier.width(1.dp).fillMaxHeight().background(MenuDivider))

        // Right: search + list
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (!headerLabel.isNullOrBlank()) {
                ChannelMenuHeaderStrip(headerLabel = headerLabel, compact = true)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MenuBgHeader)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                var isSearchFocused by remember { mutableStateOf(false) }
                androidx.compose.foundation.text.BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .focusRequester(searchFocusRequester)
                        .onFocusChanged { isSearchFocused = it.isFocused || it.hasFocus }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        if (filtered.isNotEmpty()) {
                                            keyboardController?.hide()
                                            runCatching { channelListFocusRequester.requestFocus() }
                                            return@onPreviewKeyEvent true
                                        }
                                    }
                                    Key.DirectionLeft -> {
                                        if (searchQuery.isEmpty()) {
                                            keyboardController?.hide()
                                            runCatching { closeButtonFocusRequester.requestFocus() }
                                            return@onPreviewKeyEvent true
                                        }
                                    }
                                    else -> {}
                                }
                            }
                            false
                        }
                        .background(MenuBgRow, RoundedCornerShape(8.dp))
                        .border(
                            width = if (isSearchFocused) 2.5.dp else 1.dp,
                            color = if (isSearchFocused) Color.White else MenuDivider,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = MenuTextWhite, fontSize = 13.sp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboardController?.hide()
                        if (filtered.isNotEmpty()) runCatching { channelListFocusRequester.requestFocus() }
                    }),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MenuCyan),
                    decorationBox = { innerTextField ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Filled.Search, null, tint = if (isSearchFocused) Color.White else MenuTextMuted, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                if (searchQuery.isEmpty()) Text("Cari saluran...", color = MenuTextMuted, fontSize = 13.sp)
                                innerTextField()
                            }
                        }
                    }
                )
            }
            HorizontalDivider(color = MenuDivider, thickness = 0.5.dp)
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (channels.isEmpty()) "Belum ada playlist Live TV aktif" else "Tidak ada saluran",
                        color = MenuTextMuted,
                        fontSize = 13.sp,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                    itemsIndexed(filtered, key = { _, ch -> ch.id }, contentType = { _, _ -> "menu_channel" }) { idx, ch ->
                        val epgProgram = remember(ch.id, epgNowMap) {
                            com.dinsplayer.app.core.epg.EpgManager.resolve(ch.tvgId, ch.name, epgNowMap, normalizedIndex)
                        }
                        MenuChannelRow(
                            channel = ch,
                            isActive = ch.id == activeChannelId,
                            epgProgram = epgProgram,
                            modifier = if (ch.id == focusTargetChannelId) Modifier.focusRequester(channelListFocusRequester) else Modifier,
                            onNavigateLeft = { runCatching { categoryListFocusRequester.requestFocus() }.isSuccess },
                            onNavigateUpFromFirst = if (idx == 0) ({ runCatching { searchFocusRequester.requestFocus() }.isSuccess }) else null,
                        ) {
                            keyboardController?.hide()
                            onChannelClick(ch)
                        }
                        HorizontalDivider(color = MenuDivider, thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

// Same rule the player uses to decide reconnect behaviour, so label and runtime never disagree.
private fun channelIsVod(channel: ChannelItem): Boolean =
    channel.groupTitle.equals("Lokal", ignoreCase = true) || isVodUrl(channel.url)

// Shared channel row component
@Composable
private fun MenuChannelRow(
    channel: ChannelItem,
    isActive: Boolean,
    epgProgram: com.dinsplayer.app.core.epg.EpgProgram?,
    modifier: Modifier = Modifier,
    onNavigateLeft: (() -> Boolean)? = null,
    onNavigateUpFromFirst: (() -> Boolean)? = null,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
            .border(
                width = if (isFocused) 2.5.dp else if (isActive) 1.dp else 0.dp,
                color = if (isFocused) Color.White else if (isActive) MenuCyan.copy(alpha = 0.45f) else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    isFocused -> MenuCyan.copy(alpha = 0.32f)
                    isActive  -> MenuBgRowActive
                    else      -> Color.Transparent
                }
            )
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft -> {
                            if (onNavigateLeft != null && onNavigateLeft()) return@onPreviewKeyEvent true
                        }
                        Key.DirectionUp -> {
                            if (onNavigateUpFromFirst != null && onNavigateUpFromFirst()) return@onPreviewKeyEvent true
                        }
                        else -> {}
                    }
                }
                false
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MenuBgRow),
            contentAlignment = Alignment.Center,
        ) {
            val defaultLogo = painterResource(R.drawable.ic_default_channel_logo)
            if (channel.logoUrl.isNotBlank()) {
                AsyncImage(
                    model = channel.logoUrl,
                    placeholder = defaultLogo,
                    error = defaultLogo,
                    fallback = defaultLogo,
                    contentDescription = channel.name,
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Image(
                    painter = defaultLogo,
                    contentDescription = channel.name,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = channel.name,
                    color = if (isFocused) Color.White else if (isActive) MenuCyan else MenuTextWhite,
                    fontSize = 13.sp,
                    fontWeight = if (isActive || isFocused) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f, fill = false).tvMarquee(),
                )
                Spacer(Modifier.width(6.dp))
                val isVod = remember(channel.url, channel.groupTitle) { channelIsVod(channel) }
                Text(
                    text = if (isVod) "VOD" else "LIVE",
                    color = if (isVod) MenuTextMuted else MenuLiveRed,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
            }
            if (epgProgram != null) {
                val timeRange = remember(epgProgram.startMillis, epgProgram.stopMillis) {
                    formatEpgTimeRange(epgProgram.startMillis, epgProgram.stopMillis)
                }
                Text(
                    text = "$timeRange • ${epgProgram.title}",
                    color = if (isFocused) Color.White.copy(alpha = 0.9f) else MenuCyan.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (channel.groupTitle.isNotBlank()) {
                Text(
                    text = channel.groupTitle,
                    color = if (isFocused) Color.White.copy(alpha = 0.85f) else MenuTextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (isActive || isFocused) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Sedang diputar",
                tint = if (isFocused) Color.White else MenuCyan,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

