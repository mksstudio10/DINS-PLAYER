package com.dinsplayer.app.features.beranda

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dinsplayer.app.data.models.DrmSchemeOption
import com.dinsplayer.app.data.models.StreamTestForm
import com.dinsplayer.app.data.models.UserAgentOption
import com.dinsplayer.app.features.playlist.QrCodeCanvas
import com.dinsplayer.app.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Popup dialog that starts the embedded HTTP server for Uji Stream, renders a scannable QR code
 * and the local URL, and updates or plays the stream submitted from the phone browser.
 */
@Composable
fun WebServerStreamDialog(
    currentForm: StreamTestForm,
    onDismiss: () -> Unit,
    onApplyForm: (StreamTestForm, Boolean) -> Unit,
) {
    val closeFocusRequester = remember { FocusRequester() }
    var serverUrl by remember { mutableStateOf("") }
    var serverError by remember { mutableStateOf<String?>(null) }
    var lastSubmittedUrl by remember { mutableStateOf<String?>(null) }

    val latestForm by rememberUpdatedState(currentForm)
    val latestOnApply by rememberUpdatedState(onApplyForm)
    val latestOnDismiss by rememberUpdatedState(onDismiss)

    DisposableEffect(Unit) {
        val server = LocalStreamHttpServer(
            getCurrentForm = { latestForm },
            onStreamSubmitted = { newForm, playNow ->
                lastSubmittedUrl = newForm.url
                latestOnApply(newForm, playNow)
                if (playNow) {
                    latestOnDismiss()
                }
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
                            text = "Uji Stream via Web Server",
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

                    if (lastSubmittedUrl != null) {
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
                                text = "Form Uji Stream berhasil diperbarui dari browser!",
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

internal class LocalStreamHttpServer(
    private val getCurrentForm: () -> StreamTestForm,
    private val onStreamSubmitted: (StreamTestForm, Boolean) -> Unit,
) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var port: Int = 8086
        private set

    fun start() {
        var boundSocket: ServerSocket? = null
        for (candidatePort in 8086..8099) {
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
                val htmlBytes = STREAM_WEB_FORM_HTML.toByteArray(Charsets.UTF_8)
                sendResponse(out, 200, "text/html; charset=utf-8", htmlBytes)
                return
            }

            if (method == "GET" && path == "/api/state") {
                val f = getCurrentForm()
                val json = JSONObject().apply {
                    put("url", f.url)
                    put("xForwardedFor", f.xForwardedFor)
                    put("referer", f.referer)
                    put("origin", f.origin)
                    put("drmLicense", f.drmLicense)
                    put("customHeaders", f.customHeaders)
                    put("userAgent", f.userAgent.name)
                    put("customUserAgent", f.customUserAgent)
                    put("drmScheme", f.drmScheme.name)
                }.toString().toByteArray(Charsets.UTF_8)
                sendResponse(out, 200, "application/json; charset=utf-8", json)
                return
            }

            if (method == "POST" && path == "/api/stream") {
                if (contentLength <= 0 || contentLength > 1 * 1024 * 1024) {
                    sendJson(out, 400, false, "Ukuran data tidak valid")
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
                handleStreamPost(out, bodyStr)
                return
            }

            sendResponse(out, 404, "text/plain; charset=utf-8", "Not Found".toByteArray(Charsets.UTF_8))
        }
    }

    private suspend fun handleStreamPost(out: OutputStream, bodyStr: String) {
        try {
            val json = JSONObject(bodyStr)
            val url = json.optString("url", "").trim()
            val xForwardedFor = json.optString("xForwardedFor", "").trim()
            val referer = json.optString("referer", "").trim()
            val origin = json.optString("origin", "").trim()
            val drmLicense = json.optString("drmLicense", "").trim()
            val customHeaders = json.optString("customHeaders", "").trim()
            val uaName = json.optString("userAgent", UserAgentOption.DEFAULT.name)
            val customUserAgent = json.optString("customUserAgent", "").trim()
            val drmSchemeName = json.optString("drmScheme", DrmSchemeOption.AUTO.name)
            val playNow = json.optBoolean("playNow", false)

            if (url.isBlank()) {
                sendJson(out, 400, false, "Tautan Stream (URL) wajib diisi.")
                return
            }

            val uaOption = runCatching { UserAgentOption.valueOf(uaName) }.getOrDefault(UserAgentOption.DEFAULT)
            val drmOption = runCatching { DrmSchemeOption.valueOf(drmSchemeName) }.getOrDefault(DrmSchemeOption.AUTO)

            val newForm = StreamTestForm(
                url = url,
                xForwardedFor = xForwardedFor,
                referer = referer,
                origin = origin,
                drmLicense = drmLicense,
                customHeaders = customHeaders,
                userAgent = uaOption,
                customUserAgent = customUserAgent,
                drmScheme = drmOption,
                showCustomHeaders = customHeaders.isNotBlank()
            )

            withContext(Dispatchers.Main) {
                onStreamSubmitted(newForm, playNow)
            }

            val msg = if (playNow) {
                "✅ Stream dikirim dan langsung diputar di DINS Player!"
            } else {
                "✅ Data Uji Stream berhasil diisi ke aplikasi!"
            }
            sendJson(out, 200, true, msg)
        } catch (e: Exception) {
            sendJson(out, 500, false, "Gagal memproses data: ${e.message}")
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

private const val STREAM_WEB_FORM_HTML = """<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Uji Stream - DINS Player</title>
<style>
  :root {
    --bg-base: #101622;
    --bg-card: #1A2234;
    --accent: #3B82F6;
    --text-primary: #F8FAFC;
    --text-secondary: #94A3B8;
    --border: rgba(255,255,255,0.14);
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
  .field { margin-bottom: 12px; }
  label.field-label { display: block; font-size: 11px; color: var(--text-secondary); margin-bottom: 5px; font-weight: 600; }
  input[type="text"], textarea, select {
    width: 100%;
    background: rgba(255,255,255,0.06);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 12px 14px;
    color: var(--text-primary);
    font-size: 13px;
    outline: none;
  }
  select option { background: #1A2234; color: #F8FAFC; }
  input:focus, textarea:focus, select:focus { border-color: var(--accent); }
  textarea { resize: vertical; min-height: 82px; }
  .url-wrap { position: relative; }
  .paste-btn {
    position: absolute;
    right: 8px;
    top: 8px;
    background: rgba(59, 130, 246, 0.18);
    color: var(--accent);
    border: 1px solid rgba(59, 130, 246, 0.4);
    border-radius: 8px;
    padding: 6px 10px;
    font-size: 11px;
    font-weight: 600;
    cursor: pointer;
  }
  .accordion {
    background: rgba(255,255,255,0.06);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 13px 16px;
    margin-bottom: 12px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    cursor: pointer;
    user-select: none;
    font-size: 13px;
    color: var(--text-secondary);
    font-weight: 600;
  }
  .row-2 { display: flex; gap: 10px; }
  .row-2 > .field { flex: 1; }
  .msg {
    padding: 10px 12px;
    border-radius: 10px;
    font-size: 12px;
    margin-bottom: 14px;
    display: none;
  }
  .msg.error { display: block; background: rgba(239,68,68,0.14); border: 1px solid rgba(239,68,68,0.4); color: #FCA5A5; }
  .msg.success { display: block; background: rgba(16,185,129,0.14); border: 1px solid rgba(16,185,129,0.4); color: #6EE7B7; }
  .btn-primary {
    width: 100%;
    background: linear-gradient(90deg, #1565C0, #448AFF);
    color: #fff;
    border: none;
    border-radius: 14px;
    padding: 14px;
    font-size: 15px;
    font-weight: 700;
    cursor: pointer;
    margin-bottom: 10px;
  }
  .btn-outline {
    width: 100%;
    background: transparent;
    color: var(--accent);
    border: 1px solid var(--accent);
    border-radius: 14px;
    padding: 12px;
    font-size: 13px;
    font-weight: 600;
    cursor: pointer;
  }
  .btn-primary:disabled, .btn-outline:disabled { opacity: 0.55; cursor: not-allowed; }
  .hidden { display: none; }
</style>
</head>
<body>
<div class="card">
  <h1>Uji Stream</h1>
  <div class="subtitle">Kirim tautan stream &amp; opsi DRM langsung ke DINS Player</div>

  <div class="field url-wrap">
    <input type="text" id="url" placeholder="Tautan Stream (https://...)" style="padding-right: 68px;">
    <button type="button" class="paste-btn" onclick="pasteUrl()">Tempel</button>
  </div>

  <div class="accordion" onclick="toggleAdvanced()">
    <span>⚙ Opsi Lanjutan</span>
    <span id="advArrow">▼</span>
  </div>

  <div id="advBody" class="hidden">
    <div class="field">
      <input type="text" id="xForwardedFor" placeholder="X-Forwarded-For">
    </div>
    <div class="field">
      <input type="text" id="referer" placeholder="Referer">
    </div>
    <div class="field">
      <input type="text" id="origin" placeholder="Origin">
    </div>
    <div class="field">
      <input type="text" id="drmLicense" placeholder="URL Lisensi DRM / kid:key">
    </div>
    <div class="field">
      <label class="field-label">Custom Headers (Satu per baris, Nama: Nilai)</label>
      <textarea id="customHeaders" placeholder="User-Agent: Custom&#10;Authorization: Bearer token"></textarea>
    </div>
    <div class="row-2">
      <div class="field">
        <label class="field-label">UserAgent</label>
        <select id="userAgent" onchange="onUaChange()">
          <option value="DEFAULT">Default</option>
          <option value="CUSTOM">Custom</option>
          <option value="CHROME">Chrome</option>
          <option value="DINSPLAYER">DinsPlayer</option>
          <option value="VLC">VLC</option>
          <option value="TIVIMATE">TiviMate</option>
        </select>
      </div>
      <div class="field">
        <label class="field-label">DRM Scheme</label>
        <select id="drmScheme">
          <option value="AUTO">Auto</option>
          <option value="CLEARKEY">ClearKey</option>
          <option value="WIDEVINE">Widevine</option>
          <option value="PLAYREADY">PlayReady</option>
        </select>
      </div>
    </div>
    <div id="customUaWrap" class="field hidden">
      <input type="text" id="customUserAgent" placeholder="Custom User-Agent">
    </div>
  </div>

  <div id="statusMsg" class="msg"></div>

  <button id="playBtn" type="button" class="btn-primary" onclick="submitStream(true)">▶ Putar Stream di Perangkat</button>
  <button id="fillBtn" type="button" class="btn-outline" onclick="submitStream(false)">Isi ke Form Aplikasi Saja</button>
</div>

<script>
  function toggleAdvanced() {
    var body = document.getElementById('advBody');
    var arrow = document.getElementById('advArrow');
    if (body.className === 'hidden') {
      body.className = '';
      arrow.innerText = '▲';
    } else {
      body.className = 'hidden';
      arrow.innerText = '▼';
    }
  }

  function onUaChange() {
    var ua = document.getElementById('userAgent').value;
    document.getElementById('customUaWrap').className = ua === 'CUSTOM' ? 'field' : 'field hidden';
  }

  function pasteUrl() {
    if (navigator.clipboard && navigator.clipboard.readText) {
      navigator.clipboard.readText().then(function(t) {
        if (t) document.getElementById('url').value = t.trim();
      }).catch(function() {});
    }
  }

  function showMsg(text, isOk) {
    var box = document.getElementById('statusMsg');
    box.className = isOk ? 'msg success' : 'msg error';
    box.innerText = text;
  }

  function submitStream(playNow) {
    var playBtn = document.getElementById('playBtn');
    var fillBtn = document.getElementById('fillBtn');
    playBtn.disabled = true;
    fillBtn.disabled = true;

    var payload = {
      url: document.getElementById('url').value.trim(),
      xForwardedFor: document.getElementById('xForwardedFor').value.trim(),
      referer: document.getElementById('referer').value.trim(),
      origin: document.getElementById('origin').value.trim(),
      drmLicense: document.getElementById('drmLicense').value.trim(),
      customHeaders: document.getElementById('customHeaders').value.trim(),
      userAgent: document.getElementById('userAgent').value,
      customUserAgent: document.getElementById('customUserAgent').value.trim(),
      drmScheme: document.getElementById('drmScheme').value,
      playNow: playNow
    };

    fetch('/api/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
    .then(function(r) { return r.json(); })
    .then(function(res) { showMsg(res.message, res.ok); })
    .catch(function(err) { showMsg('Gagal terhubung ke aplikasi: ' + err, false); })
    .finally(function() {
      playBtn.disabled = false;
      fillBtn.disabled = false;
    });
  }

  // Muat state form dari aplikasi saat halaman dibuka
  fetch('/api/state')
    .then(function(r) { return r.json(); })
    .then(function(s) {
      if (s.url) document.getElementById('url').value = s.url;
      if (s.xForwardedFor) document.getElementById('xForwardedFor').value = s.xForwardedFor;
      if (s.referer) document.getElementById('referer').value = s.referer;
      if (s.origin) document.getElementById('origin').value = s.origin;
      if (s.drmLicense) document.getElementById('drmLicense').value = s.drmLicense;
      if (s.customHeaders) document.getElementById('customHeaders').value = s.customHeaders;
      if (s.userAgent) document.getElementById('userAgent').value = s.userAgent;
      if (s.customUserAgent) document.getElementById('customUserAgent').value = s.customUserAgent;
      if (s.drmScheme) document.getElementById('drmScheme').value = s.drmScheme;
      onUaChange();
      if (s.xForwardedFor || s.referer || s.origin || s.drmLicense || s.customHeaders || (s.userAgent && s.userAgent !== 'DEFAULT') || (s.drmScheme && s.drmScheme !== 'AUTO')) {
        toggleAdvanced();
      }
    })
    .catch(function() {});
</script>
</body>
</html>
"""

