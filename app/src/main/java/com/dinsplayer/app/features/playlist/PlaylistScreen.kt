package com.dinsplayer.app.features.playlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import com.dinsplayer.app.features.mobile.rememberIsWideNav
import com.dinsplayer.app.features.mobile.tvFocusRing
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.delay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dinsplayer.app.data.db.AppDatabase
import com.dinsplayer.app.data.models.Playlist
import com.dinsplayer.app.data.models.PlaylistType
import com.dinsplayer.app.ui.theme.*
import com.dinsplayer.app.core.network.FallbackDns
import kotlinx.coroutines.launch

@Composable
fun PlaylistScreen(
    importUri: android.net.Uri? = null,
    onImportConsumed: () -> Unit = {},
    onPlaylistSelected: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).playlistDao() }
    val scope = rememberCoroutineScope()

    val playlists by dao.observeAll().collectAsState(initial = emptyList())
    var showAddChoiceDialog by remember { mutableStateOf(false) }
    var showWebServerDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var playlistToEdit by remember { mutableStateOf<Playlist?>(null) }
    // Copied out of the shared holder so the form keeps its file after the holder is cleared.
    var dialogImportUri by remember { mutableStateOf<android.net.Uri?>(null) }

    LaunchedEffect(importUri) {
        if (importUri != null) {
            playlistToEdit = null
            dialogImportUri = importUri
            showAddDialog = true
            onImportConsumed()
        }
    }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // Guard against double-fire from D-pad KEYDOWN+KEYUP both triggering onClick
    var isSaving by remember { mutableStateOf(false) }

    val isLandscape = rememberIsWideNav()
    var isAddTriggered by remember { mutableStateOf(false) }
    val isAddActive = isAddTriggered || showAddChoiceDialog || showWebServerDialog || (showAddDialog && playlistToEdit == null)
    val addIconRotation by animateFloatAsState(
        targetValue = if (isAddActive) 135f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "addIconRotation"
    )
    val triggerAddPlaylist: () -> Unit = {
        if (!isAddTriggered && !showAddChoiceDialog && !showAddDialog && !showWebServerDialog) {
            isAddTriggered = true
            scope.launch {
                delay(85L)
                playlistToEdit = null
                showAddChoiceDialog = true
                isAddTriggered = false
            }
        }
    }

    val filteredPlaylists = remember(playlists, searchQuery) {
        if (searchQuery.isBlank()) playlists
        else playlists.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.source.contains(searchQuery, ignoreCase = true) ||
            it.serverUrl.contains(searchQuery, ignoreCase = true)
        }
    }

    if (playlistToDelete != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Konfirmasi Hapus") },
            text = { Text("Apakah Anda yakin ingin menghapus playlist '${playlistToDelete?.name}'?") },
            confirmButton = {
                TextButton(onClick = {
                    playlistToDelete?.let { p ->
                        scope.launch {
                            dao.delete(p)
                            if (p.isActive) {
                                val remaining = playlists.filter { it.id != p.id }
                                if (remaining.isNotEmpty()) {
                                    dao.setActiveExclusive(remaining.first().id)
                                }
                            }
                        }
                    }
                    playlistToDelete = null
                }) {
                    Text("Hapus", color = ColorError)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text("Batal")
                }
            },
            containerColor = BgDialog,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showSearch) {
                    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .tvFocusRing(RoundedCornerShape(10.dp)),
                        placeholder = { Text("Cari playlist...", color = TextMuted) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = TextPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent,
                            unfocusedContainerColor = BgElevated,
                            focusedContainerColor = BgElevated,
                        ),
                        shape = RoundedCornerShape(10.dp),
                        leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextSecondary) }
                    )
                    IconButton(
                        onClick = { showSearch = false; searchQuery = "" },
                        modifier = Modifier.tvFocusRing(CircleShape),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Tutup pencarian", tint = TextSecondary)
                    }
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Playlist", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = TextPrimary))
                        Text(
                            text = playlists.firstOrNull { it.isActive }?.name?.let { "Aktif: $it" } ?: "Belum ada playlist aktif",
                            style = MaterialTheme.typography.bodySmall.copy(color = AccentBlue),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (isLandscape) {
                            var isAddHeaderFocused by remember { mutableStateOf(false) }
                            val headerAddInteraction = remember { MutableInteractionSource() }
                            val isHeaderAddPressed by headerAddInteraction.collectIsPressedAsState()
                            val headerAddScale by animateFloatAsState(
                                targetValue = when {
                                    isHeaderAddPressed -> 0.84f
                                    isAddTriggered -> 1.18f
                                    else -> 1f
                                },
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                ),
                                label = "headerAddScale"
                            )
                            IconButton(
                                onClick = triggerAddPlaylist,
                                interactionSource = headerAddInteraction,
                                modifier = Modifier
                                    .size(40.dp)
                                    .graphicsLayer {
                                        scaleX = headerAddScale
                                        scaleY = headerAddScale
                                    }
                                    .onFocusChanged { isAddHeaderFocused = it.isFocused || it.hasFocus }
                                    .border(
                                        width = if (isAddHeaderFocused) 2.5.dp else 0.dp,
                                        color = if (isAddHeaderFocused) Color.White else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .background(
                                        if (isAddHeaderFocused) Color.White.copy(alpha = 0.25f) else Color.Transparent,
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = "Tambah Playlist",
                                    tint = if (isAddHeaderFocused) Color.White else TextSecondary,
                                    modifier = Modifier.graphicsLayer { rotationZ = addIconRotation }
                                )
                            }
                        }
                        if (playlists.isNotEmpty()) {
                            var isSearchFocused by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = { showSearch = true },
                                modifier = Modifier
                                    .onFocusChanged { isSearchFocused = it.isFocused || it.hasFocus }
                                    .border(
                                        width = if (isSearchFocused) 2.5.dp else 0.dp,
                                        color = if (isSearchFocused) Color.White else Color.Transparent,
                                        shape = CircleShape
                                    ),
                            ) {
                                Icon(Icons.Filled.Search, contentDescription = "Cari", tint = if (isSearchFocused) Color.White else TextSecondary)
                            }
                        }
                    }
                }
            }

            if (playlists.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, tint = TextMuted, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Belum ada playlist", style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary))
                        Spacer(Modifier.height(16.dp))
                        var isEmptyBtnFocused by remember { mutableStateOf(false) }
                        Button(
                            onClick = triggerAddPlaylist,
                            modifier = Modifier
                                .onFocusChanged { isEmptyBtnFocused = it.isFocused || it.hasFocus }
                                .border(
                                    width = if (isEmptyBtnFocused) 3.dp else 1.dp,
                                    color = if (isEmptyBtnFocused) Color.White else AccentBlue,
                                    shape = RoundedCornerShape(12.dp)
                                ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isEmptyBtnFocused) Color(0xFF2563EB) else AccentBlue
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Tambah Playlist", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (filteredPlaylists.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Playlist tidak ditemukan", style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary))
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = if (isLandscape) 12.dp else 88.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredPlaylists, key = { it.id }) { playlist ->
                        PlaylistCard(
                            playlist  = playlist,
                            onDelete  = { playlistToDelete = playlist },
                            onEdit    = { playlistToEdit = playlist; showAddDialog = true },
                            onToggle  = { scope.launch { dao.setActiveExclusive(playlist.id) } },
                            onOpen    = { if (playlist.isActive) onPlaylistSelected(playlist.id) }
                        )
                    }
                }
            }
        }

        // FAB — bottom-left in portrait mode only
        if (!isLandscape) {
            var isFabFocused by remember { mutableStateOf(false) }
            val fabInteraction = remember { MutableInteractionSource() }
            val isFabPressed by fabInteraction.collectIsPressedAsState()
            val fabScale by animateFloatAsState(
                targetValue = when {
                    isFabPressed -> 0.86f
                    isAddTriggered -> 1.15f
                    else -> 1f
                },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "fabScale"
            )
            FloatingActionButton(
                onClick  = triggerAddPlaylist,
                interactionSource = fabInteraction,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, bottom = 20.dp)
                    .graphicsLayer {
                        scaleX = fabScale
                        scaleY = fabScale
                    }
                    .onFocusChanged { isFabFocused = it.isFocused || it.hasFocus }
                    .border(
                        width = if (isFabFocused) 3.5.dp else 0.dp,
                        color = if (isFabFocused) Color.White else Color.Transparent,
                        shape = CircleShape
                    ),
                containerColor = if (isFabFocused) Color(0xFF2563EB) else AccentBlue,
                contentColor   = Color.White,
                shape          = CircleShape,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Tambah playlist",
                    modifier = Modifier
                        .size(28.dp)
                        .graphicsLayer { rotationZ = addIconRotation }
                )
            }
        }
    }

    if (showAddChoiceDialog) {
        AddPlaylistChoiceDialog(
            onDismiss = { showAddChoiceDialog = false },
            onSelectManual = {
                showAddChoiceDialog = false
                playlistToEdit = null
                showAddDialog = true
            },
            onSelectWebServer = {
                showAddChoiceDialog = false
                showWebServerDialog = true
            }
        )
    }

    if (showWebServerDialog) {
        WebServerPlaylistDialog(
            context = context,
            existingPlaylists = playlists,
            onDismiss = { showWebServerDialog = false },
            onAddPlaylist = { newPlaylist ->
                scope.launch {
                    val shouldBeActive = playlists.isEmpty() || newPlaylist.isActive
                    val insertedId = dao.insert(newPlaylist.copy(isActive = shouldBeActive))
                    if (insertedId > 0L && shouldBeActive) {
                        dao.setActiveExclusive(insertedId)
                    }
                }
            }
        )
    }

    if (showAddDialog) {
        // key() forces the dialog to fully remount — and reset all its remember state —
        // whenever the playlist being edited changes. Without this, editing playlist B
        // after editing playlist A shows A's stale field values.
        key(playlistToEdit?.id) {
            AddPlaylistDialog(
                playlistToEdit = playlistToEdit,
                existingPlaylists = playlists,
                initialImportUri = dialogImportUri,
                onDismiss = { showAddDialog = false; playlistToEdit = null; isSaving = false; dialogImportUri = null },
                onAdd     = { newPlaylist ->
                    if (isSaving) return@AddPlaylistDialog
                    isSaving = true

                    scope.launch {
                        if (newPlaylist.id > 0L) {
                            // Mode edit: id sudah disematkan oleh dialog
                            dao.update(newPlaylist)
                        } else {
                            // Mode tambah baru
                            val shouldBeActive = playlists.isEmpty() || newPlaylist.isActive
                            val insertedId = dao.insert(newPlaylist.copy(isActive = shouldBeActive))
                            if (insertedId > 0L && shouldBeActive) {
                                dao.setActiveExclusive(insertedId)
                            }
                        }
                        isSaving = false
                    }
                    showAddDialog = false
                    playlistToEdit = null
                    dialogImportUri = null
                }
            )
        }
    }
}


private const val MAX_PLAYLIST_BYTES = 20L * 1024 * 1024

@Composable
private fun PlaylistCard(playlist: Playlist, onDelete: () -> Unit, onEdit: () -> Unit, onToggle: () -> Unit, onOpen: () -> Unit) {
    val wideNav = rememberIsWideNav()
    val cardModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .background(BgElevated)
        .border(1.dp, if (playlist.isActive) AccentBlue.copy(alpha = 0.3f) else DividerColor, RoundedCornerShape(14.dp))
        .then(
            // Parent and children were both D-pad targets. Spatial focus then chose between the
            // card and action buttons, skipping actions unpredictably. TV gets leaf targets only.
            if (wideNav) Modifier else Modifier
                .tvFocusRing(RoundedCornerShape(14.dp))
                .clickable(enabled = playlist.isActive) { onOpen() }
        )
        .padding(14.dp)
    Row(
        modifier = cardModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Type badge
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AccentBlueFaint),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (playlist.type) {
                    PlaylistType.M3U_URL  -> Icons.Filled.Link
                    PlaylistType.M3U_FILE -> Icons.Filled.FolderOpen
                    PlaylistType.XTREAM   -> Icons.Filled.Tv
                },
                contentDescription = null,
                tint   = AccentBlue,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text  = playlist.name,
                    style = MaterialTheme.typography.titleSmall.copy(color = TextPrimary, fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (playlist.isActive) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = ColorSuccess.copy(alpha = 0.15f)
                    ) {
                        Text("Aktif", style = MaterialTheme.typography.labelSmall.copy(color = ColorSuccess), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = playlist.type.name,
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
            )
            val src = playlist.source.ifBlank { playlist.serverUrl }
            if (src.isNotBlank()) {
                Text(
                    text = src,
                    style = MaterialTheme.typography.labelSmall.copy(color = TextMuted),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (wideNav) {
            // TV needs adjacent, labelled targets. The old stacked icon buttons made D-pad Down
            // leave the card before Edit/Hapus, and "Toggle" gave no clue about its result.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (playlist.isActive) {
                    var isBukaFocused by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = onOpen,
                        modifier = Modifier.onFocusChanged { isBukaFocused = it.isFocused || it.hasFocus },
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            if (isBukaFocused) 2.5.dp else 1.dp,
                            if (isBukaFocused) Color.White else AccentBlue
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isBukaFocused) Color.White.copy(alpha = 0.15f) else Color.Transparent
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Buka")
                    }
                }
                var isToggleFocused by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = onToggle,
                    modifier = Modifier.onFocusChanged { isToggleFocused = it.isFocused || it.hasFocus },
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        if (isToggleFocused) 2.5.dp else 1.dp,
                        if (isToggleFocused) Color.White else if (playlist.isActive) AccentBlue else DividerColor,
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isToggleFocused) Color.White.copy(alpha = 0.15f) else Color.Transparent
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(
                        if (playlist.isActive) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (playlist.isActive) "Aktif" else "Aktifkan")
                }
                var isEditFocused by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.onFocusChanged { isEditFocused = it.isFocused || it.hasFocus },
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        if (isEditFocused) 2.5.dp else 1.dp,
                        if (isEditFocused) Color.White else DividerColor
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isEditFocused) Color.White.copy(alpha = 0.15f) else Color.Transparent
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
                var isDeleteFocused by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.onFocusChanged { isDeleteFocused = it.isFocused || it.hasFocus },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = ColorError,
                        containerColor = if (isDeleteFocused) ColorError.copy(alpha = 0.15f) else Color.Transparent
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        if (isDeleteFocused) 2.5.dp else 1.dp,
                        if (isDeleteFocused) Color.White else ColorError.copy(alpha = 0.6f)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Hapus")
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onToggle, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = if (playlist.isActive) Icons.Filled.ToggleOn else Icons.Filled.ToggleOff,
                        contentDescription = if (playlist.isActive) "Playlist aktif" else "Aktifkan playlist",
                        tint = if (playlist.isActive) AccentBlue else TextMuted,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = "Hapus", tint = ColorError.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AddPlaylistDialog(
    playlistToEdit: Playlist?,
    existingPlaylists: List<Playlist>,
    initialImportUri: android.net.Uri? = null,
    onDismiss: () -> Unit,
    onAdd: (Playlist) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedType by remember { mutableIntStateOf(if (playlistToEdit?.type == PlaylistType.XTREAM) 1 else 0) }
    var name by remember { mutableStateOf(playlistToEdit?.name ?: "") }
    var urlOrRaw by remember { mutableStateOf(playlistToEdit?.source ?: "") }
    var username by remember { mutableStateOf(playlistToEdit?.username ?: "") }
    var password by remember { mutableStateOf(playlistToEdit?.password ?: "") }
    var serverUrl by remember { mutableStateOf(playlistToEdit?.serverUrl ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    var customHeaders by remember { mutableStateOf(playlistToEdit?.customHeaders ?: "") }
    var showCustomHeaders by remember { mutableStateOf((playlistToEdit?.customHeaders ?: "").isNotBlank()) }

    val uaPresets = listOf("DinsPlayer/1.0 (Android; Linux; U; ExoPlayer) Chrome/129.0.0.0 Safari/537.36", "TiviMate/4.7.0", "VLC/3.0.18 LibVLC/3.0.18", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
    val existingUa = playlistToEdit?.userAgent?.takeIf { it.isNotBlank() && it != "EMPTY_UA" }
    var selectedUaPreset by remember { mutableStateOf<String?>(existingUa?.takeIf { uaPresets.contains(it) }) }
    var customUaText by remember { mutableStateOf(existingUa?.takeIf { !uaPresets.contains(it) } ?: "") }
    var showCustomUa by remember { mutableStateOf(existingUa != null && !uaPresets.contains(existingUa)) }

    val currentUserAgent by remember {
        derivedStateOf {
            when {
                showCustomUa -> customUaText
                selectedUaPreset != null -> selectedUaPreset!!
                else -> ""
            }
        }
    }

    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    var testMessage by remember { mutableStateOf("") }
    var duplicateError by remember { mutableStateOf("") }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    fun importFromUri(uri: android.net.Uri?) {
        if (uri != null) {
            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val playlistsDir = java.io.File(context.filesDir, "playlists").apply { if (!exists()) mkdirs() }
                    var displayName: String? = null
                    try {
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (cursor.moveToFirst() && nameIdx != -1) {
                                displayName = cursor.getString(nameIdx)
                            }
                        }
                    } catch (e: Exception) { /* ignore query error */ }

                    val baseName = displayName?.substringBeforeLast(".")?.ifBlank { null } ?: "local_playlist"
                    val ext = displayName?.substringAfterLast(".", "m3u") ?: "m3u"
                    val destFile = java.io.File(playlistsDir, "${baseName}_${System.currentTimeMillis()}.$ext")

                    context.contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output ->
                            // Anything can arrive as octet-stream, including a video. Cap the copy
                            // so a wrong file cannot fill internal storage.
                            val buffer = ByteArray(8 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_PLAYLIST_BYTES) {
                                    destFile.delete()
                                    throw java.io.IOException("ukuran file melebihi 20 MB, ini bukan file playlist")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }

                    val sampleText = destFile.bufferedReader().use { r ->
                        (1..25).mapNotNull { r.readLine() }.joinToString("\n")
                    }
                    val isM3u = sampleText.contains("#EXTM3U") || sampleText.contains("#EXTINF:")

                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        urlOrRaw = destFile.absolutePath
                        if (name.isBlank() && displayName != null) {
                            name = displayName!!.substringBeforeLast(".").replace("_", " ").trim()
                        }
                        testResult = isM3u
                        testMessage = if (isM3u) {
                            "✅ File '${displayName ?: destFile.name}' berhasil dimuat (${destFile.length() / 1024} KB)"
                        } else {
                            "⚠️ File '${displayName ?: destFile.name}' dimuat (${destFile.length() / 1024} KB), tapi pastikan berisi format M3U"
                        }
                    }
                } catch (e: Exception) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        urlOrRaw = uri.toString()
                        testResult = false
                        testMessage = "❌ Gagal memproses file: ${e.message}"
                    }
                }
            }
        }
    }

    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> importFromUri(uri) }

    // A file opened from another app arrives here already chosen.
    LaunchedEffect(initialImportUri) { importFromUri(initialImportUri) }

    fun handlePaste() {
        clipboardManager.getText()?.text?.let {
            urlOrRaw = it
            testResult = null
        }
    }

    fun handleTest() {
        if (selectedType == 0 && urlOrRaw.isBlank()) {
            testResult = false
            testMessage = "Silakan masukkan URL atau file M3U terlebih dahulu"
            return
        }
        if (selectedType == 1 && (serverUrl.isBlank() || username.isBlank())) {
            testResult = false
            testMessage = "Silakan isi URL Server dan Username terlebih dahulu"
            return
        }
        
        isTesting = true
        testResult = null
        coroutineScope.launch {
            try {
                if (selectedType == 1) { // Xtream
                    val ua = currentUserAgent.takeIf { it.isNotBlank() && it != "EMPTY_UA" }
                        ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.60 Mobile Safari/537.36"
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val client = FallbackDns.configure(
                                okhttp3.OkHttpClient.Builder()
                                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            ).build()
                            val testUrl = "${serverUrl.trimEnd('/')}/player_api.php?username=${android.net.Uri.encode(username)}&password=${android.net.Uri.encode(password)}"
                            val req = okhttp3.Request.Builder().url(testUrl).header("User-Agent", ua).build()
                            val response = client.newCall(req).execute()
                            if (!response.isSuccessful) {
                                response.body?.close()
                                return@withContext Pair(false, "HTTP ${response.code}: Gagal akses panel")
                            }
                            val bodyStr = response.use { it.body?.string() } ?: ""
                            val parsed = org.json.JSONTokener(bodyStr).nextValue()
                            if (parsed is org.json.JSONObject && parsed.has("user_info")) {
                                val status = parsed.getJSONObject("user_info").optString("status", "")
                                if (status.equals("Active", ignoreCase = true)) {
                                    Pair(true, "✅ Login sukses! Status akun: Active")
                                } else {
                                    Pair(false, "⚠️ Akun tidak aktif (Status: $status)")
                                }
                            } else {
                                Pair(false, "❌ Respon server tidak valid atau otentikasi gagal")
                            }
                        } catch (e: Exception) {
                            Pair(false, "❌ Gagal terhubung: ${e.message?.take(60)}")
                        }
                    }
                    testResult = result.first
                    testMessage = result.second
                    isTesting = false
                    return@launch
                }
                
                // M3U logic: Local file, URI, or Raw Text
                if (urlOrRaw.startsWith("content://") || urlOrRaw.startsWith("file://") || urlOrRaw.startsWith("/")) {
                    val testPair = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val sample = if (urlOrRaw.startsWith("content://")) {
                                val uri = android.net.Uri.parse(urlOrRaw)
                                context.contentResolver.openInputStream(uri)?.use { stream ->
                                    stream.bufferedReader(Charsets.UTF_8).use { r ->
                                        (1..25).mapNotNull { r.readLine() }.joinToString("\n")
                                    }
                                } ?: ""
                            } else {
                                val file = java.io.File(urlOrRaw.removePrefix("file://"))
                                if (!file.exists()) return@withContext Pair(false, "❌ File tidak ditemukan di perangkat")
                                file.bufferedReader(Charsets.UTF_8).use { r ->
                                    (1..25).mapNotNull { r.readLine() }.joinToString("\n")
                                }
                            }
                            val isM3u = sample.contains("#EXTM3U") || sample.contains("#EXTINF:")
                            if (isM3u) {
                                Pair(true, "✅ File M3U valid dan siap digunakan")
                            } else {
                                Pair(false, "⚠️ Format file bukan M3U standard (#EXTM3U/#EXTINF tidak ditemukan)")
                            }
                        } catch (e: Exception) {
                            Pair(false, "❌ Gagal membaca file: ${e.message?.take(60)}")
                        }
                    }
                    testResult = testPair.first
                    testMessage = testPair.second
                    isTesting = false
                    return@launch
                }
                if (!urlOrRaw.startsWith("http")) {
                    val isM3u = urlOrRaw.contains("#EXTM3U") || urlOrRaw.contains("#EXTINF:")
                    testResult = isM3u
                    testMessage = if (isM3u) "✅ Isi raw M3U valid" else "⚠️ Format raw M3U tidak valid"
                    isTesting = false
                    return@launch
                }
                val ua = currentUserAgent
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val client = FallbackDns.configure(
                        okhttp3.OkHttpClient.Builder()
                            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true)
                    ).build()
                    val reqBuilder = okhttp3.Request.Builder().url(urlOrRaw.trim())
                    if (ua.isNotBlank() && ua != "EMPTY_UA") reqBuilder.header("User-Agent", ua)
                    try {
                        val response = client.newCall(reqBuilder.build()).execute()
                        if (!response.isSuccessful) {
                            return@withContext Pair(false, "HTTP ${response.code}: Server menolak request")
                        }
                        val source = response.body?.source()
                        source?.request(4096L)
                        val peek = source?.buffer?.snapshot()?.utf8() ?: ""
                        response.close()
                        
                        val trimmed = peek.trimStart()
                        val isM3u = trimmed.startsWith("#EXTM3U") || trimmed.contains("#EXTINF:")
                        val isTxt = trimmed.lines().take(10).any {
                            it.trim().startsWith("http://") || it.trim().startsWith("https://")
                        }
                        when {
                            isM3u  -> Pair(true,  "✅ Format M3U valid — siap digunakan\n\nSnippet:\n${trimmed.take(150)}...")
                            isTxt  -> Pair(true,  "✅ Format daftar URL valid")
                            else   -> Pair(false, "⚠️ URL bisa diakses tapi isinya bukan playlist M3U\n\nSnippet:\n${trimmed.take(100)}...")
                        }
                    } catch (e: Exception) {
                        Pair(false, "❌ Gagal terhubung: ${e.message?.take(60)}")
                    }
                }
                testResult = result.first
                testMessage = result.second
            } catch (e: Exception) {
                testResult = false
                testMessage = "❌ Error: ${e.message?.take(60)}"
            } finally {
                isTesting = false
            }
        }
    }

    fun handleDownloadToDevice() {
        if (name.isBlank() || urlOrRaw.isBlank()) {
            android.widget.Toast.makeText(context, "Nama dan Link/Isi M3U wajib diisi!", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        coroutineScope.launch {
            try {
                android.widget.Toast.makeText(context, "Mulai mengunduh playlist...", android.widget.Toast.LENGTH_SHORT).show()
                val isUrl = urlOrRaw.trim().startsWith("http://") || urlOrRaw.trim().startsWith("https://")
                val contentToSave = if (isUrl) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val client = FallbackDns.configure(
                            okhttp3.OkHttpClient.Builder()
                                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                                .callTimeout(150, java.util.concurrent.TimeUnit.SECONDS)
                                .followRedirects(true)
                                .followSslRedirects(true)
                        ).build()
                        val reqBuilder = okhttp3.Request.Builder().url(urlOrRaw.trim())
                        val ua = currentUserAgent
                        if (ua.isNotBlank()) reqBuilder.header("User-Agent", ua)
                        val response = client.newCall(reqBuilder.build()).execute()
                        if (!response.isSuccessful) throw Exception("Gagal mengunduh: HTTP ${response.code}")
                        val contentLength = response.body?.contentLength() ?: -1L
                        if (contentLength > 50_000_000L) throw Exception("File terlalu besar (>50 MB), tidak dapat disimpan")
                        response.body?.string() ?: throw Exception("Body kosong")
                    }
                } else {
                    urlOrRaw
                }
                
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val appDir = java.io.File(downloadsDir, "dinsplayer")
                if (!appDir.exists()) appDir.mkdirs()
                
                val safeName = name.replace("[^a-zA-Z0-9.-]".toRegex(), "_").takeIf { it.isNotBlank() } ?: "playlist"
                val file = java.io.File(appDir, "$safeName.m3u")
                file.writeText(contentToSave)
                
                android.widget.Toast.makeText(context, "✅ Berhasil disimpan di Downloads/dinsplayer/$safeName.m3u", android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "❌ Gagal menyimpan: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    val firstFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        for (delayMs in listOf(50L, 150L, 300L)) {
            delay(delayMs)
            try {
                firstFocusRequester.requestFocus()
                break
            } catch (_: Exception) {}
        }
    }

    var dialogEntered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        dialogEntered = true
    }
    val dialogScale by animateFloatAsState(
        targetValue = if (dialogEntered) 1f else 0.88f,
        animationSpec = spring(
            dampingRatio = 0.78f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "dialogScale"
    )
    val dialogAlpha by animateFloatAsState(
        targetValue = if (dialogEntered) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing),
        label = "dialogAlpha"
    )
    val dialogTranslateY by animateFloatAsState(
        targetValue = if (dialogEntered) 0f else 24f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "dialogTranslateY"
    )

    // decorFitsSystemWindows = false: the dialog owns a separate window, so the activity's
    // adjustResize never reaches it. With decor fitting on, WindowInsets.ime reads 0 inside the
    // dialog and imePadding does nothing; the keyboard then covers the Simpan/Batal row.
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = BgDialog,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(vertical = 16.dp)
                // decorFitsSystemWindows = false hands the insets to us, and safeDrawing carries
                // ime, nav bar, status bar, and cutout in one. The keyboard shrinks the viewport
                // instead of covering the buttons.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .graphicsLayer {
                    scaleX = dialogScale
                    scaleY = dialogScale
                    alpha = dialogAlpha
                    translationY = dialogTranslateY
                }
        ) {
            // Xtream mode stacks three fields, the UA block, and the test result: taller than a
            // 640dp screen, and taller still with the keyboard up. LiveTV/player use the same shape.
            // The column must scroll or the bottom is simply cut off.
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    if (playlistToEdit != null) "Edit Playlist" else "Tambah Playlist", 
                    style = MaterialTheme.typography.titleMedium.copy(color = TextPrimary, fontWeight = FontWeight.Bold)
                )

                // Mode Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("URL / M3U", "Xtream Codes").forEachIndexed { index, label ->
                        val modeColor = AccentBlue
                        var isToggleFocused by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .then(if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier)
                                .onFocusChanged { isToggleFocused = it.isFocused || it.hasFocus }
                                .border(
                                    width = if (isToggleFocused) 2.5.dp else 1.dp,
                                    color = if (isToggleFocused) Color.White else if (selectedType == index) modeColor else Color.Transparent,
                                    shape = RoundedCornerShape(9.dp)
                                )
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    if (isToggleFocused) Color.White.copy(alpha = 0.22f)
                                    else if (selectedType == index) modeColor.copy(alpha = 0.2f)
                                    else Color.Transparent
                                )
                                .clickable { selectedType = index }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(
                                    if (index == 0) Icons.AutoMirrored.Filled.List else Icons.Default.Tv,
                                    contentDescription = null,
                                    tint = if (isToggleFocused) Color.White else if (selectedType == index) modeColor else Color.White.copy(alpha = 0.4f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    label,
                                    color = if (isToggleFocused) Color.White else if (selectedType == index) modeColor else Color.White.copy(alpha = 0.4f),
                                    fontSize = 13.sp,
                                    fontWeight = if (selectedType == index || isToggleFocused) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                SlimTextField(
                    value = name, onValueChange = { name = it },
                    placeholder = "Nama Alias",
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Next)
                )

                if (selectedType == 0) {
                    SlimTextField(
                        value = urlOrRaw, onValueChange = { urlOrRaw = it; testResult = null; duplicateError = "" },
                        placeholder = "URL atau Isi Raw M3U",
                        minLines = 1, maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focusManager.clearFocus() }),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { filePickerLauncher.launch("*/*") }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Folder, contentDescription = "Pilih File", tint = AccentBlue, modifier = Modifier.size(16.dp))
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(onClick = { handlePaste() }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Edit, contentDescription = "Paste", tint = AccentBlue, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    )
                } else {
                    SlimTextField(
                        value = serverUrl, onValueChange = { serverUrl = it; testResult = null; duplicateError = "" },
                        placeholder = "URL Server Xtream (contoh: http://domain.com:8080)",
                        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Next)
                    )
                    SlimTextField(
                        value = username, onValueChange = { username = it; testResult = null; duplicateError = "" },
                        placeholder = "Username",
                        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Next)
                    )
                    SlimTextField(
                        value = password, onValueChange = { password = it; testResult = null },
                        placeholder = "Password",
                        isPassword = true, showPassword = showPassword,
                        onTogglePassword = { showPassword = !showPassword },
                        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focusManager.clearFocus() })
                    )
                }

                // Pesan duplikat inline
                if (duplicateError.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ColorError.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = ColorError,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            duplicateError,
                            color = ColorError,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                UaSelector(
                    presets = uaPresets, selectedPreset = selectedUaPreset, showCustom = showCustomUa,
                    customText = customUaText, accentColor = AccentBlue,
                    onPresetSelected = { selectedUaPreset = it; showCustomUa = false },
                    onCustomSelected = { selectedUaPreset = null; showCustomUa = true },
                    onDefaultSelected = { selectedUaPreset = null; showCustomUa = false },
                    onCustomTextChange = { customUaText = it }, focusManager = focusManager
                )

                // Custom Headers accordion
                androidx.compose.material3.Surface(
                    color = Color.White.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showCustomHeaders = !showCustomHeaders }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Code,
                                    contentDescription = null,
                                    tint = if (customHeaders.isNotBlank()) AccentBlue else TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    "Custom Headers",
                                    color = if (customHeaders.isNotBlank()) AccentBlue else TextSecondary,
                                    fontSize = 13.sp
                                )
                                if (customHeaders.isNotBlank()) {
                                    val lineCount = customHeaders.lines().count { it.contains(":") }
                                    androidx.compose.material3.Badge(
                                        containerColor = AccentBlue.copy(alpha = 0.2f)
                                    ) {
                                        Text("$lineCount", color = AccentBlue, fontSize = 10.sp)
                                    }
                                }
                            }
                            Icon(
                                if (showCustomHeaders) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        androidx.compose.animation.AnimatedVisibility(
                            visible = showCustomHeaders,
                            enter = androidx.compose.animation.expandVertically(),
                            exit = androidx.compose.animation.shrinkVertically()
                        ) {
                            Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                                Text(
                                    "Satu header per baris, format: Nama: Nilai",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                androidx.compose.material3.OutlinedTextField(
                                    value = customHeaders,
                                    onValueChange = { customHeaders = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(110.dp),
                                    placeholder = {
                                        Text(
                                            "X-Forwarded-For: 1.2.3.4\nReferer: https://site.com\nAuthorization: Bearer token",
                                            style = androidx.compose.ui.text.TextStyle(
                                                color = TextSecondary.copy(alpha = 0.5f),
                                                fontSize = 11.sp
                                            )
                                        )
                                    },
                                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = AccentBlue,
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.20f),
                                        cursorColor = AccentBlue,
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                                        focusedContainerColor = Color.White.copy(alpha = 0.08f),
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    var isTestFocused by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { handleTest() },
                        enabled = !isTesting,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (isTestFocused) Color.White else AccentBlue,
                            containerColor = if (isTestFocused) AccentBlue.copy(alpha = 0.25f) else Color.Transparent
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            if (isTestFocused) 2.5.dp else 1.dp,
                            if (isTestFocused) Color.White else AccentBlue
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .tvFocusRing(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White,
                                width = 2.5.dp,
                                scaleOnFocus = 1.03f
                            )
                            .onFocusChanged { isTestFocused = it.isFocused || it.hasFocus }
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(color = AccentBlue, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp)); Text("Mengecek...")
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp)); Text(if (selectedType == 0) "Test URL" else "Test Akun")
                        }
                    }
                }

                if (testResult != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (testResult == true) AccentBlue.copy(0.1f) else ColorError.copy(0.1f),
                                RoundedCornerShape(8.dp)
                            )
                            .border(1.dp, if (testResult == true) AccentBlue.copy(0.4f) else ColorError.copy(0.4f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                if (testResult == true) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (testResult == true) AccentBlue else ColorError,
                                modifier = Modifier.size(16.dp).padding(top = 2.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(testMessage, color = if (testResult == true) AccentBlue else ColorError, fontSize = 11.sp)
                        }
                    }
                }
                
                if (selectedType == 0 && playlistToEdit == null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    var isDownloadFocused by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { handleDownloadToDevice() },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (isDownloadFocused) Color.White else AccentBlue,
                            containerColor = if (isDownloadFocused) Color.White.copy(alpha = 0.15f) else Color.Transparent
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            if (isDownloadFocused) 2.5.dp else 1.dp,
                            if (isDownloadFocused) Color.White else AccentBlue
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isDownloadFocused = it.isFocused || it.hasFocus }
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Unduh/Simpan M3U ke Perangkat", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    var isBatalFocused by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .tvFocusRing(RoundedCornerShape(10.dp))
                            .onFocusChanged { isBatalFocused = it.isFocused || it.hasFocus },
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            if (isBatalFocused) 2.5.dp else 1.dp,
                            if (isBatalFocused) Color.White else DividerColor
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (isBatalFocused) Color.White else TextSecondary,
                            containerColor = if (isBatalFocused) Color.White.copy(alpha = 0.15f) else Color.Transparent
                        )
                    ) { Text("Batal") }

                    var isSaveFocused by remember { mutableStateOf(false) }
                    Button(
                        onClick = {
                            val sourceValid = when (selectedType) {
                                0 -> urlOrRaw.isNotBlank()
                                1 -> serverUrl.startsWith("http") && username.isNotBlank()
                                else -> false
                            }
                            if (name.isNotBlank() && sourceValid) {
                                val trimmedSource = urlOrRaw.trim()
                                // Normalisasi URL sebelum bandingkan (lowercase, tanpa trailing slash)
                                fun String.normalizeUrl() = this.trim().trimEnd('/').lowercase()
                                val normalizedSource = trimmedSource.normalizeUrl()
                                val normalizedServer = serverUrl.trimEnd('/').normalizeUrl()
                                val normalizedUser = username.trim()

                                val duplicate = existingPlaylists.find { p ->
                                    p.id != (playlistToEdit?.id ?: 0L) &&
                                    (
                                        (selectedType == 0 && p.source.normalizeUrl() == normalizedSource) ||
                                        (selectedType == 1 && p.serverUrl.normalizeUrl() == normalizedServer && p.username.trim() == normalizedUser)
                                    )
                                }

                                if (duplicate != null) {
                                    duplicateError = "Sumber ini sudah digunakan oleh playlist \"${duplicate.name}\". Hapus atau edit playlist tersebut terlebih dahulu."
                                    return@Button
                                }

                                // Tentukan tipe berdasarkan isi source, bukan prefix "http":
                                // - Path file absolut (dari file picker) → dimulai "/" → M3U_FILE
                                // - URL → dimulai "http" → M3U_URL
                                // - Raw M3U paste (dimulai "#EXTM3U") → M3U_URL agar M3uParser
                                //   membacanya via branch contains("#EXTM3U"), bukan mencoba
                                //   membuka sebagai path file.
                                val t = if (selectedType == 1) PlaylistType.XTREAM else PlaylistType.M3U_URL
                                val finalType = when {
                                    t == PlaylistType.XTREAM -> t
                                    trimmedSource.startsWith("/") || trimmedSource.startsWith("file://") -> PlaylistType.M3U_FILE
                                    else -> PlaylistType.M3U_URL
                                }
                                val finalUa = currentUserAgent.trim().takeIf { it.isNotBlank() } ?: ""
                                val isXtream = finalType == PlaylistType.XTREAM
                                onAdd(Playlist(
                                    id            = playlistToEdit?.id ?: 0L,
                                    name          = name.trim(),
                                    type          = finalType,
                                    source        = if (isXtream) "" else trimmedSource,
                                    username      = if (isXtream) username.trim() else "",
                                    password      = if (isXtream) password else "",
                                    serverUrl     = if (isXtream) serverUrl.trimEnd('/') else "",
                                    userAgent     = finalUa,
                                    customHeaders = customHeaders.trim(),
                                    isActive      = playlistToEdit?.isActive ?: true,
                                    addedAt       = playlistToEdit?.addedAt ?: System.currentTimeMillis()
                                ))
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { isSaveFocused = it.isFocused || it.hasFocus }
                            .border(
                                width = if (isSaveFocused) 2.5.dp else 0.dp,
                                color = if (isSaveFocused) Color.White else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            ),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSaveFocused) Color(0xFF3B82F6) else AccentBlue
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text(if (playlistToEdit != null) "Simpan" else "Tambah") }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SlimTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false,
    showPassword: Boolean = false,
    onTogglePassword: () -> Unit = {},
    minLines: Int = 1,
    maxLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    trailingIcon: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val inputFocusRequester = remember { FocusRequester() }
    var inputHadFocus by remember { mutableStateOf(false) }
    val rowFocusRequester = remember { FocusRequester() }
    var restoreRowFocus by remember { mutableStateOf(false) }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(restoreRowFocus) {
        if (restoreRowFocus) {
            delay(60)
            try { rowFocusRequester.requestFocus() } catch (_: Exception) {}
            restoreRowFocus = false
        }
    }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            inputHadFocus = false
            delay(50)
            try {
                inputFocusRequester.requestFocus()
                delay(100)
                keyboardController?.show()
                delay(150)
                bringIntoViewRequester.bringIntoView()
            } catch (_: Exception) {}
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .border(
                width = if (isFocused || isEditing) 2.5.dp else 1.dp,
                color = if (isFocused || isEditing) Color.White else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp)
            )
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFocused || isEditing) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
            .then(
                if (!isEditing) {
                    Modifier
                        .focusRequester(rowFocusRequester)
                        .onFocusChanged { isFocused = it.isFocused }
                        .focusable()
                        .clickable { isEditing = true }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                                    isEditing = true
                                    true
                                } else if (event.key == Key.DirectionDown) {
                                    focusManager.moveFocus(FocusDirection.Down)
                                    true
                                } else if (event.key == Key.DirectionUp) {
                                    focusManager.moveFocus(FocusDirection.Up)
                                    true
                                } else false
                            } else false
                        }
                } else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = if (maxLines > 1) Alignment.Top else Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (isEditing) {
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                    singleLine = maxLines == 1,
                    minLines = minLines,
                    maxLines = maxLines,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    visualTransformation = if (isPassword && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(AccentBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onFocusChanged {
                            if (it.isFocused || it.hasFocus) {
                                inputHadFocus = true
                            } else if (inputHadFocus) {
                                isEditing = false
                                keyboardController?.hide()
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                if (event.key == Key.Back) {
                                    isEditing = false
                                    keyboardController?.hide()
                                    restoreRowFocus = true
                                    true
                                } else if (event.key == Key.DirectionDown) {
                                    isEditing = false
                                    keyboardController?.hide()
                                    focusManager.moveFocus(FocusDirection.Down)
                                    true
                                } else if (event.key == Key.DirectionUp) {
                                    isEditing = false
                                    keyboardController?.hide()
                                    focusManager.moveFocus(FocusDirection.Up)
                                    true
                                } else false
                            } else false
                        },
                    decorationBox = { innerTextField ->
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                )
            } else {
                val displayText = if (value.isNotEmpty()) {
                    if (isPassword && !showPassword) "••••••••" else value
                } else {
                    placeholder
                }
                Text(
                    text = displayText,
                    color = if (value.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.4f),
                    fontSize = 14.sp,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (isPassword) {
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onTogglePassword, modifier = Modifier.size(20.dp)) {
                Icon(
                    if (showPassword) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, 
                    contentDescription = null, 
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else if (trailingIcon != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailingIcon()
        }
    }
}

@Composable
fun UaSelector(
    presets: List<String>,
    selectedPreset: String?,
    showCustom: Boolean,
    customText: String,
    accentColor: Color,
    onPresetSelected: (String?) -> Unit,
    onCustomSelected: () -> Unit,
    onDefaultSelected: () -> Unit,
    onCustomTextChange: (String) -> Unit,
    focusManager: androidx.compose.ui.focus.FocusManager
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Computer, contentDescription = null, tint = accentColor, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("User Agent", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            Text("(opsional)", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        val allChips = listOf("Default") + presets + listOf("Kustom")
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(allChips) { chip ->
                val isSel = when (chip) {
                    "Default" -> !showCustom && selectedPreset == null
                    "Kustom"  -> showCustom
                    else      -> !showCustom && selectedPreset == chip
                }
                var isChipFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .onFocusChanged { isChipFocused = it.isFocused || it.hasFocus }
                        .border(
                            width = if (isChipFocused) 2.5.dp else 1.dp, 
                            color = if (isChipFocused) Color.White else if (isSel) accentColor else Color.Transparent, 
                            shape = RoundedCornerShape(20.dp)
                        )
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            when (chip) {
                                "Default" -> onDefaultSelected()
                                "Kustom"  -> onCustomSelected()
                                else      -> onPresetSelected(chip)
                            }
                        }
                        .background(if (isChipFocused) Color.White.copy(alpha = 0.22f) else if (isSel) accentColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    val label = if (chip == "Default" || chip == "Kustom") chip else chip.substringBefore("/").substringBefore(" (").take(14)
                    Text(
                        label,
                        color = if (isSel) accentColor else Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp, fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
        if (showCustom) {
            Spacer(Modifier.height(10.dp))
            SlimTextField(
                value = customText, onValueChange = onCustomTextChange,
                placeholder = "Masukkan User Agent kustom...",
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focusManager.clearFocus() })
            )
        } else if (selectedPreset != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                selectedPreset,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
