@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.dinsplayer.app.features.beranda

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.dinsplayer.app.features.mobile.tvFocusRing
import com.dinsplayer.app.features.mobile.tvMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dinsplayer.app.core.player.Media3Manager
import com.dinsplayer.app.core.player.StreamHistoryManager
import com.dinsplayer.app.data.models.DrmSchemeOption
import com.dinsplayer.app.data.models.StreamTestForm
import com.dinsplayer.app.data.models.UserAgentOption
import com.dinsplayer.app.features.player.PlayerScreen
import com.dinsplayer.app.ui.theme.*

@Composable
fun BerandaScreen() {
    val context = LocalContext.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var form by remember { mutableStateOf(StreamTestForm()) }
    var showPlayer by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    val playFocusRequester = remember { FocusRequester() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    var shouldRestoreFocus by rememberSaveable { mutableStateOf(false) }

    // Pulihkan fokus remote D-pad otomatis ke tombol Putar Stream saat kembali dari PlayerActivity
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (shouldRestoreFocus) {
                    shouldRestoreFocus = false
                    coroutineScope.launch {
                        for (d in listOf(150L, 350L, 600L)) {
                            delay(d)
                            try {
                                playFocusRequester.requestFocus()
                                break
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Dikelola secara global di MobileAppScreen melalui Media3Manager.state

    // Opaque gradient removed; AppWallpaper in the shell shows through instead.
    Box(modifier = Modifier
        .fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            TopBar(onHistoryClick = { showHistory = true })

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(scrollState)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                AdBannerSlot()

                Spacer(modifier = Modifier.height(20.dp))

                SectionLabel("Uji Stream")
                Spacer(modifier = Modifier.height(10.dp))
                StreamFormContent(
                    form = form,
                    playFocusRequester = playFocusRequester,
                    onFormChange = { form = it },
                    onPaste = {
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = clip.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        if (text.isNotBlank()) {
                            form = form.copy(url = text.trim())
                            Toast.makeText(context, "URL ditempel", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Clipboard kosong", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onPlay = {
                        keyboardController?.hide()
                        if (form.url.isBlank()) {
                            Toast.makeText(context, "Masukkan URL stream", Toast.LENGTH_SHORT).show()
                        } else {
                            shouldRestoreFocus = true
                            val ua = if (form.userAgent == UserAgentOption.CUSTOM) form.customUserAgent else form.userAgent.value
                            
                            var finalDrmUri: String? = form.drmLicense.trim().takeIf { it.isNotBlank() }
                            // Null when no DRM; only set a scheme string when DRM is actually present
                            var detectedScheme: String? = when (form.drmScheme) {
                                DrmSchemeOption.AUTO -> null
                                else -> form.drmScheme.name
                            }

                            if (finalDrmUri != null && finalDrmUri.contains(":") && !finalDrmUri.startsWith("http")) {
                                try {
                                    val pairs = finalDrmUri.split(",").map { it.trim() }.filter { it.contains(":") }
                                    val zeroKid64 = android.util.Base64.encodeToString(
                                        hexStringToByteArray("00000000000000000000000000000000"),
                                        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                                    )
                                    val keysJsonList = mutableListOf<String>()
                                    for (pair in pairs) {
                                        val kid = pair.substringBefore(":").trim().trim('"').replace("-", "")
                                        val k = pair.substringAfter(":").trim().trim('"').replace("-", "")
                                        val kidBytes = hexStringToByteArray(kid)
                                        val kBytes = hexStringToByteArray(k)
                                        if (kidBytes.isNotEmpty() && kBytes.isNotEmpty()) {
                                            val kid64 = android.util.Base64.encodeToString(kidBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
                                            val k64 = android.util.Base64.encodeToString(kBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
                                            keysJsonList.add("""{"kty":"oct","k":"$k64","kid":"$kid64"}""")
                                            if (!kid.equals("00000000000000000000000000000000", ignoreCase = true)) {
                                                keysJsonList.add("""{"kty":"oct","k":"$k64","kid":"$zeroKid64"}""")
                                            }
                                        }
                                    }
                                    if (keysJsonList.isNotEmpty()) {
                                        val json = """{"keys":[${keysJsonList.joinToString(",")}],"type":"temporary"}"""
                                        finalDrmUri = "data:application/json,${java.net.URLEncoder.encode(json, "UTF-8")}"
                                    }
                                    if (form.drmScheme == DrmSchemeOption.AUTO) {
                                        detectedScheme = "CLEARKEY"
                                    }
                                } catch (e: Exception) {
                                    // ignore or log
                                }
                            } else if (form.drmScheme == DrmSchemeOption.AUTO && finalDrmUri != null) {
                                detectedScheme = if (form.url.lowercase().contains(".mpd")) "WIDEVINE" else "CLEARKEY"
                            }
                            
                            StreamHistoryManager.saveHistoryItem(context, form)

                            val autoManifestType = if (form.url.lowercase().contains(".mpd")) "mpd" else if (form.url.lowercase().contains(".m3u8")) "hls" else null

                            Media3Manager.play(
                                url = form.url.trim(), 
                                userAgent = ua, 
                                headers = buildHeaders(form),
                                drmScheme = detectedScheme,
                                drmLicenseUri = finalDrmUri,
                                manifestType = autoManifestType
                            )
                            Media3Manager.launchPlayer(context)
                            showPlayer = true
                        }
                    }
                )
            }
        }

        if (showHistory) {
            Dialog(
                onDismissRequest = { showHistory = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                HistoryScreen(
                    onDismiss = { showHistory = false },
                    onItemSelect = { selectedForm ->
                        form = selectedForm
                        showHistory = false
                    },
                    onItemPlay = { selectedForm ->
                        keyboardController?.hide()
                        form = selectedForm
                        showHistory = false
                        shouldRestoreFocus = true
                        
                        // Play immediately
                        val ua = if (selectedForm.userAgent == UserAgentOption.CUSTOM) selectedForm.customUserAgent else selectedForm.userAgent.value
                        var finalDrmUri: String? = selectedForm.drmLicense.trim().takeIf { it.isNotBlank() }
                        var detectedScheme: String? = when (selectedForm.drmScheme) {
                            DrmSchemeOption.AUTO -> null
                            else -> selectedForm.drmScheme.name
                        }

                        if (finalDrmUri != null && finalDrmUri.contains(":") && !finalDrmUri.startsWith("http")) {
                            try {
                                val pairs = finalDrmUri.split(",").map { it.trim() }.filter { it.contains(":") }
                                val zeroKid64 = android.util.Base64.encodeToString(
                                    hexStringToByteArray("00000000000000000000000000000000"),
                                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                                )
                                val keysJsonList = mutableListOf<String>()
                                for (pair in pairs) {
                                    val kid = pair.substringBefore(":").trim().trim('"').replace("-", "")
                                    val k = pair.substringAfter(":").trim().trim('"').replace("-", "")
                                    val kidBytes = hexStringToByteArray(kid)
                                    val kBytes = hexStringToByteArray(k)
                                    if (kidBytes.isNotEmpty() && kBytes.isNotEmpty()) {
                                        val kid64 = android.util.Base64.encodeToString(kidBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
                                        val k64 = android.util.Base64.encodeToString(kBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
                                        keysJsonList.add("""{"kty":"oct","k":"$k64","kid":"$kid64"}""")
                                        if (!kid.equals("00000000000000000000000000000000", ignoreCase = true)) {
                                            keysJsonList.add("""{"kty":"oct","k":"$k64","kid":"$zeroKid64"}""")
                                        }
                                    }
                                }
                                if (keysJsonList.isNotEmpty()) {
                                    val json = """{"keys":[${keysJsonList.joinToString(",")}],"type":"temporary"}"""
                                    finalDrmUri = "data:application/json,${java.net.URLEncoder.encode(json, "UTF-8")}"
                                }
                                if (selectedForm.drmScheme == DrmSchemeOption.AUTO) {
                                    detectedScheme = "CLEARKEY"
                                }
                            } catch (e: Exception) {}
                        } else if (selectedForm.drmScheme == DrmSchemeOption.AUTO && finalDrmUri != null) {
                            detectedScheme = if (selectedForm.url.lowercase().contains(".mpd")) "WIDEVINE" else "CLEARKEY"
                        }
                        
                        StreamHistoryManager.saveHistoryItem(context, selectedForm)
                        val autoManifestType = if (selectedForm.url.lowercase().contains(".mpd")) "mpd" else if (selectedForm.url.lowercase().contains(".m3u8")) "hls" else null
                        Media3Manager.play(
                            url = selectedForm.url.trim(), 
                            userAgent = ua, 
                            headers = buildHeaders(selectedForm),
                            drmScheme = detectedScheme,
                            drmLicenseUri = finalDrmUri,
                            manifestType = autoManifestType
                        )
                        Media3Manager.launchPlayer(context)
                    }
                )
            }
        }
    }
}


@Composable
private fun TopBar(onHistoryClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "DINS Player",
            style = TextStyle(
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onHistoryClick,
                modifier = Modifier
                    .size(40.dp)
                    .tvFocusRing(CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = "Riwayat",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(
                onClick = { /* profil – belum diimplementasi */ },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.AccountCircle,
                    contentDescription = "Profil",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

// AdBannerSlot: dihapus dari tampilan saat belum ada ad network aktif
@Composable
private fun AdBannerSlot() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Slot Iklan",
            style = TextStyle(color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp),
        style = TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
    )
}

@Composable
private fun StreamFormContent(
    form: StreamTestForm,
    playFocusRequester: FocusRequester,
    onFormChange: (StreamTestForm) -> Unit,
    onPaste: () -> Unit,
    onPlay: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PillTextField(
            value = form.url,
            onValueChange = { onFormChange(form.copy(url = it)) },
            placeholder = "Tautan Stream",
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onPaste,
                        modifier = Modifier.tvFocusRing(CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentPaste,
                            contentDescription = "Tempel",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (form.url.isNotBlank()) {
                        IconButton(
                            onClick = onPlay,
                            modifier = Modifier.tvFocusRing(CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Putar",
                                tint = ElectricBlue,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        )

        // Opsi Lanjutan — sembunyikan field jarang dipakai agar tampilan bersih
        var advancedExpanded by remember { mutableStateOf(false) }
        var isAdvancedFocused by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = if (isAdvancedFocused) 0.16f else 0.08f))
                .border(
                    width = if (isAdvancedFocused) 2.dp else 1.dp,
                    color = if (isAdvancedFocused) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp)
                )
                .tvFocusRing(RoundedCornerShape(12.dp))
                .onFocusChanged { isAdvancedFocused = it.isFocused || it.hasFocus }
                .clickable { advancedExpanded = !advancedExpanded }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "Opsi Lanjutan",
                    style = TextStyle(color = TextSecondary, fontSize = 14.sp)
                )
            }
            Icon(
                imageVector = if (advancedExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        AnimatedVisibility(
            visible = advancedExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.height(2.dp))
                PillTextField(
                    value = form.xForwardedFor,
                    onValueChange = { onFormChange(form.copy(xForwardedFor = it)) },
                    placeholder = "X-Forwarded-For"
                )
                PillTextField(
                    value = form.referer,
                    onValueChange = { onFormChange(form.copy(referer = it)) },
                    placeholder = "Referer"
                )
                PillTextField(
                    value = form.origin,
                    onValueChange = { onFormChange(form.copy(origin = it)) },
                    placeholder = "Origin"
                )
                PillTextField(
                    value = form.drmLicense,
                    onValueChange = { onFormChange(form.copy(drmLicense = it)) },
                    placeholder = "URL Lisensi DRM / kid:key"
                )
                PillCustomHeadersAccordion(
                    value = form.customHeaders,
                    onValueChange = { onFormChange(form.copy(customHeaders = it)) },
                    expanded = form.showCustomHeaders,
                    onToggle = { onFormChange(form.copy(showCustomHeaders = !form.showCustomHeaders)) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PillDropdown(
                        modifier = Modifier.weight(1f),
                        label = "UserAgent",
                        options = UserAgentOption.entries.map { it.label },
                        selectedIndex = form.userAgent.ordinal,
                        onSelected = { onFormChange(form.copy(userAgent = UserAgentOption.entries[it])) }
                    )
                    PillDropdown(
                        modifier = Modifier.weight(1f),
                        label = "DRM Scheme",
                        options = DrmSchemeOption.entries.map { it.label },
                        selectedIndex = form.drmScheme.ordinal,
                        onSelected = { onFormChange(form.copy(drmScheme = DrmSchemeOption.entries[it])) }
                    )
                }
                if (form.userAgent == UserAgentOption.CUSTOM) {
                    PillTextField(
                        value = form.customUserAgent,
                        onValueChange = { onFormChange(form.copy(customUserAgent = it)) },
                        placeholder = "Custom User-Agent"
                    )
                }
            }
        }

        var isPlayFocused by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .focusRequester(playFocusRequester)
                .onFocusChanged { isPlayFocused = it.isFocused || it.hasFocus }
                .border(
                    width = if (isPlayFocused) 3.5.dp else 0.dp,
                    color = if (isPlayFocused) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(14.dp)
                )
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (isPlayFocused) {
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            colors = listOf(Color(0xFF2196F3), Color(0xFF90CAF9))
                        )
                    } else {
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            colors = listOf(Color(0xFF1565C0), Color(0xFF448AFF))
                        )
                    }
                )
                .clickable { onPlay() },
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    "Putar Stream",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}


@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputFocusRequester = remember { FocusRequester() }
    val rowFocusRequester = remember { FocusRequester() }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    var inputHadFocus by remember { mutableStateOf(false) }
    var restoreRowFocus by remember { mutableStateOf(false) }

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
                // Scroll field ke atas keyboard
                delay(150)
                bringIntoViewRequester.bringIntoView()
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .bringIntoViewRequester(bringIntoViewRequester)
            .border(
                width = if (isFocused || isEditing) 3.dp else 1.dp,
                color = if (isFocused || isEditing) Color.White else Color.White.copy(alpha = 0.20f),
                shape = RoundedCornerShape(14.dp)
            )
            .clip(RoundedCornerShape(14.dp))
            .background(if (isFocused || isEditing) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.12f))
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
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (isEditing) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 14.sp
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(ElectricBlue),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = {
                        isEditing = false
                        keyboardController?.hide()
                        restoreRowFocus = true
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = if (trailingIcon != null) 40.dp else 0.dp)
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
                decorationBox = @Composable { innerTextField ->
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = TextStyle(
                                color = TextMuted,
                                fontSize = 14.sp
                            )
                        )
                    }
                    innerTextField()
                }
            )
        } else {
            Text(
                text = if (value.isNotEmpty()) value else placeholder,
                style = TextStyle(
                    color = if (value.isNotEmpty()) Color.White else TextMuted,
                    fontSize = 14.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = if (trailingIcon != null) 40.dp else 0.dp)
            )
        }

        if (trailingIcon != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
            ) {
                trailingIcon()
            }
        }
    }
}


@Composable
private fun PillCustomHeadersAccordion(
    value: String,
    onValueChange: (String) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(14.dp))
                .tvFocusRing(RoundedCornerShape(14.dp))
                .clickable { onToggle() }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Custom Headers",
                style = TextStyle(color = TextSecondary, fontSize = 14.sp)
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = TextSecondary
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(96.dp),
                placeholder = {
                    Text(
                        text = "User-Agent: Custom\nAuthorization: Bearer token",
                        style = TextStyle(color = TextMuted, fontSize = 12.sp)
                    )
                },
                textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricBlue,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.20f),
                    cursorColor = ElectricBlue,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    unfocusedContainerColor = Color.White.copy(alpha = 0.10f),
                    focusedContainerColor = Color.White.copy(alpha = 0.14f),
                ),
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}


@Composable
private fun PillDropdown(
    modifier: Modifier = Modifier,
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = options.getOrElse(selectedIndex) { label },
            onValueChange = {},
            readOnly = true,
            label = {
                Text(
                    text = label,
                    style = TextStyle(color = TextMuted, fontSize = 11.sp)
                )
            },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ElectricBlue,
                unfocusedBorderColor = Color.White.copy(alpha = 0.20f),
                focusedLabelColor = ElectricBlue,
                unfocusedLabelColor = Color.White.copy(alpha = 0.45f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                unfocusedContainerColor = Color.White.copy(alpha = 0.10f),
                focusedContainerColor = Color.White.copy(alpha = 0.14f),
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .height(54.dp)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF112038))
        ) {
            options.forEachIndexed { i, opt ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = opt,
                            color = if (i == selectedIndex) ElectricBlue else Color.White
                        )
                    },
                    onClick = {
                        onSelected(i)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}


private fun buildHeaders(form: StreamTestForm): Map<String, String> = buildMap {
    if (form.xForwardedFor.isNotBlank()) put("X-Forwarded-For", form.xForwardedFor.trim())
    if (form.referer.isNotBlank())       put("Referer", form.referer.trim())
    if (form.origin.isNotBlank())        put("Origin", form.origin.trim())
    form.customHeaders.lines()
        .filter { it.contains(":") }
        .forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) put(parts[0].trim(), parts[1].trim())
        }
}

private fun hexStringToByteArray(s: String): ByteArray {
    val clean = s.replace("-", "").trim()
    val len = clean.length
    if (len % 2 != 0) return ByteArray(0)
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len - 1) {
        val high = Character.digit(clean[i], 16)
        val low = Character.digit(clean[i + 1], 16)
        if (high == -1 || low == -1) return ByteArray(0)
        data[i / 2] = ((high shl 4) + low).toByte()
        i += 2
    }
    return data
}

@Composable
fun HistoryScreen(
    onDismiss: () -> Unit,
    onItemSelect: (StreamTestForm) -> Unit,
    onItemPlay: (StreamTestForm) -> Unit
) {
    val context = LocalContext.current
    var history by remember { mutableStateOf(StreamHistoryManager.getHistory(context)) }
    var itemToDelete by remember { mutableStateOf<StreamTestForm?>(null) }

    if (itemToDelete != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Konfirmasi Hapus") },
            text = { Text("Apakah Anda yakin ingin menghapus riwayat ini?") },
            confirmButton = {
                TextButton(onClick = {
                    itemToDelete?.let { 
                        StreamHistoryManager.removeHistoryItem(context, it)
                        history = StreamHistoryManager.getHistory(context)
                    }
                    itemToDelete = null
                }) {
                    Text("Hapus", color = ColorError)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Batal")
                }
            },
            containerColor = BgElevated,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.tvFocusRing(CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Riwayat Uji Stream",
                    style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold)
                )
            }

            // List
            if (history.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.History, contentDescription = null, tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Belum ada riwayat", style = MaterialTheme.typography.bodyMedium.copy(color = Color.White.copy(alpha = 0.45f)))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(history) { form ->
                        HistoryItemView(
                            form = form,
                            onPlay = { onItemPlay(form) },
                            onEdit = { onItemSelect(form) },
                            onDelete = {
                                itemToDelete = form
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryItemView(
    form: StreamTestForm,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    androidx.compose.material3.Surface(
        color = Color.White.copy(alpha = 0.10f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
            .tvFocusRing(RoundedCornerShape(12.dp))
            .clickable { onPlay() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = form.url,
                style = MaterialTheme.typography.bodyMedium.copy(color = Color.White, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                modifier = Modifier.tvMarquee()
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            val infoList = mutableListOf<String>()
            if (form.drmScheme != DrmSchemeOption.AUTO) {
                infoList.add("DRM: ${form.drmScheme.label}")
            }
            if (form.userAgent != UserAgentOption.DEFAULT) {
                infoList.add("UA: ${form.userAgent.label}")
            }
            if (form.referer.isNotBlank()) infoList.add("Referer")
            if (form.customHeaders.isNotBlank()) infoList.add("Custom Headers")
            
            if (infoList.isNotEmpty()) {
                Text(
                    text = infoList.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall.copy(color = ElectricBlue)
                )
                Spacer(modifier = Modifier.height(12.dp))
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp))
                ) {
                    Text("Hapus", color = Color.Red.copy(alpha = 0.8f))
                }
                TextButton(
                    onClick = onEdit,
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp))
                ) {
                    Text("Edit", color = Color.White.copy(alpha = 0.7f))
                }
                Button(
                    onClick = onPlay,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.tvFocusRing(shape = RoundedCornerShape(8.dp), color = Color.White, width = 2.5.dp)
                ) {
                    Text("Play", color = Color.White)
                }
            }
        }
    }
}
