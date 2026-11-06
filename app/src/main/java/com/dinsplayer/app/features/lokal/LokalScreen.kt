package com.dinsplayer.app.features.lokal

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.dinsplayer.app.features.mobile.rememberIsWideNav
import com.dinsplayer.app.features.mobile.tvFocusRing
import com.dinsplayer.app.features.mobile.tvMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.compose.AsyncImage
import com.dinsplayer.app.core.player.Media3Manager
import com.dinsplayer.app.data.models.VideoFile
import com.dinsplayer.app.data.models.VideoFolder
import com.dinsplayer.app.data.settingsDataStore
import com.dinsplayer.app.features.player.PlayerScreen
import com.dinsplayer.app.ui.theme.*
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LokalScreen(
    firstFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current
    val wide = rememberIsWideNav()
    val localFirstFocusRequester = remember { FocusRequester() }
    val effectiveFirstFocusRequester = firstFocusRequester ?: localFirstFocusRequester
    var hasPermission by remember {
        mutableStateOf(checkStoragePermission(context))
    }
    // On API 26-28 MediaStore delete needs WRITE_EXTERNAL_STORAGE, which was never
    // requested at runtime: every delete threw SecurityException and surfaced as a
    // generic "Gagal menghapus" toast.
    val storagePermissions = remember {
        if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else if (Build.VERSION.SDK_INT >= 29)
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
    }
    var folders by remember { mutableStateOf<List<VideoFolder>>(emptyList()) }
    var selectedFolder by remember { mutableStateOf<VideoFolder?>(null) }
    var folderVideos by remember { mutableStateOf<List<VideoFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var nowPlayingVideo by remember { mutableStateOf<VideoFile?>(null) }
    
    // View mode and sort order live in DataStore, not in remember{}: AnimatedContent in
    // MobileAppScreen drops this composable when the user switches tabs, so local state
    // died and every return to the tab reset the list to List view / Date order.
    val settingsPrefs by context.settingsDataStore.data
        .collectAsState(initial = androidx.datastore.preferences.core.emptyPreferences())
    val isGridMode = settingsPrefs[com.dinsplayer.app.data.KEY_LOKAL_GRID] ?: false
    val sortMode = settingsPrefs[com.dinsplayer.app.data.KEY_LOKAL_SORT] ?: 0 // 0: Date, 1: Name, 2: Size
    val settingsScope = rememberCoroutineScope()
    var videoToDelete by remember { mutableStateOf<VideoFile?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    // Dikelola secara global di MobileAppScreen melalui Media3Manager.state

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasPermission = storagePermissions.all { grants[it] == true }
    }

    // RESULT_OK only means the system dialog was confirmed. The row can still be gone
    // from MediaStore without the file being removed, so confirm against the filesystem.
    var deleteTarget by remember { mutableStateOf<VideoFile?>(null) }

    val deleteIntentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val target = deleteTarget
        deleteTarget = null
        if (result.resultCode != Activity.RESULT_OK) {
            Toast.makeText(context, "Penghapusan dibatalkan", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        refreshTrigger++
        val stillThere = target != null && java.io.File(target.path).exists()
        Toast.makeText(
            context,
            if (stillThere) "File tidak dapat dihapus dari penyimpanan" else "Video berhasil dihapus",
            Toast.LENGTH_SHORT,
        ).show()
    }

    LaunchedEffect(hasPermission, refreshTrigger) {
        if (hasPermission) {
            isLoading = true
            folders = withContext(Dispatchers.IO) { scanVideoFolders(context) }
            isLoading = false
        }
    }

    // Back dari subfolder → folder list
    BackHandler(enabled = selectedFolder != null) {
        selectedFolder = null
        folderVideos = emptyList()
    }

    LaunchedEffect(selectedFolder, refreshTrigger) {
        val f = selectedFolder ?: return@LaunchedEffect
        isLoading = true
        folderVideos = withContext(Dispatchers.IO) { scanVideosInFolder(context, f.path) }
        isLoading = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!hasPermission) {
            PermissionRequest(
                firstFocusRequester = effectiveFirstFocusRequester,
                onRequest = { permissionLauncher.launch(storagePermissions) }
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                LokalHeader(
                    count = if (selectedFolder != null) folderVideos.size else folders.sumOf { it.videoCount },
                    selectedFolder = selectedFolder,
                    isGridMode = isGridMode,
                    onGridToggle = {
                        settingsScope.launch {
                            context.settingsDataStore.edit { it[com.dinsplayer.app.data.KEY_LOKAL_GRID] = !isGridMode }
                        }
                    },
                    sortMode = sortMode,
                    onSortToggle = {
                        settingsScope.launch {
                            context.settingsDataStore.edit { it[com.dinsplayer.app.data.KEY_LOKAL_SORT] = (sortMode + 1) % 3 }
                        }
                    },
                    onBack = { selectedFolder = null; folderVideos = emptyList() }
                )

                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentBlue)
                    }
                } else if (selectedFolder == null) {
                    // Folder list
                    val sortedFolders = when (sortMode) {
                        0 -> folders // Default Date
                        1 -> folders.sortedBy { it.name }
                        else -> folders.sortedByDescending { it.totalSize }
                    }
                    if (folders.isEmpty()) {
                        EmptyState("Tidak ada video ditemukan di perangkat ini")
                    } else if (isGridMode) {
                        // Fixed columns stretched the cards into flat slabs on wide screens:
                        // a 1:1 thumbnail cell grew to 331dp on an 800dp-wide landscape phone.
                        // Adaptive keeps the cell size stable and adds columns instead.
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = if (wide) 150.dp else 160.dp),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            itemsIndexed(sortedFolders, key = { _, it -> it.path }) { index, folder ->
                                FolderCardGrid(
                                    folder = folder,
                                    wide = wide,
                                    modifier = if (index == 0) Modifier.focusRequester(effectiveFirstFocusRequester) else Modifier,
                                    onClick = { selectedFolder = folder }
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            itemsIndexed(sortedFolders, key = { _, it -> it.path }) { index, folder ->
                                FolderCard(
                                    folder = folder,
                                    modifier = if (index == 0) Modifier.focusRequester(effectiveFirstFocusRequester) else Modifier,
                                    onClick = { selectedFolder = folder }
                                )
                            }
                        }
                    }
                } else {
                    // Video list inside folder
                    val sortedVideos = when (sortMode) {
                        0 -> folderVideos // Default is by date descending
                        1 -> folderVideos.sortedBy { it.name }
                        else -> folderVideos.sortedByDescending { it.size }
                    }
                    if (folderVideos.isEmpty()) {
                        EmptyState("Tidak ada video di folder ini")
                    } else if (isGridMode) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = if (wide) 150.dp else 160.dp),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(sortedVideos, key = { it.id }) { video ->
                                VideoCardGrid(
                                    video = video,
                                    onPlay = { playVideo(context, video, sortedVideos) },
                                    onDelete = { videoToDelete = video }
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(sortedVideos, key = { it.id }) { video ->
                                VideoCard(
                                    video = video,
                                    onPlay = { playVideo(context, video, sortedVideos) },
                                    onDelete = { videoToDelete = video }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Dialog konfirmasi hapus video
        videoToDelete?.let { video ->
            DeleteConfirmDialog(
                video = video,
                onDismiss = { videoToDelete = null },
                onConfirm = {
                    videoToDelete = null
                    // Compare URLs, not titles: DISPLAY_NAME is not unique across folders,
                    // so two files named "video.mp4" would stop each other's playback.
                    if (Media3Manager.currentUrl.value == videoContentUri(video).toString()) {
                        Media3Manager.stop()
                    }
                    deleteTarget = video
                    executeDeleteVideo(
                        context = context,
                        video = video,
                        deleteIntentLauncher = deleteIntentLauncher,
                        onDeleted = { refreshTrigger++ }
                    )
                }
            )
        }
    }
}

@Composable
private fun LokalHeader(
    count: Int, 
    selectedFolder: VideoFolder?, 
    isGridMode: Boolean,
    onGridToggle: () -> Unit,
    sortMode: Int,
    onSortToggle: () -> Unit,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Translucent so the wallpaper carries through; opaque here would cut a solid band
            // across the top of the list.
            .background(BgBase.copy(alpha = 0.72f))
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectedFolder != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.tvFocusRing(CircleShape)
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = "Kembali", tint = TextSecondary)
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (selectedFolder != null) selectedFolder.name else "Lokal",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = TextPrimary)
            )
            Text(
                text = "$count Video ditemukan",
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
            )
        }
        
        IconButton(
            onClick = onSortToggle,
            modifier = Modifier.tvFocusRing(CircleShape)
        ) {
            val sortIcon = when(sortMode) {
                0 -> Icons.Filled.AccessTime // Date
                1 -> Icons.Filled.SortByAlpha // Name
                else -> Icons.Filled.DataUsage // Size
            }
            Icon(sortIcon, contentDescription = "Urutkan", tint = AccentBlue, modifier = Modifier.size(20.dp))
        }
        
        IconButton(
            onClick = onGridToggle,
            modifier = Modifier.tvFocusRing(CircleShape)
        ) {
            Icon(
                if (isGridMode) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView, 
                contentDescription = "Tampilan", 
                tint = AccentBlue, 
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun FolderCard(
    folder: VideoFolder,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BgElevated)
            .border(1.dp, DividerColor, RoundedCornerShape(14.dp))
            .tvFocusRing(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail or folder icon
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BgSurface),
            contentAlignment = Alignment.Center
        ) {
            if (folder.thumbnailUri.isNotBlank()) {
                AsyncImage(
                    model = folder.thumbnailUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(32.dp))
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleSmall.copy(color = TextPrimary),
                maxLines = 1, overflow = TextOverflow.Clip,
                modifier = Modifier.tvMarquee()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${folder.videoCount} video  •  ${formatSize(folder.totalSize)}",
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
            )
        }

        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = TextMuted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun VideoCard(
    video: VideoFile,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgElevated)
            .border(1.dp, DividerColor, RoundedCornerShape(12.dp))
            .tvFocusRing(RoundedCornerShape(12.dp))
            .clickable { onPlay() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 90.dp, height = 58.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(BgSurface),
            contentAlignment = Alignment.Center
        ) {
            if (video.thumbnailUri.isNotBlank()) {
                AsyncImage(
                    model = video.thumbnailUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Filled.VideoLibrary, contentDescription = null, tint = TextMuted, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.name,
                style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${formatDuration(video.duration)}  •  ${formatSize(video.size)}",
                style = MaterialTheme.typography.labelSmall.copy(color = TextMuted)
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Hapus video",
                tint = ColorError.copy(alpha = 0.8f),
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(
            onClick = onPlay,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Putar", tint = AccentBlue, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun PermissionRequest(
    firstFocusRequester: FocusRequester? = null,
    onRequest: () -> Unit
) {
    val buttonFocusRequester = firstFocusRequester ?: remember { FocusRequester() }

    LaunchedEffect(Unit) {
        for (delayMs in listOf(50L, 150L, 300L)) {
            kotlinx.coroutines.delay(delayMs)
            try {
                buttonFocusRequester.requestFocus()
                break
            } catch (_: Exception) {}
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(AccentBlue.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Akses Media Diperlukan",
            style = MaterialTheme.typography.titleLarge.copy(color = TextPrimary, fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Izinkan akses penyimpanan agar kami dapat memindai dan menampilkan koleksi video lokal Anda dengan rapi.",
            style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onRequest,
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentBlue,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
            modifier = Modifier
                .focusRequester(buttonFocusRequester)
                .tvFocusRing(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White,
                    width = 3.dp,
                    scaleOnFocus = 1.08f
                )
        ) {
            Text("Berikan Akses", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(BgSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.VideoLibrary, contentDescription = null, tint = TextMuted.copy(alpha = 0.7f), modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(message, style = MaterialTheme.typography.titleMedium.copy(color = TextSecondary, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(8.dp))
            Text("Silakan tambahkan video atau pilih folder lain.", style = MaterialTheme.typography.bodySmall.copy(color = TextMuted))
        }
    }
}

@Composable
private fun FolderCardGrid(
    folder: VideoFolder,
    wide: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BgElevated)
            .border(1.dp, DividerColor, RoundedCornerShape(14.dp))
            .tvFocusRing(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Square thumbnails are the portrait look; on wide screens they compete with
                // the short viewport, so widen them to 16:10 there.
                .aspectRatio(if (wide) 1.6f else 1f)
                .clip(RoundedCornerShape(10.dp))
                .background(BgSurface),
            contentAlignment = Alignment.Center
        ) {
            if (folder.thumbnailUri.isNotBlank()) {
                AsyncImage(
                    model = folder.thumbnailUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = folder.name,
            style = MaterialTheme.typography.titleSmall.copy(color = TextPrimary),
            maxLines = 1, overflow = TextOverflow.Clip,
            modifier = Modifier.tvMarquee()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${folder.videoCount} video",
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
        )
    }
}

@Composable
private fun VideoCardGrid(
    video: VideoFile,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgElevated)
            .border(1.dp, DividerColor, RoundedCornerShape(12.dp))
            .tvFocusRing(RoundedCornerShape(12.dp))
            .clickable { onPlay() }
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f/9f)
                .clip(RoundedCornerShape(8.dp))
                .background(BgSurface),
            contentAlignment = Alignment.Center
        ) {
            if (video.thumbnailUri.isNotBlank()) {
                AsyncImage(
                    model = video.thumbnailUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(32.dp))
            }

            // Delete button on top-end of thumbnail
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Hapus video",
                    tint = ColorError.copy(alpha = 0.9f),
                    modifier = Modifier.size(16.dp)
                )
            }
            
            // Duration badge
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = formatDuration(video.duration),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = video.name,
            style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
            maxLines = 1, overflow = TextOverflow.Clip,
            modifier = Modifier.tvMarquee()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = formatSize(video.size),
            style = MaterialTheme.typography.labelSmall.copy(color = TextMuted)
        )
    }
}

private fun playVideo(context: Context, video: VideoFile, folderVideos: List<VideoFile>) {
    val playlist = folderVideos.map { v ->
        val vUri = videoContentUri(v)
        com.dinsplayer.app.features.player.ChannelItem(
            id = v.id.toString(),
            name = v.name,
            groupTitle = "Lokal",
            logoUrl = vUri.toString(),
            url = vUri.toString()
        )
    }
    Media3Manager.setActiveChannels(playlist, video.id.toString())
    Media3Manager.play(url = videoContentUri(video).toString(), title = video.name)
    Media3Manager.launchPlayer(context)
}

// ── Helpers ────────────────────────────────────────────────────────────────

private fun checkStoragePermission(context: Context): Boolean {
    val perm = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_VIDEO
    else
        Manifest.permission.READ_EXTERNAL_STORAGE
    return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}

private fun scanVideoFolders(context: Context): List<VideoFolder> {
    val folderMap = mutableMapOf<String, VideoFolder>()
    val collection = if (Build.VERSION.SDK_INT >= 29)
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DATA,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Video.Media.BUCKET_ID,
        MediaStore.Video.Media.SIZE,
    )

    context.contentResolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")
        ?.use { cursor ->
            val idCol      = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol    = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dataCol    = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val bucketCol  = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val sizeCol    = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            while (cursor.moveToNext()) {
                val id         = cursor.getLong(idCol)
                val bucketName = cursor.getString(bucketCol) ?: "Lainnya"
                val path       = cursor.getString(dataCol) ?: continue
                val size       = cursor.getLong(sizeCol)
                val folderPath = path.substringBeforeLast("/")
                val thumbUri   = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString()

                val existing = folderMap[folderPath]
                folderMap[folderPath] = VideoFolder(
                    name         = bucketName,
                    path         = folderPath,
                    videoCount   = (existing?.videoCount ?: 0) + 1,
                    totalSize    = (existing?.totalSize ?: 0L) + size,
                    thumbnailUri = existing?.thumbnailUri?.ifBlank { thumbUri } ?: thumbUri,
                )
            }
        }
    return folderMap.values.sortedByDescending { it.videoCount }
}

private fun scanVideosInFolder(context: Context, folderPath: String): List<VideoFile> {
    val videos = mutableListOf<VideoFile>()
    val collection = if (Build.VERSION.SDK_INT >= 29)
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DATA,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_ADDED,
    )
    val selection = "${MediaStore.Video.Media.DATA} LIKE ?"
    val args      = arrayOf("$folderPath/%")

    context.contentResolver.query(collection, projection, selection, args, "${MediaStore.Video.Media.DATE_ADDED} DESC")
        ?.use { cursor ->
            val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dataCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id   = cursor.getLong(idCol)
                val path = cursor.getString(dataCol) ?: continue
                videos.add(VideoFile(
                    id           = id,
                    name         = cursor.getString(nameCol) ?: "Video",
                    path         = path,
                    folderName   = folderPath.substringAfterLast("/"),
                    duration     = cursor.getLong(durationCol),
                    size         = cursor.getLong(sizeCol),
                    dateAdded    = cursor.getLong(dateCol),
                    thumbnailUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString(),
                ))
            }
        }
    return videos
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024         -> "%.0f KB".format(bytes / 1_024.0)
        else                   -> "$bytes B"
    }
}

@Composable
private fun DeleteConfirmDialog(
    video: VideoFile,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(20.dp))
                .background(BgElevated)
                .border(1.dp, DividerColor, RoundedCornerShape(20.dp))
                .padding(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(ColorError.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = ColorError,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Text(
                    text = "Hapus Video?",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 18.sp
                    )
                )

                Text(
                    text = video.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "File video ini akan dihapus permanen dari penyimpanan perangkat Anda.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, DividerColor),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextSecondary
                        )
                    ) {
                        Text(
                            text = "Batal",
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                    }

                    Button(
                        onClick = onConfirm,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ColorError,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = "Hapus",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

private fun videoContentUri(video: VideoFile): Uri =
    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video.id)

private fun executeDeleteVideo(
    context: Context,
    video: VideoFile,
    deleteIntentLauncher: ActivityResultLauncher<IntentSenderRequest>,
    onDeleted: () -> Unit
) {
    val contentResolver = context.contentResolver
    val uri = videoContentUri(video)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val pendingIntent = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
            deleteIntentLauncher.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal menyiapkan penghapusan: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
            val rows = contentResolver.delete(uri, null, null)
            if (rows > 0) {
                Toast.makeText(context, "Video berhasil dihapus", Toast.LENGTH_SHORT).show()
                onDeleted()
            } else {
                Toast.makeText(context, "Gagal menghapus file", Toast.LENGTH_SHORT).show()
            }
        } catch (securityException: SecurityException) {
            val recoverableSecurityException = securityException as? RecoverableSecurityException
            if (recoverableSecurityException != null) {
                val intentSender = recoverableSecurityException.userAction.actionIntent.intentSender
                deleteIntentLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build()
                )
            } else {
                Toast.makeText(context, "Izin ditolak: ${securityException.message}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal menghapus: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    } else {
        try {
            val rows = contentResolver.delete(uri, null, null)
            val fileDeleted = java.io.File(video.path).delete()
            if (rows > 0 || fileDeleted) {
                Toast.makeText(context, "Video berhasil dihapus", Toast.LENGTH_SHORT).show()
                onDeleted()
            } else {
                Toast.makeText(context, "Gagal menghapus file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal menghapus: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
