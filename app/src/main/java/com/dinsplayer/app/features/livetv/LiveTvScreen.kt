package com.dinsplayer.app.features.livetv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.res.painterResource
import com.dinsplayer.app.R
import com.dinsplayer.app.features.mobile.rememberIsWideNav
import com.dinsplayer.app.features.mobile.tvFocusRing
import com.dinsplayer.app.features.mobile.tvMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.datastore.preferences.core.edit
import com.dinsplayer.app.data.KEY_LIVETV_GRID
import com.dinsplayer.app.data.settingsDataStore
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dinsplayer.app.core.network.M3uParser
import com.dinsplayer.app.core.network.XtreamParser
import com.dinsplayer.app.core.player.Media3Manager
import com.dinsplayer.app.features.multistream.MultiPlayerActivity
import com.dinsplayer.app.data.db.AppDatabase
import com.dinsplayer.app.data.models.Channel
import com.dinsplayer.app.data.models.PlaylistType
import com.dinsplayer.app.features.player.ChannelItem
import com.dinsplayer.app.features.player.PlayerScreen
import com.dinsplayer.app.ui.theme.AccentBlue
import com.dinsplayer.app.ui.theme.AccentBlueSoft
import com.dinsplayer.app.ui.theme.BgDialog
import com.dinsplayer.app.ui.theme.BgBase
import com.dinsplayer.app.ui.theme.BgElevated
import com.dinsplayer.app.ui.theme.BgSurface
import com.dinsplayer.app.ui.theme.DividerColor
import com.dinsplayer.app.ui.theme.TextMuted
import com.dinsplayer.app.ui.theme.TextPrimary
import com.dinsplayer.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun LiveTvScreen(initialPlaylistId: Long? = null) {
    val context = LocalContext.current
    val wide = rememberIsWideNav()
    val db = remember { AppDatabase.get(context) }
    val playlists by db.playlistDao().observeAll().collectAsState(initial = emptyList())
    
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var groups by remember { mutableStateOf<List<String>>(listOf("Semua")) }
    var selectedGroup by remember { mutableStateOf("Semua") }
    
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    // ponytail: restore nowPlayingChannel when live-tv mini-player or history view added
    var isLoading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    // Multi-select mode: user long-press channel atau pilih lewat header untuk masuk mode ini, max 4 slot
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var multiSelectedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var showMultiMenu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    fun toggleMultiSelect(channel: Channel) {
        val id = channel.id.toString()
        multiSelectedIds = if (id in multiSelectedIds) {
            multiSelectedIds - id
        } else if (multiSelectedIds.size < 4) {
            multiSelectedIds + id
        } else {
            multiSelectedIds
        }
    }

    fun exitMultiSelect() {
        isMultiSelectMode = false
        multiSelectedIds = emptyList()
    }

    fun syncLiveChannelsToPlayer(): List<ChannelItem> {
        val items = channels.toChannelItems()
        Media3Manager.setActiveChannels(items)
        return items
    }

    BackHandler(enabled = isMultiSelectMode) {
        exitMultiSelect()
    }

    val scope = rememberCoroutineScope()
    val isGridFlow = remember { context.settingsDataStore.data }
    val isGridPref by isGridFlow.collectAsState(initial = null)
    var isGridView by remember { mutableStateOf(false) }

    LaunchedEffect(isGridPref) {
        isGridPref?.get(KEY_LIVETV_GRID)?.let { isGridView = it }
    }

    fun toggleGridView() {
        val newMode = !isGridView
        isGridView = newMode
        scope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[KEY_LIVETV_GRID] = newMode
            }
        }
    }
    
    val epgData by com.dinsplayer.app.core.epg.EpgManager.epgData.collectAsState()
    val normalizedIndex by com.dinsplayer.app.core.epg.EpgManager.normalizedIndex.collectAsState()

    // Keyed on a minute counter so the list re-resolves when a programme ends.
    // Without the ticker the key never changed: nothing recomposed the screen, so the
    // EPG card kept showing whatever was on air when the list was first drawn.
    var epgMinute by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(30_000L)
            epgMinute = System.currentTimeMillis() / 60_000L
        }
    }
    val epgNow = System.currentTimeMillis()
    val epgNowMap: Map<String, com.dinsplayer.app.core.epg.EpgProgram> = remember(epgData, epgMinute) {
        epgData.mapNotNull { (key, programs) ->
            programs.find { it.startMillis <= epgNow && epgNow < it.stopMillis }?.let { key to it }
        }.toMap()
    }

    // Derive a stable reload key from only the fields that affect what gets loaded.
    // Using the whole `playlists` list as a LaunchedEffect key causes a full channel
    // reload on every metadata change (name, addedAt, isActive toggle on another row).
    val activePlaylist = playlists.firstOrNull { it.isActive }
    val reloadKey = remember(activePlaylist) {
        // source + serverUrl + username identify the actual content. type changes
        // (M3U_URL → M3U_FILE after file pick) also need a reload.
        "${activePlaylist?.id}|${activePlaylist?.type}|${activePlaylist?.source}|${activePlaylist?.serverUrl}|${activePlaylist?.username}"
    }

    LaunchedEffect(reloadKey, initialPlaylistId) {
        exitMultiSelect()
        if (playlists.isEmpty() || activePlaylist == null) {
            channels = emptyList()
            groups = emptyList()
            Media3Manager.setActiveChannels(emptyList())
            return@LaunchedEffect
        }

        val targetPlaylist = activePlaylist

        isLoading = true
        errorMessage = ""
        try {
            val fetchedChannels = withContext(Dispatchers.IO) {
                if (targetPlaylist.type == PlaylistType.XTREAM) {
                    loadingMessage = "Menghubungkan ke Xtream..."
                    XtreamParser.fetchChannels(targetPlaylist) { msg ->
                        loadingMessage = msg
                    }
                } else {
                    M3uParser.parsePlaylist(targetPlaylist, context) { msg ->
                        loadingMessage = msg
                    }
                }
            }
            channels = fetchedChannels
            // Push channel list to Media3Manager so PlayerScreen can use it for in-player menu
            Media3Manager.setActiveChannels(fetchedChannels.toChannelItems())
            val uniqueGroups = fetchedChannels.map { it.groupTitle }.filter { it.isNotBlank() }.distinct().sorted()
            groups = listOf("Semua") + uniqueGroups
            if (selectedGroup !in groups) selectedGroup = "Semua"
        } catch (e: Exception) {
            errorMessage = e.message ?: "Gagal memuat playlist"
        } finally {
            isLoading = false
        }
    }


    val filtered = remember(selectedGroup, searchQuery, channels) {
        channels.filter { ch ->
            val groupMatch = selectedGroup == "Semua" || ch.groupTitle == selectedGroup
            val searchMatch = searchQuery.isBlank() || ch.name.contains(searchQuery, ignoreCase = true)
            groupMatch && searchMatch
        }
    }

    // Tampilkan PlayerScreen saat channel dipilih
    // Dikelola secara global di MobileAppScreen melalui Media3Manager.state

    // No background here: the shell paints AppWallpaper behind every tab and a solid fill would
    // cover it. Header rows raise their own surface where they need contrast.
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (isMultiSelectMode) {
                // Header mode multi-select: tampilkan jumlah terpilih + tombol batal
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Dashboard, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            "Pilih saluran",
                            style = MaterialTheme.typography.titleSmall.copy(color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            "${multiSelectedIds.size}/4 dipilih",
                            style = MaterialTheme.typography.bodySmall.copy(color = AccentBlue)
                        )
                    }
                }
                TextButton(
                    onClick = { exitMultiSelect() },
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp)),
                ) {
                    Text("Batal", color = TextSecondary)
                }
            } else if (showSearch) {
                val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Cari saluran...", fontSize = 14.sp, color = TextMuted) },
                    modifier = Modifier.weight(1f).height(48.dp).tvFocusRing(RoundedCornerShape(10.dp)),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = AccentBlue,
                        unfocusedBorderColor = DividerColor,
                        focusedTextColor     = TextPrimary,
                        unfocusedTextColor   = TextPrimary,
                        unfocusedContainerColor = BgElevated,
                        focusedContainerColor   = BgElevated,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextSecondary) }
                )
                IconButton(
                    onClick = { showSearch = false; searchQuery = "" },
                    modifier = Modifier.tvFocusRing(CircleShape)
                ) {
                    Icon(Icons.Filled.Close, null, tint = TextSecondary)
                }
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Live TV", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = TextPrimary))
                    Text("${filtered.size} saluran", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary))
                }
                IconButton(
                    onClick = { toggleGridView() },
                    modifier = Modifier.tvFocusRing(CircleShape)
                ) {
                    Icon(
                        if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                        contentDescription = if (isGridView) "Tampilan List" else "Tampilan Grid 3 Kotak",
                        tint = if (isGridView) AccentBlue else TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                // Multi TV: pilih mode multi-select atau buka layar Multi TV langsung
                Box {
                    IconButton(
                        onClick = { showMultiMenu = true },
                        modifier = Modifier.tvFocusRing(CircleShape)
                    ) {
                        Icon(
                            Icons.Filled.Dashboard,
                            contentDescription = "Multi TV",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMultiMenu,
                        onDismissRequest = { showMultiMenu = false },
                        containerColor = BgDialog,
                    ) {
                        DropdownMenuItem(
                            text = { Text("Pilih Saluran (2-4 TV)", color = TextPrimary) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Checklist,
                                    contentDescription = null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            enabled = channels.isNotEmpty(),
                            onClick = {
                                showMultiMenu = false
                                multiSelectedIds = emptyList()
                                isMultiSelectMode = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Buka Layar Kosong", color = TextPrimary) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Dashboard,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            onClick = {
                                showMultiMenu = false
                                syncLiveChannelsToPlayer()
                                MultiPlayerActivity.launch(context)
                            },
                        )
                    }
                }
                IconButton(
                    onClick = { showSearch = true },
                    modifier = Modifier.tvFocusRing(CircleShape)
                ) {
                    Icon(Icons.Filled.Search, contentDescription = "Cari", tint = TextSecondary)
                }
            }
        }

        // Group filter chips
        if (groups.size > 1 && !isLoading) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                items(groups, key = { it }) { group ->
                    val selected = selectedGroup == group
                    var isChipFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick   = { selectedGroup = group },
                        shape     = RoundedCornerShape(8.dp),
                        color     = if (selected) AccentBlue else (if (isChipFocused) BgSurface else BgElevated),
                        modifier  = Modifier
                            .onFocusChanged { isChipFocused = it.isFocused || it.hasFocus }
                            .border(1.dp, if (selected) AccentBlue else DividerColor, RoundedCornerShape(8.dp))
                            .tvFocusRing(shape = RoundedCornerShape(8.dp), color = Color.White, width = 2.5.dp)
                    ) {
                        Text(
                            text  = group,
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = if (selected || isChipFocused) Color.White else TextSecondary,
                                fontWeight = if (selected || isChipFocused) FontWeight.Bold else FontWeight.Normal
                            ),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // States
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentBlue)
                        Spacer(Modifier.height(16.dp))
                        Text(loadingMessage, style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary))
                    }
                }
            }
            errorMessage.isNotBlank() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Filled.ErrorOutline, null, tint = AccentBlue, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Terjadi Kesalahan", style = MaterialTheme.typography.titleMedium.copy(color = TextPrimary))
                        Spacer(Modifier.height(8.dp))
                        Text(errorMessage, style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            channels.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Tv, null, tint = TextMuted, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Tidak ada saluran", style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary))
                        Text("Tambahkan playlist di menu Playlist", style = MaterialTheme.typography.bodySmall.copy(color = TextMuted))
                    }
                }
            }
            filtered.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Saluran tidak ditemukan", style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tidak ada hasil untuk \"$searchQuery\"" +
                                if (selectedGroup != "Semua") " di grup $selectedGroup" else "",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { searchQuery = ""; selectedGroup = "Semua" }) {
                            Text("Reset Filter", color = AccentBlue, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            else -> {
                val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                val onPlayChannel: (Channel) -> Unit = { channel ->
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    syncLiveChannelsToPlayer()
                    val liveHeaders = buildMap<String, String> {
                        if (channel.referer.isNotBlank()) put("Referer", channel.referer)
                        if (channel.origin.isNotBlank()) put("Origin", channel.origin)
                    }
                    Media3Manager.setActiveChannelId(channel.id.toString())
                    Media3Manager.play(
                        url = channel.url,
                        title = channel.name,
                        userAgent = channel.userAgent,
                        headers = liveHeaders,
                        drmScheme = channel.drmScheme.takeIf { it.isNotBlank() },
                        drmLicenseUri = channel.drmLicenseUri,
                        manifestType = channel.manifestType
                    )
                    Media3Manager.launchPlayer(context)
                }

                if (isGridView) {
                    LazyVerticalGrid(
                        // Portrait stays 3 across. Wide screens get a fixed 5 so the channel
                        // fleet reads as a stable wall of logos instead of a shifting count.
                        columns = GridCells.Fixed(if (wide) 5 else 3),
                        contentPadding = PaddingValues(
                            start = 12.dp, end = 12.dp,
                            top = 6.dp,
                            bottom = if (isMultiSelectMode) 120.dp else 6.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filtered, key = { it.id }, contentType = { "channel_grid" }) { channel ->
                            val epgProgram = remember(channel.id, epgNowMap) {
                                com.dinsplayer.app.core.epg.EpgManager
                                    .resolve(channel.tvgId, channel.name, epgNowMap, normalizedIndex)
                            }
                            val slotNumber = multiSelectedIds.indexOf(channel.id.toString()).let { if (it >= 0) it + 1 else null }
                            ChannelGridCard(
                                channel = channel,
                                epgProgram = epgProgram,
                                wide = wide,
                                isMultiSelectMode = isMultiSelectMode,
                                slotNumber = slotNumber,
                                onPlay = { onPlayChannel(channel) },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    isMultiSelectMode = true
                                    toggleMultiSelect(channel)
                                }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp,
                            top = 4.dp,
                            bottom = if (isMultiSelectMode) 120.dp else 4.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filtered, key = { it.id }, contentType = { "channel_row" }) { channel ->
                            val epgProgram = remember(channel.id, epgNowMap) {
                                com.dinsplayer.app.core.epg.EpgManager
                                    .resolve(channel.tvgId, channel.name, epgNowMap, normalizedIndex)
                            }
                            val slotNumber = multiSelectedIds.indexOf(channel.id.toString()).let { if (it >= 0) it + 1 else null }
                            ChannelRow(
                                channel = channel,
                                epgProgram = epgProgram,
                                isMultiSelectMode = isMultiSelectMode,
                                slotNumber = slotNumber,
                                onPlay = { onPlayChannel(channel) },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    isMultiSelectMode = true
                                    toggleMultiSelect(channel)
                                }
                            )
                        }
                    }
                }
            }
        }
        } // end Column

        // Floating tray muncul di atas list saat mode multi-select aktif
        if (isMultiSelectMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 4.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                MultiSelectTray(
                    selectedCount = multiSelectedIds.size,
                    onCancel = { exitMultiSelect() },
                    onStart = {
                        val allChannelItems = syncLiveChannelsToPlayer()
                        val selected = multiSelectedIds.mapNotNull { id ->
                            allChannelItems.find { it.id == id }
                        }
                        MultiPlayerActivity.launch(context, selected.map { it.id })
                        exitMultiSelect()
                    }
                )
            }
        }
    } // end Box
}

private fun List<Channel>.toChannelItems(): List<ChannelItem> = map { ch ->
    ChannelItem(
        id = ch.id.toString(),
        name = ch.name,
        groupTitle = ch.groupTitle,
        logoUrl = ch.logoUrl,
        url = ch.url,
        userAgent = ch.userAgent,
        referer = ch.referer,
        origin = ch.origin,
        drmScheme = ch.drmScheme.takeIf { it.isNotBlank() },
        drmLicenseUri = ch.drmLicenseUri?.takeIf { it.isNotBlank() },
        manifestType = ch.manifestType,
        tvgId = ch.tvgId,
    )
}

private val epgTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

private fun formatEpgTimeRange(startMillis: Long, stopMillis: Long): String {
    val zone  = java.time.ZoneId.systemDefault()
    val start = java.time.Instant.ofEpochMilli(startMillis).atZone(zone).format(epgTimeFormatter)
    val stop  = java.time.Instant.ofEpochMilli(stopMillis).atZone(zone).format(epgTimeFormatter)
    return "$start - $stop"
}

@Composable
private fun ChannelRow(
    channel: Channel,
    epgProgram: com.dinsplayer.app.core.epg.EpgProgram?,
    isMultiSelectMode: Boolean = false,
    slotNumber: Int? = null,
    onPlay: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (slotNumber != null) AccentBlueSoft else BgElevated
                )
                .border(
                    1.dp,
                    if (slotNumber != null) AccentBlue else DividerColor,
                    RoundedCornerShape(12.dp)
                )
                .tvFocusRing(RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = {
                        if (isMultiSelectMode) onLongClick() else onPlay()
                    },
                    onLongClick = onLongClick
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(BgSurface),
            contentAlignment = Alignment.Center
        ) {
            val defaultLogo = painterResource(R.drawable.ic_default_channel_logo)
            if (channel.logoUrl.isNotBlank()) {
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(LocalContext.current)
                        .data(channel.logoUrl)
                        .crossfade(true)
                        .crossfade(200)
                        .build(),
                    placeholder = defaultLogo,
                    error = defaultLogo,
                    fallback = defaultLogo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(40.dp)
                )
            } else {
                Image(
                    painter = defaultLogo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(38.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleSmall.copy(color = TextPrimary),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.tvMarquee()
            )
            if (epgProgram != null) {
                val timeRange = remember(epgProgram.startMillis, epgProgram.stopMillis) {
                    formatEpgTimeRange(epgProgram.startMillis, epgProgram.stopMillis)
                }
                Text(
                    text = "$timeRange • ${epgProgram.title}",
                    style = MaterialTheme.typography.bodySmall.copy(color = AccentBlue),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.tvMarquee()
                )
            } else if (channel.groupTitle.isNotBlank()) {
                Text(channel.groupTitle, style = MaterialTheme.typography.bodySmall.copy(color = TextMuted))
            }
        }
        if (isMultiSelectMode) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (slotNumber != null) AccentBlue else Color.Transparent)
                    .border(
                        width = if (slotNumber != null) 0.dp else 1.5.dp,
                        color = if (slotNumber != null) AccentBlue else DividerColor,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (slotNumber != null) {
                    Text(
                        text = slotNumber.toString(),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        } else {
            Icon(Icons.Filled.PlayCircle, contentDescription = "Putar", tint = AccentBlue, modifier = Modifier.size(28.dp))
        }
        }
    }
}

@Composable
private fun ChannelGridCard(
    channel: Channel,
    epgProgram: com.dinsplayer.app.core.epg.EpgProgram? = null,
    wide: Boolean,
    isMultiSelectMode: Boolean = false,
    slotNumber: Int? = null,
    onPlay: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (slotNumber != null) AccentBlueSoft else BgElevated)
                .border(1.dp, if (slotNumber != null) AccentBlue else DividerColor, RoundedCornerShape(12.dp))
                .tvFocusRing(RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = { if (isMultiSelectMode) onLongClick() else onPlay() },
                    onLongClick = onLongClick
                )
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (wide) Modifier.aspectRatio(1.6f) else Modifier.height(54.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(BgSurface),
            contentAlignment = Alignment.Center
        ) {
            val defaultLogo = painterResource(R.drawable.ic_default_channel_logo)
            if (channel.logoUrl.isNotBlank()) {
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(LocalContext.current)
                        .data(channel.logoUrl)
                        .crossfade(true)
                        .crossfade(200)
                        .build(),
                    placeholder = defaultLogo,
                    error = defaultLogo,
                    fallback = defaultLogo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                )
            } else {
                Image(
                    painter = defaultLogo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = channel.name,
            style = MaterialTheme.typography.labelMedium.copy(
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().tvMarquee()
        )
        if (epgProgram != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = epgProgram.title,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = AccentBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal
                ),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().tvMarquee()
            )
        }
        }
        // Badge nomor slot
        if (slotNumber != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(AccentBlue, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(slotNumber.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MultiSelectTray(
    selectedCount: Int,
    onCancel: () -> Unit,
    onStart: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(BgDialog)
            .border(1.dp, AccentBlue.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "$selectedCount/4 saluran dipilih",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Text(
                        if (selectedCount < 2) "Pilih minimal 2 saluran" else "Siap untuk Multi TV",
                        color = if (selectedCount >= 2) AccentBlue else TextMuted,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp)),
                ) {
                    Text("Batal", color = TextSecondary)
                }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = onStart,
                    enabled = selectedCount >= 2,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentBlue,
                        disabledContainerColor = AccentBlue.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(10.dp)),
                ) {
                    Icon(Icons.Filled.Dashboard, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Mulai")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (slot in 1..4) {
                    val filled = slot <= selectedCount
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (filled) AccentBlueSoft else BgSurface)
                            .border(
                                1.dp,
                                if (filled) AccentBlue else DividerColor,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Slot $slot",
                            color = if (filled) AccentBlue else TextMuted,
                            fontSize = 10.sp,
                            fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
