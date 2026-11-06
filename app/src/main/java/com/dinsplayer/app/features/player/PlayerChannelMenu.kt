package com.dinsplayer.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
) {
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)),
        exit = fadeOut(animationSpec = tween(240, easing = androidx.compose.animation.core.FastOutLinearInEasing)),
    ) {
        // Dim background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { 
                    keyboardController?.hide()
                    onDismiss() 
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
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { /* consume clicks */ }
                ) {
                    LandscapeLayout(channels, activeChannelId, onChannelClick, onDismiss)
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
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { /* consume clicks */ },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                            .background(MenuBgPanel)
                    ) {
                        PortraitLayout(channels, activeChannelId, onChannelClick, onDismiss)
                    }
                }
            }
        }
    }
}

// Portrait: header + category chips + list
@Composable
private fun PortraitLayout(
    channels: List<ChannelItem>,
    activeChannelId: String,
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

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MenuBgHeader)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(MenuBgRow, RoundedCornerShape(8.dp))
                    .border(1.dp, MenuDivider, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp),
                textStyle = androidx.compose.ui.text.TextStyle(color = MenuTextWhite, fontSize = 14.sp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
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
                    focusManager.clearFocus()
                    onDismiss()
                },
                modifier = Modifier.size(40.dp).tvFocusRing(CircleShape)
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
                            .background(if (active) MenuCyan.copy(alpha = 0.18f) else if (isChipFocused) Color.White.copy(alpha = 0.12f) else MenuBgRow)
                            .tvFocusRing(shape = RoundedCornerShape(16.dp), color = Color.White, width = 2.dp)
                            .clickable { selectedGroup = grp; focusManager.clearFocus() }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = grp,
                            color = if (active || isChipFocused) MenuCyan else MenuTextMuted,
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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Tidak ada saluran ditemukan", color = MenuTextMuted, fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                items(filtered, key = { it.id }, contentType = { "menu_channel" }) { ch ->
                    val epgProgram = remember(ch.id, epgNowMap) {
                        com.dinsplayer.app.core.epg.EpgManager.resolve(ch.tvgId, ch.name, epgNowMap, normalizedIndex)
                    }
                    MenuChannelRow(
                        channel = ch,
                        isActive = ch.id == activeChannelId,
                        epgProgram = epgProgram
                    ) {
                        keyboardController?.hide()
                        focusManager.clearFocus()
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
                        focusManager.clearFocus()
                        onDismiss()
                    },
                    modifier = Modifier.size(32.dp).tvFocusRing(CircleShape)
                ) {
                    Icon(Icons.Filled.Close, "Tutup", tint = MenuTextMuted, modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider(color = MenuDivider, thickness = 0.5.dp)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(groups, key = { it }) { grp ->
                    val active = grp == selectedGroup
                    var isFocused by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
                            .tvFocusRing(RoundedCornerShape(8.dp), color = Color.White, width = 2.dp)
                            .background(if (active) MenuCyan.copy(alpha = 0.18f) else if (isFocused) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { selectedGroup = grp }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (active) {
                            Box(Modifier.width(3.dp).height(16.dp).background(MenuCyan, RoundedCornerShape(2.dp)))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = grp,
                            color = if (active || isFocused) MenuCyan else MenuTextMuted,
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MenuBgHeader)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .background(MenuBgRow, RoundedCornerShape(8.dp))
                        .border(1.dp, MenuDivider, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = MenuTextWhite, fontSize = 13.sp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MenuCyan),
                    decorationBox = { innerTextField ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Filled.Search, null, tint = MenuTextMuted, modifier = Modifier.size(16.dp))
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
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Tidak ada saluran", color = MenuTextMuted, fontSize = 13.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                    items(filtered, key = { it.id }, contentType = { "menu_channel" }) { ch ->
                        val epgProgram = remember(ch.id, epgNowMap) {
                            com.dinsplayer.app.core.epg.EpgManager.resolve(ch.tvgId, ch.name, epgNowMap, normalizedIndex)
                        }
                        MenuChannelRow(
                            channel = ch,
                            isActive = ch.id == activeChannelId,
                            epgProgram = epgProgram
                        ) {
                            keyboardController?.hide()
                            focusManager.clearFocus()
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
private fun MenuChannelRow(channel: ChannelItem, isActive: Boolean, epgProgram: com.dinsplayer.app.core.epg.EpgProgram?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) MenuBgRowActive else Color.Transparent)
            .tvFocusRing(RoundedCornerShape(8.dp))
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
            if (channel.logoUrl.isNotBlank()) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = channel.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    channel.name.take(2).uppercase(),
                    color = MenuTextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = channel.name,
                    color = if (isActive) MenuCyan else MenuTextWhite,
                    fontSize = 13.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
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
                    color = MenuCyan.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (channel.groupTitle.isNotBlank()) {
                Text(
                    text = channel.groupTitle,
                    color = MenuTextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (isActive) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Sedang diputar",
                tint = MenuCyan,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

