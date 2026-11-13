package com.dinsplayer.app.features.playlist

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dinsplayer.app.core.network.FallbackDns
import com.dinsplayer.app.data.models.Playlist
import com.dinsplayer.app.data.models.PlaylistType
import com.dinsplayer.app.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Compact dialog shown when the '+' button is pressed in PlaylistScreen,
 * letting the user choose between Manual input and Local Web Server (QR Code).
 */
@Composable
fun AddPlaylistChoiceDialog(
    onDismiss: () -> Unit,
    onSelectManual: () -> Unit,
    onSelectWebServer: () -> Unit,
) {
    val manualFocusRequester = remember { FocusRequester() }
    val webFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        for (delayMs in listOf(40L, 120L, 250L)) {
            delay(delayMs)
            if (runCatching { manualFocusRequester.requestFocus() }.isSuccess) break
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .pointerInput(Unit) {
                    detectTapGestures { onDismiss() }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .fillMaxWidth(0.86f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(BgDialog)
                    .border(1.dp, DividerColor, RoundedCornerShape(18.dp))
                    .pointerInput(Unit) {
                        detectTapGestures { /* consume tap */ }
                    }
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Tambah Playlist",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                )
                Text(
                    text = "Pilih metode pengisian playlist",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                )

                var isManualFocused by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(manualFocusRequester)
                        .onFocusChanged { isManualFocused = it.isFocused || it.hasFocus }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                runCatching { webFocusRequester.requestFocus() }
                                return@onPreviewKeyEvent true
                            }
                            false
                        }
                        .border(
                            width = if (isManualFocused) 2.5.dp else 1.dp,
                            color = if (isManualFocused) Color.White else DividerColor,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isManualFocused) AccentBlue.copy(alpha = 0.22f) else BgElevated)
                        .clickable { onSelectManual() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AccentBlue.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.EditNote,
                            contentDescription = null,
                            tint = if (isManualFocused) Color.White else AccentBlue,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Manual",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Isi form langsung di aplikasi",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                var isWebFocused by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(webFocusRequester)
                        .onFocusChanged { isWebFocused = it.isFocused || it.hasFocus }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                                runCatching { manualFocusRequester.requestFocus() }
                                return@onPreviewKeyEvent true
                            }
                            false
                        }
                        .border(
                            width = if (isWebFocused) 2.5.dp else 1.dp,
                            color = if (isWebFocused) Color.White else DividerColor,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isWebFocused) AccentBlue.copy(alpha = 0.22f) else BgElevated)
                        .clickable { onSelectWebServer() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AccentBlue.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QrCode2,
                            contentDescription = null,
                            tint = if (isWebFocused) Color.White else AccentBlue,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Web Server",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Scan QR & isi lewat browser HP",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Popup dialog that starts the embedded HTTP server, renders a scannable QR code
 * and the local URL, and saves incoming playlists submitted from the phone browser.
 */
@Composable
fun WebServerPlaylistDialog(
    context: Context,
    existingPlaylists: List<Playlist>,
    onDismiss: () -> Unit,
    onAddPlaylist: (Playlist) -> Unit,
) {
    val closeFocusRequester = remember { FocusRequester() }
    var serverUrl by remember { mutableStateOf("") }
    var serverError by remember { mutableStateOf<String?>(null) }
    var lastAddedName by remember { mutableStateOf<String?>(null) }
    var addedCount by remember { mutableIntStateOf(0) }

    val currentPlaylists by rememberUpdatedState(existingPlaylists)
    val currentOnAdd by rememberUpdatedState(onAddPlaylist)

    DisposableEffect(Unit) {
        val server = LocalPlaylistHttpServer(
            context = context.applicationContext,
            getExistingPlaylists = { currentPlaylists },
            onPlaylistSubmitted = { playlist ->
                currentOnAdd(playlist)
                lastAddedName = playlist.name
                addedCount += 1
            }
        )
        runCatching {
            server.start()
            serverUrl = server.getServerUrl()
        }.onFailure { err ->
            serverError = err.message ?: "Gagal memulai web server lokal"
        }

        onDispose {
            server.stop()
        }
    }

    LaunchedEffect(Unit) {
        for (delayMs in listOf(50L, 150L, 300L)) {
            delay(delayMs)
            if (runCatching { closeFocusRequester.requestFocus() }.isSuccess) break
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
                    .widthIn(max = 420.dp)
                    .fillMaxWidth(0.9f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(BgDialog)
                    .border(1.dp, DividerColor, RoundedCornerShape(20.dp))
                    .pointerInput(Unit) {
                        detectTapGestures { /* consume tap */ }
                    }
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AccentBlue.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Language,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Tambah via Web Server",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        )
                        Text(
                            text = "Pastikan HP dan perangkat ini di jaringan Wi-Fi yang sama",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        )
                    }
                }

                if (serverError != null) {
                    Text(
                        text = serverError!!,
                        color = ColorError,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else if (serverUrl.isNotBlank()) {
                    // QR Code Container
                    Box(
                        modifier = Modifier
                            .size(196.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        QrCodeCanvas(
                            content = serverUrl,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Text(
                        text = "Scan QR Code di atas menggunakan kamera/browser HP, atau buka alamat berikut:",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )

                    // Local Web Address Pill
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(BgElevated)
                            .border(1.dp, AccentBlue.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Wifi,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = serverUrl,
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    if (lastAddedName != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(ColorSuccess.copy(alpha = 0.14f))
                                .border(1.dp, ColorSuccess.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = ColorSuccess,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Playlist \"$lastAddedName\" berhasil ditambahkan ($addedCount)",
                                color = ColorSuccess,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(ColorSuccess)
                            )
                            Text(
                                text = "Server aktif • Menunggu input dari browser...",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                var isCloseFocused by remember { mutableStateOf(false) }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(closeFocusRequester)
                        .onFocusChanged { isCloseFocused = it.isFocused || it.hasFocus }
                        .border(
                            width = if (isCloseFocused) 2.5.dp else 0.dp,
                            color = if (isCloseFocused) Color.White else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        ),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Text(
                        text = "Tutup",
                        color = Color.White,
                        fontWeight = if (isCloseFocused) FontWeight.Bold else FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Renders a standard ISO/IEC 18004 QR Code onto a Compose Canvas without external libraries.
 */
@Composable
fun QrCodeCanvas(
    content: String,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(content) { QrCodeEncoder.encode(content) }
    Canvas(modifier = modifier) {
        val n = matrix.size
        if (n == 0) return@Canvas
        val quietZone = 2
        val totalModules = n + quietZone * 2
        val moduleSize = minOf(size.width, size.height) / totalModules.toFloat()
        val offsetX = (size.width - moduleSize * totalModules) / 2f
        val offsetY = (size.height - moduleSize * totalModules) / 2f

        for (r in 0 until n) {
            for (c in 0 until n) {
                if (matrix[r][c]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(
                            x = offsetX + (c + quietZone) * moduleSize,
                            y = offsetY + (r + quietZone) * moduleSize
                        ),
                        size = Size(moduleSize * 1.03f, moduleSize * 1.03f)
                    )
                }
            }
        }
    }
}

/**
 * Self-contained ISO/IEC 18004 QR Code encoder (Byte mode, ECC Level L, Versions 1..4).
 */
internal object QrCodeEncoder {
    private data class VersionSpec(
        val version: Int,
        val totalDataCodewords: Int,
        val eccCodewords: Int,
        val alignmentCenters: IntArray,
    )

    private val specs = listOf(
        VersionSpec(1, 19, 7, intArrayOf()),
        VersionSpec(2, 34, 10, intArrayOf(6, 18)),
        VersionSpec(3, 55, 15, intArrayOf(6, 22)),
        VersionSpec(4, 80, 20, intArrayOf(6, 26)),
    )

    fun encode(text: String): Array<BooleanArray> {
        val dataBytes = text.toByteArray(Charsets.UTF_8)
        val spec = specs.firstOrNull { dataBytes.size + 2 <= it.totalDataCodewords }
            ?: specs.last()
        val payload = dataBytes.take(spec.totalDataCodewords - 2).toByteArray()

        val dataCodewords = buildDataCodewords(payload, spec.totalDataCodewords)
        val eccCodewords = computeReedSolomon(dataCodewords, spec.eccCodewords)
        val allCodewords = dataCodewords + eccCodewords

        val size = 17 + 4 * spec.version
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        fun setFunctionModule(row: Int, col: Int, dark: Boolean) {
            if (row in 0 until size && col in 0 until size) {
                modules[row][col] = dark
                isFunction[row][col] = true
            }
        }

        // Finder patterns + separators
        fun drawFinderPattern(topRow: Int, leftCol: Int) {
            for (dr in -1..7) {
                for (dc in -1..7) {
                    val r = topRow + dr
                    val c = leftCol + dc
                    if (r !in 0 until size || c !in 0 until size) continue
                    val inOuter = dr in 0..6 && dc in 0..6 && (dr == 0 || dr == 6 || dc == 0 || dc == 6)
                    val inInner = dr in 2..4 && dc in 2..4
                    setFunctionModule(r, c, inOuter || inInner)
                }
            }
        }
        drawFinderPattern(0, 0)
        drawFinderPattern(0, size - 7)
        drawFinderPattern(size - 7, 0)

        // Timing patterns
        for (i in 8 until size - 8) {
            setFunctionModule(6, i, i % 2 == 0)
            setFunctionModule(i, 6, i % 2 == 0)
        }

        // Alignment patterns
        val centers = spec.alignmentCenters
        for (cr in centers) {
            for (cc in centers) {
                if ((cr == 6 && cc == 6) || (cr == 6 && cc == size - 7) || (cr == size - 7 && cc == 6)) continue
                for (dr in -2..2) {
                    for (dc in -2..2) {
                        val dist = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc))
                        setFunctionModule(cr + dr, cc + dc, dist != 1)
                    }
                }
            }
        }

        // Reserve format info & dark module
        fun writeFormatBits(mask: Int) {
            val data = (0b01 shl 3) or mask
            var rem = data
            repeat(10) {
                rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int): Boolean = ((bits ushr i) and 1) != 0

            for (i in 0..5) setFunctionModule(i, 8, bit(i))
            setFunctionModule(7, 8, bit(6))
            setFunctionModule(8, 8, bit(7))
            setFunctionModule(8, 7, bit(8))
            for (i in 9..14) setFunctionModule(8, 14 - i, bit(i))

            for (i in 0..7) setFunctionModule(8, size - 1 - i, bit(i))
            for (i in 8..14) setFunctionModule(size - 15 + i, 8, bit(i))
            setFunctionModule(size - 8, 8, true)
        }
        writeFormatBits(0)

        // Place data + ECC bits in 2-column upward/downward zigzag
        var bitIndex = 0
        val totalBits = allCodewords.size * 8
        var rightCol = size - 1
        var upward = true
        while (rightCol >= 1) {
            if (rightCol == 6) rightCol -= 1
            for (step in 0 until size) {
                val r = if (upward) size - 1 - step else step
                for (dc in 0..1) {
                    val c = rightCol - dc
                    if (!isFunction[r][c]) {
                        val dark = if (bitIndex < totalBits) {
                            val b = allCodewords[bitIndex / 8].toInt() and 0xFF
                            ((b ushr (7 - (bitIndex % 8))) and 1) != 0
                        } else false
                        modules[r][c] = dark
                        bitIndex++
                    }
                }
            }
            upward = !upward
            rightCol -= 2
        }

        // Apply Mask 0: (row + col) % 2 == 0
        for (r in 0 until size) {
            for (c in 0 until size) {
                if (!isFunction[r][c] && (r + c) % 2 == 0) {
                    modules[r][c] = !modules[r][c]
                }
            }
        }
        writeFormatBits(0)
        return modules
    }

    private fun buildDataCodewords(payload: ByteArray, totalDataCodewords: Int): ByteArray {
        val bits = ArrayList<Int>(totalDataCodewords * 8)
        fun appendBits(value: Int, length: Int) {
            for (i in length - 1 downTo 0) {
                bits.add((value ushr i) and 1)
            }
        }
        // Byte mode indicator (0100) + 8-bit length
        appendBits(0b0100, 4)
        appendBits(payload.size, 8)
        for (b in payload) {
            appendBits(b.toInt() and 0xFF, 8)
        }
        // Terminator (up to 4 zero bits)
        val capacityBits = totalDataCodewords * 8
        repeat(minOf(4, capacityBits - bits.size)) { bits.add(0) }
        while (bits.size % 8 != 0) bits.add(0)

        val result = ByteArray(totalDataCodewords)
        val dataBytesWritten = bits.size / 8
        for (i in 0 until dataBytesWritten) {
            var v = 0
            for (bit in 0 until 8) {
                v = (v shl 1) or bits[i * 8 + bit]
            }
            result[i] = v.toByte()
        }
        val padBytes = intArrayOf(0xEC, 0x11)
        for (i in dataBytesWritten until totalDataCodewords) {
            result[i] = padBytes[(i - dataBytesWritten) % 2].toByte()
        }
        return result
    }

    private fun computeReedSolomon(data: ByteArray, degree: Int): ByteArray {
        fun gfMul(x: Int, y: Int): Int {
            var a = x
            var b = y
            var res = 0
            for (i in 7 downTo 0) {
                res = (res shl 1) xor ((res ushr 7) * 0x11D)
                res = res xor (((b ushr i) and 1) * a)
            }
            return res
        }

        val gen = IntArray(degree)
        gen[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                gen[j] = gfMul(gen[j], root)
                if (j + 1 < degree) {
                    gen[j] = gen[j] xor gen[j + 1]
                }
            }
            root = gfMul(root, 0x02)
        }

        val result = IntArray(degree)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor result[0]
            for (j in 0 until degree - 1) {
                result[j] = result[j + 1] xor gfMul(gen[j], factor)
            }
            result[degree - 1] = gfMul(gen[degree - 1], factor)
        }
        return ByteArray(degree) { result[it].toByte() }
    }
}

/**
 * Lightweight embedded HTTP server using java.net.ServerSocket.
 * Serves the web form on GET / and processes playlist submissions on POST /api/playlist.
 */
internal class LocalPlaylistHttpServer(
    private val context: Context,
    private val getExistingPlaylists: () -> List<Playlist>,
    private val onPlaylistSubmitted: (Playlist) -> Unit,
) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var port: Int = 8080
        private set

    fun start() {
        var boundSocket: ServerSocket? = null
        for (candidatePort in 8080..8095) {
            try {
                boundSocket = ServerSocket(candidatePort)
                port = candidatePort
                break
            } catch (_: Exception) {}
        }
        if (boundSocket == null) {
            boundSocket = ServerSocket(0)
            port = boundSocket.localPort
        }
        serverSocket = boundSocket

        scope.launch {
            while (isActive && serverSocket?.isClosed == false) {
                val client = try {
                    serverSocket?.accept() ?: break
                } catch (_: Exception) {
                    break
                }
                launch { handleClient(client) }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }

    fun getServerUrl(): String {
        val ip = findLocalIpv4Address() ?: "127.0.0.1"
        return "http://$ip:$port"
    }

    private fun findLocalIpv4Address(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val candidates = mutableListOf<Pair<Int, String>>()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val priority = when {
                            name.startsWith("wlan") || name.startsWith("eth") -> 1
                            ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.") -> 2
                            else -> 3
                        }
                        candidates.add(priority to ip)
                    }
                }
            }
            candidates.minByOrNull { it.first }?.second
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val out = client.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0)?.uppercase() ?: "GET"
            val path = parts.getOrNull(1) ?: "/"

            var contentLength = 0
            while (true) {
                val header = reader.readLine()
                if (header.isNullOrEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            if (method == "GET" && (path == "/" || path.startsWith("/?"))) {
                val htmlBytes = WEB_FORM_HTML.toByteArray(Charsets.UTF_8)
                sendResponse(out, 200, "text/html; charset=utf-8", htmlBytes)
                return
            }

            if (method == "POST" && (path == "/api/playlist" || path == "/api/test" || path == "/api/download")) {
                if (contentLength <= 0 || contentLength > 20 * 1024 * 1024) {
                    sendJson(out, 400, false, "Ukuran data tidak valid (maksimal 20 MB)")
                    return
                }
                val bodyChars = CharArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val r = reader.read(bodyChars, totalRead, contentLength - totalRead)
                    if (r == -1) break
                    totalRead += r
                }
                val bodyStr = String(bodyChars, 0, totalRead)
                when (path) {
                    "/api/playlist" -> handlePlaylistPost(out, bodyStr)
                    "/api/test" -> handlePlaylistTest(out, bodyStr)
                    "/api/download" -> handlePlaylistDownload(out, bodyStr)
                }
                return
            }

            sendResponse(out, 404, "text/plain; charset=utf-8", "Not Found".toByteArray(Charsets.UTF_8))
        }
    }

    private suspend fun handlePlaylistPost(out: OutputStream, bodyStr: String) {
        try {
            val json = JSONObject(bodyStr)
            val selectedType = json.optInt("selectedType", 0)
            val name = json.optString("name", "").trim()
            val urlOrRaw = json.optString("urlOrRaw", "").trim()
            val isFileUpload = json.optBoolean("isFileUpload", false)
            val fileName = json.optString("fileName", "").trim()
            val serverUrl = json.optString("serverUrl", "").trim().trimEnd('/')
            val username = json.optString("username", "").trim()
            val password = json.optString("password", "")
            val userAgent = json.optString("userAgent", "").trim()
            val customHeaders = json.optString("customHeaders", "").trim()

            if (name.isBlank()) {
                sendJson(out, 400, false, "Nama Alias wajib diisi.")
                return
            }

            val sourceValid = when (selectedType) {
                0 -> urlOrRaw.isNotBlank()
                1 -> (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) && username.isNotBlank()
                else -> false
            }
            if (!sourceValid) {
                val msg = if (selectedType == 0) {
                    "URL atau Isi Raw M3U wajib diisi."
                } else {
                    "URL Server Xtream (http/https) dan Username wajib diisi."
                }
                sendJson(out, 400, false, msg)
                return
            }

            // Hanya simpan menjadi file (path) jika berasal dari unggahan file (Pilih File).
            // Jika berupa isi playlist (raw M3U) atau URL, simpan langsung isinya ke kolom source.
            val finalSource = if (selectedType == 0 && isFileUpload) {
                val playlistsDir = File(context.filesDir, "playlists").apply { if (!exists()) mkdirs() }
                val safeFileName = fileName.replace("[^a-zA-Z0-9._-]".toRegex(), "_").takeIf { it.isNotBlank() }
                    ?: "playlist_${System.currentTimeMillis()}.m3u"
                val destFile = File(playlistsDir, "${System.currentTimeMillis()}_$safeFileName")
                destFile.writeText(urlOrRaw, Charsets.UTF_8)
                destFile.absolutePath
            } else {
                urlOrRaw
            }

            fun String.normalizeUrl() = this.trim().trimEnd('/').lowercase()
            val normalizedSource = finalSource.normalizeUrl()
            val normalizedServer = serverUrl.normalizeUrl()

            val existing = getExistingPlaylists()
            val duplicate = existing.find { p ->
                (selectedType == 0 && p.source.normalizeUrl() == normalizedSource) ||
                (selectedType == 1 && p.serverUrl.normalizeUrl() == normalizedServer && p.username.trim() == username)
            }
            if (duplicate != null) {
                sendJson(
                    out,
                    409,
                    false,
                    "Sumber ini sudah digunakan oleh playlist \"${duplicate.name}\"."
                )
                return
            }

            // isFileUpload = true berarti konten file sudah ada di urlOrRaw dan path disimpan ke finalSource.
            // Raw M3U yang di-paste (tidak ada upload file) tetap M3U_URL agar M3uParser
            // membacanya via branch contains("#EXTM3U"), bukan mencoba membuka sebagai path file.
            val finalType = when {
                selectedType == 1 -> PlaylistType.XTREAM
                isFileUpload      -> PlaylistType.M3U_FILE
                else              -> PlaylistType.M3U_URL
            }
            val isXtream = finalType == PlaylistType.XTREAM

            val playlist = Playlist(
                id = 0L,
                name = name,
                type = finalType,
                source = if (isXtream) "" else finalSource,
                username = if (isXtream) username else "",
                password = if (isXtream) password else "",
                serverUrl = if (isXtream) serverUrl else "",
                userAgent = userAgent,
                customHeaders = customHeaders,
                isActive = true,
                addedAt = System.currentTimeMillis()
            )

            withContext(Dispatchers.Main) {
                onPlaylistSubmitted(playlist)
            }

            sendJson(out, 200, true, "Playlist \"$name\" berhasil ditambahkan ke DINS Player!")
        } catch (e: Exception) {
            sendJson(out, 500, false, "Gagal memproses data: ${e.message}")
        }
    }

    private fun handlePlaylistTest(out: OutputStream, bodyStr: String) {
        try {
            val json = JSONObject(bodyStr)
            val selectedType = json.optInt("selectedType", 0)
            val urlOrRaw = json.optString("urlOrRaw", "").trim()
            val serverUrl = json.optString("serverUrl", "").trim().trimEnd('/')
            val username = json.optString("username", "").trim()
            val password = json.optString("password", "")
            val userAgent = json.optString("userAgent", "").trim()

            if (selectedType == 1) {
                if (serverUrl.isBlank() || username.isBlank()) {
                    sendJson(out, 400, false, "Silakan isi URL Server dan Username terlebih dahulu")
                    return
                }
                val ua = userAgent.takeIf { it.isNotBlank() }
                    ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.60 Mobile Safari/537.36"
                val client = FallbackDns.configure(
                    OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                ).build()
                val testUrl = "$serverUrl/player_api.php?username=${android.net.Uri.encode(username)}&password=${android.net.Uri.encode(password)}"
                val req = Request.Builder().url(testUrl).header("User-Agent", ua).build()
                val response = client.newCall(req).execute()
                if (!response.isSuccessful) {
                    response.body?.close()
                    sendJson(out, 200, false, "HTTP ${response.code}: Gagal akses panel")
                    return
                }
                val respStr = response.use { it.body?.string() } ?: ""
                val parsed = JSONTokener(respStr).nextValue()
                if (parsed is JSONObject && parsed.has("user_info")) {
                    val status = parsed.getJSONObject("user_info").optString("status", "")
                    if (status.equals("Active", ignoreCase = true)) {
                        sendJson(out, 200, true, "✅ Login sukses! Status akun: Active")
                    } else {
                        sendJson(out, 200, false, "⚠️ Akun tidak aktif (Status: $status)")
                    }
                } else {
                    sendJson(out, 200, false, "❌ Respon server tidak valid atau otentikasi gagal")
                }
                return
            }

            if (urlOrRaw.isBlank()) {
                sendJson(out, 400, false, "Silakan masukkan URL atau isi M3U terlebih dahulu")
                return
            }
            if (!urlOrRaw.startsWith("http://") && !urlOrRaw.startsWith("https://")) {
                val isM3u = urlOrRaw.contains("#EXTM3U") || urlOrRaw.contains("#EXTINF:")
                sendJson(out, 200, isM3u, if (isM3u) "✅ Isi raw M3U valid" else "⚠️ Format raw M3U tidak valid")
                return
            }
            val client = FallbackDns.configure(
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
            ).build()
            val reqBuilder = Request.Builder().url(urlOrRaw)
            if (userAgent.isNotBlank()) reqBuilder.header("User-Agent", userAgent)
            val response = client.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) {
                response.body?.close()
                sendJson(out, 200, false, "HTTP ${response.code}: Server menolak request")
                return
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
                isM3u -> sendJson(out, 200, true, "✅ Format M3U valid dan siap digunakan")
                isTxt -> sendJson(out, 200, true, "✅ Format daftar URL valid")
                else -> sendJson(out, 200, false, "⚠️ URL bisa diakses tapi isinya bukan playlist M3U")
            }
        } catch (e: Exception) {
            sendJson(out, 200, false, "❌ Gagal terhubung: ${e.message?.take(60)}")
        }
    }

    private fun handlePlaylistDownload(out: OutputStream, bodyStr: String) {
        try {
            val json = JSONObject(bodyStr)
            val name = json.optString("name", "").trim()
            val urlOrRaw = json.optString("urlOrRaw", "").trim()
            val userAgent = json.optString("userAgent", "").trim()

            if (name.isBlank() || urlOrRaw.isBlank()) {
                sendJson(out, 400, false, "Nama dan Link/Isi M3U wajib diisi!")
                return
            }

            val isUrl = urlOrRaw.startsWith("http://") || urlOrRaw.startsWith("https://")
            val contentToSave = if (isUrl) {
                val client = FallbackDns.configure(
                    OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(120, TimeUnit.SECONDS)
                        .callTimeout(150, TimeUnit.SECONDS)
                        .followRedirects(true)
                        .followSslRedirects(true)
                ).build()
                val reqBuilder = Request.Builder().url(urlOrRaw)
                if (userAgent.isNotBlank()) reqBuilder.header("User-Agent", userAgent)
                val response = client.newCall(reqBuilder.build()).execute()
                if (!response.isSuccessful) {
                    response.body?.close()
                    sendJson(out, 200, false, "Gagal mengunduh: HTTP ${response.code}")
                    return
                }
                val contentLength = response.body?.contentLength() ?: -1L
                if (contentLength > 50_000_000L) {
                    response.body?.close()
                    sendJson(out, 200, false, "File terlalu besar (>50 MB), tidak dapat disimpan")
                    return
                }
                response.use { it.body?.string() } ?: ""
            } else {
                urlOrRaw
            }

            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val appDir = File(downloadsDir, "dinsplayer").apply { if (!exists()) mkdirs() }
            val safeName = name.replace("[^a-zA-Z0-9.-]".toRegex(), "_").takeIf { it.isNotBlank() } ?: "playlist"
            val file = File(appDir, "$safeName.m3u")
            file.writeText(contentToSave, Charsets.UTF_8)

            sendJson(out, 200, true, "✅ Berhasil disimpan di Downloads/dinsplayer/$safeName.m3u")
        } catch (e: Exception) {
            sendJson(out, 200, false, "❌ Gagal menyimpan: ${e.message}")
        }
    }

    private fun sendJson(out: OutputStream, status: Int, ok: Boolean, message: String) {
        val payload = JSONObject().apply {
            put("ok", ok)
            put("message", message)
        }.toString().toByteArray(Charsets.UTF_8)
        sendResponse(out, status, "application/json; charset=utf-8", payload)
    }

    private fun sendResponse(out: OutputStream, status: Int, contentType: String, body: ByteArray) {
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            409 -> "Conflict"
            else -> "Internal Server Error"
        }
        val headers = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }
}

private const val WEB_FORM_HTML = """<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Tambah Playlist - DINS Player</title>
<style>
  :root {
    --bg-base: #101622;
    --bg-card: #1A2234;
    --bg-input: #232D42;
    --accent: #3B82F6;
    --text-primary: #F8FAFC;
    --text-secondary: #94A3B8;
    --border: rgba(255,255,255,0.12);
    --error: #EF4444;
    --success: #10B981;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
  body {
    background: var(--bg-base);
    color: var(--text-primary);
    min-height: 100vh;
    display: flex;
    justify-content: center;
    align-items: flex-start;
    padding: 20px 14px;
  }
  .card {
    width: 100%;
    max-width: 480px;
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 20px;
    padding: 22px;
    box-shadow: 0 20px 40px rgba(0,0,0,0.45);
  }
  h1 { font-size: 19px; font-weight: 700; margin-bottom: 4px; }
  .subtitle { font-size: 12px; color: var(--text-secondary); margin-bottom: 18px; }
  .tabs {
    display: flex;
    background: rgba(255,255,255,0.06);
    border-radius: 12px;
    padding: 4px;
    gap: 4px;
    margin-bottom: 16px;
  }
  .tab {
    flex: 1;
    text-align: center;
    padding: 10px 8px;
    font-size: 13px;
    font-weight: 600;
    color: rgba(255,255,255,0.45);
    border-radius: 9px;
    border: 1px solid transparent;
    cursor: pointer;
    user-select: none;
  }
  .tab.active {
    background: rgba(59, 130, 246, 0.2);
    color: var(--accent);
    border-color: var(--accent);
  }
  .field { margin-bottom: 12px; }
  input[type="text"], input[type="password"], textarea {
    width: 100%;
    background: rgba(255,255,255,0.06);
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 12px 14px;
    color: var(--text-primary);
    font-size: 13px;
    outline: none;
  }
  input:focus, textarea:focus { border-color: var(--accent); }
  textarea { resize: vertical; min-height: 76px; }
  .file-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-top: 6px;
  }
  .file-btn {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    background: rgba(59, 130, 246, 0.16);
    color: var(--accent);
    border: 1px solid rgba(59, 130, 246, 0.4);
    border-radius: 8px;
    padding: 6px 12px;
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;
  }
  .section {
    background: rgba(255,255,255,0.05);
    border-radius: 12px;
    padding: 12px 14px;
    margin-bottom: 12px;
  }
  .section-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    cursor: pointer;
    font-size: 13px;
    color: var(--text-secondary);
    font-weight: 600;
  }
  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 10px;
  }
  .chip {
    padding: 6px 10px;
    border-radius: 8px;
    font-size: 11px;
    background: rgba(255,255,255,0.07);
    color: var(--text-secondary);
    border: 1px solid transparent;
    cursor: pointer;
  }
  .chip.active {
    background: rgba(59, 130, 246, 0.22);
    color: #fff;
    border-color: var(--accent);
    font-weight: 600;
  }
  .msg {
    padding: 10px 12px;
    border-radius: 10px;
    font-size: 12px;
    margin-bottom: 14px;
    display: none;
  }
  .msg.error { display: block; background: rgba(239,68,68,0.14); border: 1px solid rgba(239,68,68,0.4); color: #FCA5A5; }
  .msg.success { display: block; background: rgba(16,185,129,0.14); border: 1px solid rgba(16,185,129,0.4); color: #6EE7B7; }
  .submit-btn {
    width: 100%;
    background: var(--accent);
    color: #fff;
    border: none;
    border-radius: 12px;
    padding: 13px;
    font-size: 14px;
    font-weight: 700;
    cursor: pointer;
  }
  .outline-btn {
    width: 100%;
    background: transparent;
    color: var(--accent);
    border: 1px solid var(--accent);
    border-radius: 12px;
    padding: 11px;
    font-size: 13px;
    font-weight: 600;
    cursor: pointer;
    margin-bottom: 10px;
  }
  .submit-btn:disabled, .outline-btn:disabled { opacity: 0.55; cursor: not-allowed; }
  .hidden { display: none; }
</style>
</head>
<body>
<div class="card">
  <h1>Tambah Playlist</h1>
  <div class="subtitle">Kirim playlist langsung ke DINS Player di TV / perangkat Anda</div>

  <div class="tabs">
    <div id="tabM3u" class="tab active" onclick="setMode(0)">URL / M3U</div>
    <div id="tabXtream" class="tab" onclick="setMode(1)">Xtream Codes</div>
  </div>

  <div class="field">
    <input type="text" id="name" placeholder="Nama Alias">
  </div>

  <div id="m3uFields">
    <div class="field">
      <textarea id="urlOrRaw" placeholder="URL atau Isi Raw M3U" oninput="onManualSourceInput()"></textarea>
      <div class="file-row">
        <span id="fileNameHint" style="font-size:11px;color:var(--text-secondary);">Pilih file .m3u/.txt atau tempel URL/playlist</span>
        <div style="display:flex;gap:6px;">
          <button type="button" class="file-btn" onclick="pasteClipboard()">Paste</button>
          <label class="file-btn">
            Pilih File
            <input type="file" id="fileInput" accept=".m3u,.m3u8,.txt,*/*" style="display:none" onchange="onFilePicked(event)">
          </label>
        </div>
      </div>
    </div>
  </div>

  <div id="xtreamFields" class="hidden">
    <div class="field">
      <input type="text" id="serverUrl" placeholder="URL Server Xtream (contoh: http://domain.com:8080)">
    </div>
    <div class="field">
      <input type="text" id="username" placeholder="Username">
    </div>
    <div class="field" style="position:relative;">
      <input type="password" id="password" placeholder="Password" style="padding-right:64px;">
      <button type="button" onclick="togglePwd()" id="pwdBtn" style="position:absolute;right:10px;top:9px;background:none;border:none;color:var(--accent);font-size:11px;font-weight:600;cursor:pointer;">Lihat</button>
    </div>
  </div>

  <div class="section">
    <div class="section-header" onclick="toggleUa()">
      <span>User-Agent</span>
      <span id="uaBadge" style="color:var(--accent);font-size:11px;">Default</span>
    </div>
    <div id="uaBody" class="hidden">
      <div class="chips">
        <div class="chip active" onclick="selectUa('default', this)">Default</div>
        <div class="chip" onclick="selectUa('DinsPlayer/1.0 (Android; Linux; U; ExoPlayer) Chrome/129.0.0.0 Safari/537.36', this)">DinsPlayer</div>
        <div class="chip" onclick="selectUa('TiviMate/4.7.0', this)">TiviMate</div>
        <div class="chip" onclick="selectUa('VLC/3.0.18 LibVLC/3.0.18', this)">VLC</div>
        <div class="chip" onclick="selectUa('Mozilla/5.0 (Windows NT 10.0; Win64; x64)', this)">Browser PC</div>
        <div class="chip" onclick="selectUa('custom', this)">Custom</div>
      </div>
      <div id="customUaWrap" class="field hidden" style="margin-top:10px;margin-bottom:0;">
        <input type="text" id="customUa" placeholder="Masukkan Custom User-Agent">
      </div>
    </div>
  </div>

  <div class="section">
    <div class="section-header" onclick="toggleHeaders()">
      <span>Custom Headers</span>
      <span style="font-size:11px;">Opsional</span>
    </div>
    <div id="headersBody" class="hidden" style="margin-top:10px;">
      <div style="font-size:11px;color:var(--text-secondary);margin-bottom:6px;">Satu header per baris, format: Nama: Nilai</div>
      <textarea id="customHeaders" placeholder="X-Forwarded-For: 1.2.3.4&#10;Referer: https://site.com"></textarea>
    </div>
  </div>

  <button id="testBtn" type="button" class="outline-btn" onclick="testSource()">Test URL</button>
  <button id="downloadBtn" type="button" class="outline-btn" onclick="downloadToDevice()">Unduh/Simpan M3U ke Perangkat</button>

  <div id="statusMsg" class="msg"></div>

  <button id="submitBtn" class="submit-btn" onclick="submitPlaylist()">Simpan</button>
</div>

<script>
  var selectedType = 0;
  var uaMode = 'default';
  var isFileUpload = false;
  var uploadedFileName = '';
  var uploadedFileContent = '';

  function setMode(m) {
    selectedType = m;
    document.getElementById('tabM3u').className = m === 0 ? 'tab active' : 'tab';
    document.getElementById('tabXtream').className = m === 1 ? 'tab active' : 'tab';
    document.getElementById('m3uFields').className = m === 0 ? '' : 'hidden';
    document.getElementById('xtreamFields').className = m === 1 ? '' : 'hidden';
    document.getElementById('testBtn').innerText = m === 0 ? 'Test URL' : 'Test Akun';
    document.getElementById('downloadBtn').className = m === 0 ? 'outline-btn' : 'outline-btn hidden';
  }

  function onManualSourceInput() {
    isFileUpload = false;
    uploadedFileName = '';
    uploadedFileContent = '';
    document.getElementById('fileNameHint').innerText = 'Pilih file .m3u/.txt atau tempel URL/playlist';
  }

  function togglePwd() {
    var p = document.getElementById('password');
    var b = document.getElementById('pwdBtn');
    if (p.type === 'password') { p.type = 'text'; b.innerText = 'Sembunyi'; }
    else { p.type = 'password'; b.innerText = 'Lihat'; }
  }

  function pasteClipboard() {
    if (navigator.clipboard && navigator.clipboard.readText) {
      navigator.clipboard.readText().then(function(t) {
        if (t) {
          onManualSourceInput();
          document.getElementById('urlOrRaw').value = t;
        }
      }).catch(function() {});
    }
  }

  function toggleUa() {
    var el = document.getElementById('uaBody');
    el.className = el.className === 'hidden' ? '' : 'hidden';
  }

  function toggleHeaders() {
    var el = document.getElementById('headersBody');
    el.className = el.className === 'hidden' ? '' : 'hidden';
  }

  function selectUa(val, chipEl) {
    uaMode = val;
    var chips = document.querySelectorAll('.chip');
    for (var i = 0; i < chips.length; i++) chips[i].className = 'chip';
    chipEl.className = 'chip active';
    document.getElementById('customUaWrap').className = val === 'custom' ? 'field' : 'field hidden';
    document.getElementById('uaBadge').innerText = val === 'default' ? 'Default' : (val === 'custom' ? 'Custom' : chipEl.innerText);
  }

  function onFilePicked(e) {
    var file = e.target.files[0];
    if (!file) return;
    isFileUpload = true;
    uploadedFileName = file.name;
    document.getElementById('fileNameHint').innerText = 'File dipilih: ' + file.name;
    var nameInput = document.getElementById('name');
    if (!nameInput.value.trim()) {
      nameInput.value = file.name.replace(/\.[^/.]+$/, '');
    }
    var reader = new FileReader();
    reader.onload = function(evt) {
      uploadedFileContent = evt.target.result || '';
      document.getElementById('urlOrRaw').value = file.name;
    };
    reader.readAsText(file);
  }

  function showMsg(text, isOk) {
    var box = document.getElementById('statusMsg');
    box.className = isOk ? 'msg success' : 'msg error';
    box.innerText = text;
  }

  function buildPayload() {
    var ua = '';
    if (uaMode === 'custom') {
      ua = document.getElementById('customUa').value.trim();
    } else if (uaMode !== 'default') {
      ua = uaMode;
    }
    var sourceVal = isFileUpload ? uploadedFileContent.trim() : document.getElementById('urlOrRaw').value.trim();
    return {
      selectedType: selectedType,
      name: document.getElementById('name').value.trim(),
      urlOrRaw: sourceVal,
      isFileUpload: isFileUpload,
      fileName: uploadedFileName,
      serverUrl: document.getElementById('serverUrl').value.trim(),
      username: document.getElementById('username').value.trim(),
      password: document.getElementById('password').value,
      userAgent: ua,
      customHeaders: document.getElementById('customHeaders').value.trim()
    };
  }

  function testSource() {
    var btn = document.getElementById('testBtn');
    btn.disabled = true;
    btn.innerText = 'Mengecek...';
    fetch('/api/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(buildPayload())
    })
    .then(function(r) { return r.json(); })
    .then(function(res) { showMsg(res.message, res.ok); })
    .catch(function(err) { showMsg('Gagal mengecek: ' + err, false); })
    .finally(function() {
      btn.disabled = false;
      btn.innerText = selectedType === 0 ? 'Test URL' : 'Test Akun';
    });
  }

  function downloadToDevice() {
    var btn = document.getElementById('downloadBtn');
    btn.disabled = true;
    btn.innerText = 'Menyimpan...';
    fetch('/api/download', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(buildPayload())
    })
    .then(function(r) { return r.json(); })
    .then(function(res) { showMsg(res.message, res.ok); })
    .catch(function(err) { showMsg('Gagal menyimpan: ' + err, false); })
    .finally(function() {
      btn.disabled = false;
      btn.innerText = 'Unduh/Simpan M3U ke Perangkat';
    });
  }

  function submitPlaylist() {
    var btn = document.getElementById('submitBtn');
    btn.disabled = true;
    btn.innerText = 'Menyimpan...';

    fetch('/api/playlist', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(buildPayload())
    })
    .then(function(r) { return r.json(); })
    .then(function(res) {
      showMsg(res.message, res.ok);
      if (res.ok) {
        document.getElementById('name').value = '';
        document.getElementById('urlOrRaw').value = '';
        onManualSourceInput();
      }
    })
    .catch(function(err) {
      showMsg('Gagal terhubung ke aplikasi: ' + err, false);
    })
    .finally(function() {
      btn.disabled = false;
      btn.innerText = 'Simpan';
    });
  }
</script>
</body>
</html>
"""
