package com.dinsplayer.app.features.setelan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.dinsplayer.app.features.mobile.WallpaperMode
import com.dinsplayer.app.features.mobile.tvFocusRing
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type


import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.datastore.preferences.core.edit
import com.dinsplayer.app.core.epg.EpgManager
import com.dinsplayer.app.core.player.Media3Manager
import com.dinsplayer.app.data.KEY_AUDIO_SYNC
import com.dinsplayer.app.data.KEY_BUFFER_SIZE
import com.dinsplayer.app.data.KEY_EPG_URLS
import com.dinsplayer.app.data.KEY_HW_DECODING
import com.dinsplayer.app.data.KEY_WALLPAPER_CUSTOM_REV
import com.dinsplayer.app.data.KEY_WALLPAPER_CUSTOM_URI
import com.dinsplayer.app.data.KEY_WALLPAPER_MODE
import com.dinsplayer.app.data.PlayerSettings
import com.dinsplayer.app.data.settingsDataStore
import com.dinsplayer.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SetelanScreen(
    firstFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ds = context.settingsDataStore

    val localFirstFocusRequester = remember { FocusRequester() }
    val effectiveFirstFocusRequester = firstFocusRequester ?: localFirstFocusRequester


    val prefs by ds.data.collectAsState(initial = null)
    val hwDecoding = prefs?.get(KEY_HW_DECODING) ?: true
    val bufferSize = prefs?.get(KEY_BUFFER_SIZE) ?: 1
    val audioSync = prefs?.get(KEY_AUDIO_SYNC) ?: 0f
    val wallpaperMode = WallpaperMode.fromKey(prefs?.get(KEY_WALLPAPER_MODE))
    val customWallpaper = prefs?.get(KEY_WALLPAPER_CUSTOM_URI)

    val pickWallpaper = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Without a persisted grant the URI dies with the process and the wallpaper silently
        // reverts to the bundled set on the next launch. Not every provider that answers
        // OpenDocument supports persistence, so a refusal must not lose the pick.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        scope.launch {
            ds.edit {
                it[KEY_WALLPAPER_CUSTOM_URI] = uri.toString()
                it[KEY_WALLPAPER_MODE] = WallpaperMode.CUSTOM.key
                // Re-picking the same URI leaves the string unchanged, so the wallpaper's
                // failure flag would never reset. The counter forces the retry.
                it[KEY_WALLPAPER_CUSTOM_REV] = (it[KEY_WALLPAPER_CUSTOM_REV] ?: 0) + 1
            }
        }
    }

    // Android TV images often ship without a document picker; launch() then throws
    // ActivityNotFoundException and takes the whole app down.
    val launchWallpaperPicker: () -> Unit = {
        try {
            pickWallpaper.launch(arrayOf("image/*"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                context,
                "Perangkat ini tidak punya pemilih foto",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    val bufferLabels = listOf("16 detik", "32 detik", "64 detik")
    var showEpgDialog by remember { mutableStateOf(false) }
    var showTosDialog by remember { mutableStateOf(false) }

    val appVersion = remember(context) {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "1.0.0"
        } catch (_: Exception) { "1.0.0" }
    }

    val epgUrlsPref = prefs?.get(KEY_EPG_URLS)
    val epgUrls = remember(epgUrlsPref) {
        epgUrlsPref?.toList() ?: EpgManager.defaultUrls
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // App Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Setelan",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 22.sp
                    )
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Preferensi pemutar dan konfigurasi aplikasi",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 13.sp)
                )
            }
        }

        // Settings Content List
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Group 1: Pemutaran & Video
            SettingsGroup(title = "Pemutaran & Video") {
                SettingSwitchRow(
                    icon = Icons.Filled.Memory,
                    title = "Akselerasi Perangkat Keras",
                    subtitle = if (hwDecoding)
                        "Decoder GPU. Matikan jika video rusak/pecah di perangkat tertentu"
                    else
                        "Decoder software aktif. Berlaku setelah aplikasi dimulai ulang",
                    checked = hwDecoding,
                    onCheckedChange = { v ->
                        PlayerSettings.updateHwDecode(v)
                        scope.launch { ds.edit { it[KEY_HW_DECODING] = v } }
                    },
                    modifier = Modifier.focusRequester(effectiveFirstFocusRequester)
                )

                HorizontalDivider(
                    color = DividerColor,
                    thickness = 0.8.dp,
                    modifier = Modifier.padding(start = 68.dp, end = 16.dp)
                )

                SettingSliderRow(
                    icon = Icons.Filled.Speed,
                    title = "Buffer Streaming",
                    subtitle = "Durasi pra-unduh video. Berlaku setelah aplikasi dimulai ulang",
                    valueText = bufferLabels[bufferSize.coerceIn(0, 2)],
                    sliderValue = bufferSize.toFloat(),
                    valueRange = 0f..2f,
                    steps = 1,
                    stepDelta = 1f,
                    onValueChange = { v ->
                        val intVal = v.toInt().coerceIn(0, 2)
                        PlayerSettings.updateBuffer(intVal)
                        scope.launch { ds.edit { it[KEY_BUFFER_SIZE] = intVal } }
                    }
                )

                HorizontalDivider(
                    color = DividerColor,
                    thickness = 0.8.dp,
                    modifier = Modifier.padding(start = 68.dp, end = 16.dp)
                )

                SettingSliderRow(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    title = "Sinkronisasi Audio",
                    subtitle = "Sesuaikan keterlambatan audio terhadap video",
                    valueText = if (audioSync == 0f) "Normal" else "${audioSync.toInt()} ms",
                    sliderValue = audioSync,
                    valueRange = -500f..500f,
                    steps = 0,
                    stepDelta = 25f,
                    showReset = audioSync != 0f,
                    onReset = {
                        scope.launch { ds.edit { it[KEY_AUDIO_SYNC] = 0f } }
                        Media3Manager.setAudioSyncMs(0L)
                    },
                    onValueChange = { v ->
                        val snapped = kotlin.math.round(v / 25f) * 25f
                        scope.launch { ds.edit { it[KEY_AUDIO_SYNC] = snapped } }
                        Media3Manager.setAudioSyncMs(snapped.toLong())
                    }
                )
            }

            // Group 2: Tampilan
            SettingsGroup(title = "Tampilan") {
                WallpaperMode.entries.forEachIndexed { index, mode ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = DividerColor,
                            thickness = 0.8.dp,
                            modifier = Modifier.padding(start = 68.dp, end = 16.dp)
                        )
                    }
                    SettingChoiceRow(
                        icon = when (mode) {
                            WallpaperMode.DEFAULT -> Icons.Filled.Wallpaper
                            WallpaperMode.CUSTOM -> Icons.Filled.Image
                            WallpaperMode.OFF -> Icons.Filled.Block
                        },
                        title = mode.label,
                        subtitle = mode.hint,
                        selected = wallpaperMode == mode,
                        onClick = {
                            if (mode == WallpaperMode.CUSTOM && customWallpaper == null) {
                                launchWallpaperPicker()
                            } else {
                                scope.launch { ds.edit { it[KEY_WALLPAPER_MODE] = mode.key } }
                            }
                        }
                    )
                }

                if (wallpaperMode == WallpaperMode.CUSTOM) {
                    HorizontalDivider(
                        color = DividerColor,
                        thickness = 0.8.dp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp)
                    )
                    SettingClickableRow(
                        icon = Icons.Filled.PhotoLibrary,
                        title = "Ganti Foto Latar",
                        subtitle = if (customWallpaper == null) "Belum ada foto dipilih"
                        else "Foto tersimpan di perangkat Anda",
                        trailingText = if (customWallpaper == null) "Pilih" else "Ganti",
                        onClick = { launchWallpaperPicker() }
                    )
                }
            }

            // Group 3: Panduan EPG
            // The "Proteksi Anti-Sniffing (MITM)" switch was removed: it wrote a preference
            // nothing ever read, and PlayerState.SecurityError was never emitted anywhere.
            SettingsGroup(title = "Panduan EPG") {
                SettingClickableRow(
                    icon = Icons.Filled.Tv,
                    title = "Sumber EPG (XMLTV)",
                    subtitle = "${epgUrls.size} tautan EPG aktif",
                    trailingText = "Atur",
                    onClick = { showEpgDialog = true }
                )
            }

            // Group 4: Informasi Aplikasi
            SettingsGroup(title = "Tentang Aplikasi") {
                // Header Profil Aplikasi
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AccentBlue.copy(alpha = 0.12f))
                            .border(1.dp, AccentBlue.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(com.dinsplayer.app.R.drawable.ic_dins_logo),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "DINS Player",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Pemutar Media & IPTV Modern",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = ColorSuccess.copy(alpha = 0.14f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ColorSuccess.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = "v$appVersion",                            style = MaterialTheme.typography.labelSmall.copy(
                                color = ColorSuccess,
                                fontWeight = FontWeight.SemiBold
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                HorizontalDivider(
                    color = DividerColor,
                    thickness = 0.8.dp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp)
                )

                SettingClickableRow(
                    icon = Icons.Filled.Policy,
                    title = "Ketentuan Layanan & Hak Cipta",
                    subtitle = "DINS Player murni pemutar media tanpa konten bawaan",
                    trailingText = "Buka",
                    onClick = { showTosDialog = true }
                )

                HorizontalDivider(
                    color = DividerColor,
                    thickness = 0.8.dp,
                    modifier = Modifier.padding(start = 68.dp, end = 16.dp)
                )

                // Inline Disclaimer Card
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AccentBlue.copy(alpha = 0.08f))
                        .border(1.dp, AccentBlue.copy(alpha = 0.20f), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(18.dp).padding(top = 2.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Penafian Konten & Hak Cipta",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "DINS Player adalah aplikasi pemutar media mandiri dan TIDAK menyediakan, menyiarkan, atau menyertakan konten media atau playlist apa pun. Seluruh konten berasal dari input mandiri pengguna.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        )
                    }
                }
            }
        }
    }

    if (showEpgDialog) {
        EpgManagementDialog(
            urls = epgUrls,
            onDismiss = { showEpgDialog = false },
            onSave = { newUrls ->
                scope.launch {
                    ds.edit { it[KEY_EPG_URLS] = newUrls.toSet() }
                    EpgManager.syncEpgs(newUrls.toSet())
                }
                showEpgDialog = false
            }
        )
    }

    if (showTosDialog) {
        TermsOfServiceDialog(
            onDismiss = { showTosDialog = false }
        )
    }
}

// ── Components ──────────────────────────────────────────────────────────────

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary,
                fontSize = 13.sp
            ),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgElevated)
                .border(1.dp, DividerColor, RoundedCornerShape(16.dp))
        ) {
            content()
        }
    }
}

@Composable
private fun SettingIcon(icon: ImageVector, tint: Color = TextSecondary) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(20.dp)
    )
}

@Composable
private fun SettingChoiceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
            .tvFocusRing(RoundedCornerShape(10.dp))
            .background(if (isFocused) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingIcon(icon = icon, tint = if (selected || isFocused) AccentBlue else TextSecondary)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            )
        }
        Spacer(Modifier.width(6.dp))
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            RadioButton(
                selected = selected,
                onClick = null,
                modifier = Modifier
                    .scale(0.85f)
                    .focusProperties { canFocus = false },
                colors = RadioButtonDefaults.colors(
                    selectedColor = AccentBlue,
                    unselectedColor = TextMuted,
                )
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
            .tvFocusRing(RoundedCornerShape(10.dp))
            .background(if (isFocused) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingIcon(icon = icon)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            )
        }
        Spacer(Modifier.width(6.dp))
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Switch(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier
                    .scale(0.8f)
                    .focusProperties { canFocus = false },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AccentBlue,
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = BgSurface,
                    uncheckedBorderColor = DividerColor,
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingSliderRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    valueText: String,
    sliderValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    stepDelta: Float = 1f,
    showReset: Boolean = false,
    onReset: (() -> Unit)? = null,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
            .tvFocusRing(RoundedCornerShape(10.dp))
            .background(if (isFocused) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.DirectionLeft -> {
                            if (sliderValue > valueRange.start) {
                                val nextVal = (sliderValue - stepDelta).coerceIn(valueRange.start, valueRange.endInclusive)
                                onValueChange(nextVal)
                                true
                            } else {
                                false
                            }
                        }
                        Key.DirectionRight -> {
                            if (sliderValue < valueRange.endInclusive) {
                                val nextVal = (sliderValue + stepDelta).coerceIn(valueRange.start, valueRange.endInclusive)
                                onValueChange(nextVal)
                                true
                            } else {
                                true
                            }
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            if (showReset && onReset != null) {
                                onReset()
                            } else {
                                val nextVal = if (sliderValue >= valueRange.endInclusive) {
                                    valueRange.start
                                } else {
                                    (sliderValue + stepDelta).coerceIn(valueRange.start, valueRange.endInclusive)
                                }
                                onValueChange(nextVal)
                            }
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingIcon(icon = icon)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showReset && onReset != null) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = if (isFocused) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.08f),
                        modifier = Modifier
                            .clickable { onReset() }
                            .padding(end = 6.dp)
                    ) {
                        Text(
                            text = "Reset",
                            color = if (isFocused) Color.White else TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isFocused) AccentBlue else AccentBlue.copy(alpha = 0.15f),
                    border = if (isFocused) androidx.compose.foundation.BorderStroke(1.5.dp, Color.White) else null
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        if (isFocused && sliderValue > valueRange.start) {
                            Text(
                                text = "◀ ",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = valueText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (isFocused) Color.White else AccentBlue,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            )
                        )
                        if (isFocused && sliderValue < valueRange.endInclusive) {
                            Text(
                                text = " ▶",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(2.dp))

        Slider(
            value = sliderValue,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            thumb = {
                Box(
                    modifier = Modifier
                        .size(if (isFocused) 18.dp else 14.dp)
                        .clip(CircleShape)
                        .background(if (isFocused) Color.White else AccentBlue)
                        .border(2.dp, AccentBlue, CircleShape)
                )
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    colors = SliderDefaults.colors(
                        activeTrackColor = if (isFocused) Color.White else AccentBlue,
                        inactiveTrackColor = DividerColor,
                    ),
                    modifier = Modifier.height(if (isFocused) 4.dp else 3.dp)
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 34.dp)
                .focusProperties { canFocus = false }
        )
    }
}

@Composable
private fun SettingClickableRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailingText: String = "",
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
            .tvFocusRing(RoundedCornerShape(10.dp))
            .background(if (isFocused) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingIcon(icon = icon)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            )
        }
        if (trailingText.isNotBlank()) {
            Text(
                text = trailingText,
                color = AccentBlue,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(16.dp)
        )
    }
}


@Composable
private fun SettingInfoRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingIcon(icon = icon)
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = TextPrimary,
                fontSize = 13.sp
            ),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                color = TextSecondary,
                fontSize = 12.sp
            )
        )
    }
}

// ── EPG Dialog ──────────────────────────────────────────────────────────────

@Composable
private fun EpgManagementDialog(
    urls: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    var editableUrls by remember { mutableStateOf(urls) }
    var newUrl by remember { mutableStateOf("") }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .widthIn(max = 480.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(BgElevated)
                    .border(1.dp, DividerColor, RoundedCornerShape(18.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { focusManager.clearFocus() }
                    )
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AccentBlue.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Tv,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Sumber EPG (XMLTV)",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        )
                        Text(
                            text = "Kelola daftar URL panduan jadwal siaran",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 12.sp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (editableUrls.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Belum ada URL EPG yang tersimpan",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                            )
                        }
                    } else {
                        editableUrls.forEachIndexed { index, url ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(BgSurface)
                                    .border(0.8.dp, DividerColor, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = url,
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        editableUrls = editableUrls.toMutableList().apply { removeAt(index) }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Hapus",
                                        tint = ColorError.copy(alpha = 0.85f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        placeholder = { Text("https://example.com/epg.xml.gz", color = TextMuted, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = TextPrimary),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = DividerColor,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = AccentBlue,
                            focusedContainerColor = BgSurface,
                            unfocusedContainerColor = BgSurface,
                        )
                    )

                    Button(
                        onClick = {
                            val trimmed = newUrl.trim()
                            if (trimmed.isNotBlank() && !editableUrls.contains(trimmed)) {
                                editableUrls = editableUrls + trimmed
                                newUrl = ""
                                focusManager.clearFocus()
                            }
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .tvFocusRing(RoundedCornerShape(10.dp)),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Tambah", modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .tvFocusRing(RoundedCornerShape(10.dp)),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DividerColor),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                    ) {
                        Text("Batal")
                    }

                    Button(
                        onClick = { onSave(editableUrls) },
                        modifier = Modifier
                            .weight(1f)
                            .tvFocusRing(RoundedCornerShape(10.dp)),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) {
                        Text("Simpan", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun TermsOfServiceDialog(
    onDismiss: () -> Unit
) {
    val closeFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        for (delayMs in listOf(50L, 150L, 300L)) {
            kotlinx.coroutines.delay(delayMs)
            try {
                closeFocusRequester.requestFocus()
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
                .background(Color.Black.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(0.92f)
                    .padding(16.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = BgElevated),
                border = androidx.compose.foundation.BorderStroke(1.dp, DividerColor)
            ) {
                Column(
                    modifier = Modifier.padding(22.dp)
                ) {
                    // Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AccentBlue.copy(alpha = 0.15f))
                                .border(1.dp, AccentBlue.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Policy,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ketentuan Layanan & Hak Cipta",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            )
                            Text(
                                text = "Terms of Service & Content Disclaimer",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    HorizontalDivider(color = DividerColor, thickness = 0.8.dp)

                    Spacer(Modifier.height(14.dp))

                    // Scrollable Terms Content
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        TosSection(
                            title = "1. Pemutar Media Mandiri (Media Player Tool)",
                            content = "DINS Player adalah aplikasi pemutar media murni. Aplikasi ini TIDAK menyediakan, mengunggah, menjual, atau menyertakan media, video, saluran siaran, atau playlist IPTV bawaan apa pun."
                        )

                        TosSection(
                            title = "2. Sumber dan Tanggung Jawab Konten Pengguna",
                            content = "Seluruh konten yang diputar berasal dari input mandiri pengguna (berkas lokal, URL M3U, atau akun Xtream). Pengguna bertanggung jawab penuh atas keabsahan dan hak cipta materi yang dimuat ke dalam aplikasi."
                        )

                        TosSection(
                            title = "3. Ketiadaan Afiliasi Pihak Ketiga",
                            content = "DINS Player tidak berafiliasi dengan penyedia konten, operator TV, atau layanan pihak ketiga mana pun. Kami tidak bertanggung jawab atas ketersediaan, kualitas, atau isi streaming yang disediakan oleh pihak luar."
                        )

                        TosSection(
                            title = "4. Kepatuhan Hak Cipta & DMCA",
                            content = "Aplikasi ini sepenuhnya menghormati hak kekayaan intelektual dan tidak mendukung atau memfasilitasi penayangan konten yang dilindungi hak cipta tanpa izin sah dari pemilik hak cipta."
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(closeFocusRequester)
                            .tvFocusRing(RoundedCornerShape(10.dp)),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) {
                        Text("Saya Mengerti & Tutup", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TosSection(
    title: String,
    content: String
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = AccentBlue,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall.copy(
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        )
    }
}

