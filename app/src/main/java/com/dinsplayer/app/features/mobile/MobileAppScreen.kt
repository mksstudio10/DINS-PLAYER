package com.dinsplayer.app.features.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dinsplayer.app.data.db.AppDatabase
import com.dinsplayer.app.features.beranda.BerandaScreen
import com.dinsplayer.app.features.livetv.LiveTvScreen
import com.dinsplayer.app.features.lokal.LokalScreen
import com.dinsplayer.app.features.playlist.PlaylistScreen
import com.dinsplayer.app.features.setelan.SetelanScreen
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dinsplayer.app.ui.theme.AccentBlue
import com.dinsplayer.app.ui.theme.BgBase
import com.dinsplayer.app.ui.theme.BgDialog
import com.dinsplayer.app.ui.theme.ElectricBlue
import com.dinsplayer.app.ui.theme.NavUnselected
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dinsplayer.app.features.mobile.AppWallpaper
import com.dinsplayer.app.features.player.PlayerScreen
import com.dinsplayer.app.core.player.Media3Manager
import com.dinsplayer.app.data.models.PlayerState
import com.dinsplayer.app.data.settingsDataStore

private data class NavItem(
    val label: String,
    val iconSelected: ImageVector,
    val iconUnselected: ImageVector,
)

// Tab index constants
private const val TAB_BERANDA  = 0
private const val TAB_LOKAL    = 1
private const val TAB_LIVETV   = 2
private const val TAB_PLAYLIST = 3
private const val TAB_SETELAN  = 4

private val navItems = listOf(
    NavItem("Beranda",  Icons.Filled.Home,              Icons.Outlined.Home),
    NavItem("Lokal",    Icons.Filled.FolderOpen,        Icons.Outlined.FolderOpen),
    NavItem("Live TV",  Icons.Filled.Tv,                Icons.Outlined.Tv),
    NavItem("Playlist", Icons.AutoMirrored.Filled.List, Icons.AutoMirrored.Outlined.List),
    NavItem("Setelan",  Icons.Filled.Settings,          Icons.Outlined.Settings),
)

@Composable
fun MobileAppScreen() {
    val context = LocalContext.current
    val wide = rememberIsWideNav()
    val dao = remember { AppDatabase.get(context).playlistDao() }
    val playlists by dao.observeAll().collectAsState(initial = emptyList())
    val hasActivePlaylist = playlists.any { it.isActive }

    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_BERANDA) }
    // playlistId yang dipilih dari PlaylistScreen untuk di-load di LiveTV
    var selectedPlaylistId by remember { mutableStateOf<Long?>(null) }
    var showExitDialog by remember { mutableStateOf(false) }

    // Jika Live TV dipilih tapi tidak ada playlist aktif, arahkan ke Playlist
    val effectiveTab = if (selectedTab == TAB_LIVETV && !hasActivePlaylist) TAB_PLAYLIST else selectedTab

    val playerState by Media3Manager.state.collectAsState()
    val currentTitle by Media3Manager.currentTitle.collectAsState()

    // Back dari tab non-Beranda → ke Beranda
    BackHandler(enabled = selectedTab != TAB_BERANDA) {
        selectedTab = TAB_BERANDA
    }

    // Back dari Beranda → munculkan dialog konfirmasi keluar
    BackHandler(enabled = selectedTab == TAB_BERANDA) {
        showExitDialog = true
    }

    val ds = context.settingsDataStore
    val epgUrlsPref by ds.data.collectAsState(initial = androidx.datastore.preferences.core.emptyPreferences())
    val epgUrls = epgUrlsPref[com.dinsplayer.app.data.KEY_EPG_URLS]
    
    androidx.compose.runtime.LaunchedEffect(epgUrls) {
        val urls = if (epgUrls.isNullOrEmpty()) com.dinsplayer.app.core.epg.EpgManager.defaultUrls.toSet() else epgUrls
        com.dinsplayer.app.core.epg.EpgManager.syncEpgs(urls)
    }

    // A playlist file opened from another app: jump to the Playlist tab, which opens the form.
    val importUri by com.dinsplayer.app.features.playlist.PlaylistImport.pending.collectAsState()
    androidx.compose.runtime.LaunchedEffect(importUri) {
        if (importUri != null) selectedTab = TAB_PLAYLIST
    }

    val onTabSelected: (Int) -> Unit = { index ->
        if (index == TAB_LIVETV && !hasActivePlaylist) {
            selectedTab = TAB_PLAYLIST
        } else {
            selectedTab = index
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // The wallpaper sits under everything and the Scaffold goes transparent, otherwise
        // `containerColor = BgBase` paints a solid layer over it and nothing is visible.
        AppWallpaper()
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (!wide) {
                    DinsBottomNav(
                        selectedIndex = effectiveTab,
                        hasLiveTv     = hasActivePlaylist,
                        onTabSelected = onTabSelected
                    )
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = innerPadding.calculateBottomPadding())
            ) {
                val berandaFocusRequester = remember { FocusRequester() }
                val lokalFocusRequester = remember { FocusRequester() }
                val setelanFocusRequester = remember { FocusRequester() }

                if (wide) {
                    DinsRailNav(
                        selectedIndex = effectiveTab,
                        hasLiveTv     = hasActivePlaylist,
                        onTabSelected = onTabSelected,
                        onNavigateRight = {
                            when (effectiveTab) {
                                TAB_BERANDA -> runCatching { berandaFocusRequester.requestFocus() }.isSuccess
                                TAB_LOKAL   -> runCatching { lokalFocusRequester.requestFocus() }.isSuccess
                                TAB_SETELAN -> runCatching { setelanFocusRequester.requestFocus() }.isSuccess
                                else        -> false
                            }
                        }
                    )
                }
                // Each screen applies its own statusBarsPadding, so this shell only forwards the
                // bottom inset. Adding the full innerPadding would double the top inset.
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    AnimatedContent(
                        targetState = effectiveTab,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing)) togetherWith
                            fadeOut(animationSpec = tween(durationMillis = 120, easing = LinearOutSlowInEasing))
                        },
                        label = "tab"
                    ) { tab ->
                        when (tab) {
                            TAB_BERANDA  -> BerandaScreen(firstFocusRequester = berandaFocusRequester)
                            TAB_LOKAL    -> LokalScreen(firstFocusRequester = lokalFocusRequester)
                            TAB_LIVETV   -> LiveTvScreen(initialPlaylistId = selectedPlaylistId)
                            TAB_PLAYLIST -> PlaylistScreen(
                                importUri = importUri,
                                onImportConsumed = { com.dinsplayer.app.features.playlist.PlaylistImport.consume() },
                                onPlaylistSelected = { playlistId: Long ->
                                    selectedPlaylistId = playlistId
                                    selectedTab = TAB_LIVETV
                                }
                            )
                            TAB_SETELAN  -> SetelanScreen(firstFocusRequester = setelanFocusRequester)
                        }
                    }
                }
            }
        }

        if (showExitDialog) {
            ExitConfirmDialog(
                onConfirm = {
                    showExitDialog = false
                    (context as? android.app.Activity)?.finish()
                },
                onDismiss = {
                    showExitDialog = false
                }
            )
        }
    }
}

@Composable
private fun DinsBottomNav(
    selectedIndex: Int,
    hasLiveTv: Boolean,
    onTabSelected: (Int) -> Unit,
) {
    val navShape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        NavigationBar(
            containerColor = Color(0xD9121A2B),
            tonalElevation = 0.dp,
            windowInsets   = WindowInsets(0),
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
                .clip(navShape)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.14f),
                    shape = navShape
                )
        ) {
            navItems.forEachIndexed { index, item ->
                val selected = selectedIndex == index
                val isLiveTv = index == TAB_LIVETV
                val enabled  = !isLiveTv || hasLiveTv

                NavigationBarItem(
                    selected  = selected,
                    enabled   = enabled,
                    onClick   = { onTabSelected(index) },
                    icon = {
                        Icon(
                            imageVector        = if (selected) item.iconSelected else item.iconUnselected,
                            contentDescription = item.label,
                        )
                    },
                    label     = { Text(text = item.label, fontSize = 11.sp) },
                    colors    = NavigationBarItemDefaults.colors(
                        selectedIconColor   = ElectricBlue,
                        selectedTextColor   = ElectricBlue,
                        unselectedIconColor = if (enabled) NavUnselected else NavUnselected.copy(alpha = 0.3f),
                        unselectedTextColor = if (enabled) NavUnselected else NavUnselected.copy(alpha = 0.3f),
                        disabledIconColor   = NavUnselected.copy(alpha = 0.3f),
                        disabledTextColor   = NavUnselected.copy(alpha = 0.3f),
                        indicatorColor      = ElectricBlue.copy(alpha = 0.15f),
                    ),
                    alwaysShowLabel = true,
                )
            }
        }
    }
}

@Composable
private fun DinsRailNav(
    selectedIndex: Int,
    hasLiveTv: Boolean,
    onTabSelected: (Int) -> Unit,
    onNavigateRight: (() -> Boolean)? = null,
) {
    val railShape = RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp)
    Column(
        modifier = Modifier
            .width(96.dp)
            .fillMaxHeight()
            .clip(railShape)
            .background(Color(0xD9121A2B), railShape)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.14f),
                shape = railShape
            )
            .statusBarsPadding()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        navItems.forEachIndexed { index, item ->
            val selected = selectedIndex == index
            val isLiveTv = index == TAB_LIVETV
            val enabled  = !isLiveTv || hasLiveTv
            val tint = when {
                !enabled -> NavUnselected.copy(alpha = 0.3f)
                selected -> ElectricBlue
                else     -> NavUnselected
            }

            RailNavItem(
                label     = item.label,
                icon      = if (selected) item.iconSelected else item.iconUnselected,
                tint      = tint,
                enabled   = enabled,
                onClick   = { onTabSelected(index) },
                onNavigateRight = if (selected) onNavigateRight else null
            )
        }
    }
}

@Composable
private fun RailNavItem(
    label: String,
    icon: ImageVector,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    onNavigateRight: (() -> Boolean)? = null,
) {
    Column(
        modifier = Modifier
            .width(80.dp)
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(14.dp))
            // Remote navigation is invisible without this: clickable only ripples on press,
            // so on a TV the user cannot see which item the D-pad is currently on.
            .tvFocusRing(RoundedCornerShape(14.dp))
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionRight) {
                    if (onNavigateRight != null) {
                        onNavigateRight()
                    } else false
                } else false
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = if (enabled) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1
        )
    }
}

@Composable
private fun ExitConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val batalFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        for (delayMs in listOf(50L, 150L, 300L)) {
            kotlinx.coroutines.delay(delayMs)
            try {
                batalFocusRequester.requestFocus()
                break
            } catch (_: Exception) {}
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
                    .widthIn(min = 300.dp, max = 380.dp)
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(BgDialog)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(AccentBlue.copy(alpha = 0.16f))
                        .border(1.dp, AccentBlue.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "Keluar Aplikasi?",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Apakah Anda yakin ingin keluar dari DINS Player?",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                )

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Tombol Batal
                    var isBatalFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .focusRequester(batalFocusRequester)
                            .onFocusChanged { isBatalFocused = it.isFocused || it.hasFocus }
                            .border(
                                width = if (isBatalFocused) 3.dp else 1.dp,
                                color = if (isBatalFocused) Color.White else Color.White.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isBatalFocused) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f))
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Batal",
                            color = if (isBatalFocused) Color.White else Color.White.copy(alpha = 0.85f),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }

                    // Tombol Keluar
                    var isKeluarFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .onFocusChanged { isKeluarFocused = it.isFocused || it.hasFocus }
                            .border(
                                width = if (isKeluarFocused) 3.dp else 0.dp,
                                color = if (isKeluarFocused) Color.White else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isKeluarFocused) Color(0xFF3B82F6) else AccentBlue)
                            .clickable { onConfirm() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Keluar",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}
