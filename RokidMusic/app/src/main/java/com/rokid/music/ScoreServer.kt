package com.rokid.music

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket

class ScoreServer(private val context: Context, private val onScoreChanged: () -> Unit) {

    private var server: ServerSocket? = null
    private var running = false
    private var wifiConnected = false
    private var currentIp: String? = null
    var error: String? = null
        private set

    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                wifiConnected = true
                error = null
                currentIp = getLocalIp()
                Log.d("ScoreServer", "WiFi up, IP: $currentIp")
                restartServer()
            }
        }

        override fun onLost(network: Network) {
            wifiConnected = false
            currentIp = null
            Log.d("ScoreServer", "WiFi lost")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                wifiConnected = true
                error = null
                val ip = getLocalIp()
                if (ip != currentIp) {
                    currentIp = ip
                    restartServer()
                }
            }
        }
    }

    fun infoLine(): String {
        if (error != null) return "Score Manager Error"
        val ip = getLocalIp() ?: return "WiFi not connected"
        return "Connect to the same WiFi  |  http://$ip:8849"
    }

    fun start() {
        // Register WiFi state listener
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm?.registerNetworkCallback(request, connectivityCallback)
        } catch (_: Exception) {}

        // Initial attempt — may retry when WiFi comes up
        currentIp = getLocalIp()
        wifiConnected = currentIp != null
        if (!wifiConnected) {
            // Connectivity callback will set wifiConnected when WiFi is ready
            Log.d("ScoreServer", "No WiFi IP yet, waiting for callback")
        }
        restartServer()
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(connectivityCallback)
        } catch (_: Exception) {}
    }

    private fun restartServer() {
        try { server?.close() } catch (_: Exception) {}
        running = false
        Thread {
            try {
                server = ServerSocket(8849)
                running = true
                error = null
                // Refresh IP and WiFi state when server successfully binds
                currentIp = getLocalIp()
                wifiConnected = currentIp != null
                Log.d("ScoreServer", "Server on ${currentIp}:8849")
                while (running) {
                    val client = server?.accept() ?: break
                    Thread { handleClient(client) }.start()
                }
            } catch (e: Exception) {
                if (running) {  // Only set error if not intentionally stopped
                    Log.e("ScoreServer", "Server error", e)
                    error = e.message ?: "unknown"
                }
                running = false
            }
        }.start()
    }

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("score_deleted", Context.MODE_PRIVATE)

    private fun isDeleted(name: String) = prefs.getBoolean(name, false)
    private fun markDeleted(name: String) = prefs.edit().putBoolean(name, true).apply()
    private fun clearDeleted(name: String) = prefs.edit().remove(name).apply()

    private fun getScoresDir(): File {
        val dir = context.getExternalFilesDir("scores") ?: File(context.filesDir, "scores")
        dir.mkdirs()
        return dir
    }

    private fun listScores(): List<ScoreInfo> {
        val result = mutableListOf<ScoreInfo>()
        // Uploaded scores are the sole source of truth.  Do not use the APK's
        // bundled index, so what the player shows always matches the upload.
        getScoresDir().listFiles()
            ?.filter { it.name.endsWith(".tab.json") }
            ?.forEach { f ->
                try {
                    val j = org.json.JSONObject(f.readText())
                    val metadata = j.optJSONObject("metadata")
                    val title = metadata?.optString("title", "")?.trim().orEmpty()
                    val artist = metadata?.optString("artist", "")?.trim().orEmpty()
                    result.add(ScoreInfo(if (title.isEmpty()) "Untitled" else title, artist, f.name))
                } catch (e: Exception) {
                    Log.w("ScoreServer", "Skipping invalid uploaded score: ${f.name}", e)
                }
            }
        return result.sortedBy { it.title }
    }

    data class ScoreInfo(val title: String, val artist: String, val fileName: String)

    private fun handleClient(socket: java.net.Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val requestLine = readHttpLine(input) ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrElse(0) { "GET" }
            val path = parts.getOrElse(1) { "/" }

            var contentLength = 0
            var contentType = ""
            var line = readHttpLine(input)
            while (!line.isNullOrEmpty()) {
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val name = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    when (name) {
                        "content-length" -> contentLength = value.toIntOrNull() ?: 0
                        "content-type" -> contentType = value
                    }
                }
                line = readHttpLine(input)
            }

            when {
                method == "POST" && path == "/upload" && contentLength > 0 -> {
                    val ok = handleUpload(input, contentLength, contentType)
                    if (ok) {
                        sendJson(output, 200, """{"ok":true}""")
                        onScoreChanged()
                    } else {
                        sendJson(output, 400, """{"ok":false,"error":"invalid file"}""")
                    }
                }
                method == "POST" && path.startsWith("/delete-all") -> {
                    // Delete all uploaded files.
                    getScoresDir().listFiles()?.forEach { if (it.name.endsWith(".tab.json")) it.delete() }
                    sendJson(output, 200, """{"ok":true}""")
                    Log.d("ScoreServer", "Deleted all scores")
                    try { onScoreChanged() } catch (_: Exception) {}
                }
                method == "POST" && path.startsWith("/delete") -> {
                    val query = path.substringAfter("?", "")
                    val rawFileName = query.removePrefix("file=")
                    val fileName = java.net.URLDecoder.decode(rawFileName, "UTF-8")
                    Log.d("ScoreServer", "Delete request for: $fileName")
                    if (fileName.endsWith(".tab.json")) {
                        val file = File(getScoresDir(), fileName)
                        if (file.exists()) file.delete()
                        sendJson(output, 200, """{"ok":true}""")
                        Log.d("ScoreServer", "Deleted: $fileName")
                        try { onScoreChanged() } catch (_: Exception) {}
                    } else {
                        sendJson(output, 400, """{"ok":false,"error":"invalid"}""")
                    }
                }
                else -> sendResponse(output, 200, pageHtml())
            }
        } catch (e: Exception) {
            Log.e("ScoreServer", "Client error", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleUpload(input: InputStream, contentLength: Int, contentType: String): Boolean {
        val body = readExactly(input, contentLength) ?: return false
        val bodyStr = body.toString(Charsets.UTF_8)

        val boundary = Regex("boundary=(?:\\\"([^\\\"]+)\\\"|([^;\\s]+))", RegexOption.IGNORE_CASE)
            .find(contentType)
            ?.let { it.groups[1]?.value ?: it.groups[2]?.value }
            ?: return false

        val filenameMatch = Regex("filename=\"([^\"]+)\"").find(bodyStr)
        val filename = filenameMatch?.groupValues?.getOrNull(1)?.substringAfterLast('/')?.substringAfterLast('\\')
            ?: return false
        if (!filename.endsWith(".tab.json")) return false

        val contentStart = bodyStr.indexOf("\r\n\r\n")
        if (contentStart < 0) return false
        val dataStart = contentStart + 4
        val boundaryEnd = bodyStr.indexOf("\r\n--$boundary", dataStart)
        if (boundaryEnd < dataStart) return false
        val content = bodyStr.substring(dataStart, boundaryEnd)

        // Reject partial or malformed uploads before they are ever persisted.
        try { org.json.JSONObject(content) } catch (_: Exception) { return false }

        // Auto-rename if duplicate: "song.tab.json" → "song (1).tab.json"
        var file = File(getScoresDir(), filename)
        if (file.exists()) {
            val base = filename.removeSuffix(".tab.json")
            var n = 1
            while (file.exists()) {
                file = File(getScoresDir(), "$base($n).tab.json")
                n++
            }
        }
        file.writeText(content)
        clearDeleted(file.name)
        Log.d("ScoreServer", "Saved: ${file.name} (${content.length} chars)")
        return true
    }

    private fun readHttpLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next == -1) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
            if (next == '\n'.code) {
                val line = bytes.toByteArray()
                val length = if (line.lastOrNull() == '\r'.code.toByte()) line.size - 1 else line.size
                return String(line, 0, length, Charsets.ISO_8859_1)
            }
            bytes.write(next)
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray? {
        if (length <= 0 || length > 16 * 1024 * 1024) return null
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) return null
            offset += count
        }
        return body
    }

    private fun sendResponse(output: java.io.OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray()
        val header = "HTTP/1.1 $code OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.write(bytes)
    }

    private fun sendJson(output: java.io.OutputStream, code: Int, json: String) {
        val bytes = json.toByteArray()
        val header = "HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.write(bytes)
    }

    private fun pageHtml(): String {
        val scores = listScores()
        val rows = scores.joinToString("\n") { s ->
            val label = if (s.artist.isNotEmpty()) "${s.title} — ${s.artist}" else s.title
            val escName = escapeHtml(s.fileName); val escLabel = escapeHtml(label).replace("'", "\\'")
            """<tr><td class="name">${escLabel}</td><td><button class="btn-del" onclick="showModal('${escName}','${escLabel}')">Del</button></td></tr>"""
        }

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Guitar Score Manager</title>
<style>
  * { box-sizing: border-box; }
  body { margin: 0; padding: 24px; background: #000; color: #40FF5E; font-family: ui-monospace, monospace; display: flex; justify-content: center; }
  .wrap { max-width: 440px; width: 100%; }
  h1 { font-size: 20px; text-align: center; margin-bottom: 6px; }
  .sub { font-size: 12px; opacity: 0.72; text-align: center; margin-bottom: 20px; }
  .box { border: 1.5px solid #40FF5E; background: #001B08; padding: 16px 18px; margin-bottom: 16px; text-align: center; }
  .box h2 { font-size: 14px; margin: 0 0 10px 0; text-align: left; }
  .up-row { display: flex; align-items: center; justify-content: center; gap: 10px; }
  input[type=file] { color: #40FF5E; font-family: inherit; flex: 1; max-width: 260px; }
  input[type=file]::file-selector-button { background: #001B08; color: #40FF5E; border: 1px solid #40FF5E; padding: 5px 14px; font-family: inherit; cursor: pointer; }
  .btn { background: #40FF5E; color: #000; border: none; padding: 7px 20px; font-size: 13px; font-family: inherit; font-weight: bold; cursor: pointer; flex-shrink: 0; }
  .btn-del { background: transparent; color: #FF5555; border: 1px solid #FF5555; padding: 4px 12px; font-size: 11px; font-family: inherit; cursor: pointer; }
  .tag { color: #40FF5E; opacity: 0.5; font-size: 11px; }
  .scroll { max-height: 180px; overflow-y: auto; }
  .scroll::-webkit-scrollbar { width: 5px; }
  .scroll::-webkit-scrollbar-track { background: #001B08; }
  .scroll::-webkit-scrollbar-thumb { background: #40FF5E; border-radius: 2px; }
  .scroll::-webkit-scrollbar-thumb:hover { background: #00FF44; }
  table { width: 100%; border-collapse: collapse; text-align: left; }
  td { padding: 8px 6px; border-bottom: 1px solid rgba(64,255,94,0.15); font-size: 13px; }
  td:last-child { text-align: center; width: 50px; }
  td.name { word-break: break-all; }
  .msg { font-size: 12px; margin-top: 8px; min-height: 16px; }
  .empty { text-align: center; opacity: 0.5; padding: 12px; font-size: 13px; }
  .note { font-size: 10px; opacity: 0.5; text-align: center; margin-top: 16px; }
  .modal-bg { display: none; position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.85); z-index: 100; justify-content: center; align-items: center; }
  .modal-bg.show { display: flex; }
  .modal-box { border: 1.5px solid #40FF5E; background: #001B08; padding: 20px 24px; text-align: center; max-width: 300px; }
  .modal-box p { margin: 0 0 16px 0; font-size: 14px; }
  .modal-btns { display: flex; gap: 12px; justify-content: center; }
  .btn-cancel { background: transparent; color: #40FF5E; border: 1px solid #40FF5E; padding: 6px 18px; font-size: 13px; font-family: inherit; cursor: pointer; }
  .btn-ok { background: #40FF5E; color: #000; border: none; padding: 6px 18px; font-size: 13px; font-family: inherit; font-weight: bold; cursor: pointer; }
</style>
</head>
<body>
<div class="wrap">
  <h1>Guitar Score Manager</h1>
  <div class="sub">Upload · View · Delete · Sync to Glass</div>

  <div class="box">
    <h2>+ Upload</h2>
    <form id="upForm" onsubmit="upload(event)">
      <div class="up-row">
        <input type="file" id="fileInput" name="score" accept=".tab.json,.json" multiple required>
        <button class="btn" type="submit">Upload</button>
      </div>
      <div class="msg" id="uploadMsg"></div>
    </form>
  </div>

  <div class="box">
    <div style="display:flex;justify-content:space-between;align-items:center">
      <h2 style="margin:0">Stored Scores (${scores.size})</h2>
      ${if (scores.isEmpty()) "" else "<button class='btn-del' onclick=\"showModal('__ALL__','ALL scores')\">Delete All</button>"}
    </div>
    ${if (scores.isEmpty()) "<div class='empty' style='margin-top:10px'>No scores yet</div>" else "<div class='scroll' style='margin-top:8px'><table>$rows</table></div>"}
  </div>

  <div class="note">Same WiFi required · Only .tab.json format</div>

  <div class="modal-bg" id="modal">
    <div class="modal-box">
      <p id="modalMsg"></p>
      <div class="modal-btns">
        <button class="btn-cancel" onclick="closeModal()">Cancel</button>
        <button class="btn-ok" id="modalOk">Delete</button>
      </div>
    </div>
  </div>
</div>

<script>
let delTarget = '';

function showModal(file, label) {
  delTarget = file;
  document.getElementById('modalMsg').textContent = 'Delete "' + label + '"?';
  document.getElementById('modal').classList.add('show');
}

function closeModal() {
  document.getElementById('modal').classList.remove('show');
  delTarget = '';
}

async function doDelete() {
  const target = delTarget;
  closeModal();
  try {
    const url = target === '__ALL__' ? '/delete-all' : '/delete?file=' + encodeURIComponent(target);
    const r = await fetch(url, { method: 'POST' });
    if (r.ok) location.href = location.href.split('#')[0] + '?_=' + Date.now();
  } catch(_) {}
}
document.getElementById('modalOk').addEventListener('click', doDelete);

async function upload(e) {
  e.preventDefault();
  const files = document.getElementById('fileInput').files;
  if (!files.length) return;
  const msg = document.getElementById('uploadMsg');
  let ok = 0;
  for (const file of files) {
    msg.textContent = 'Uploading ' + file.name + '...';
    const form = new FormData();
    form.append('score', file);
    try {
      const r = await fetch('/upload', { method: 'POST', body: form });
      if (r.ok) ok++;
    } catch(_) {}
  }
  if (ok === files.length) msg.textContent = 'All ' + ok + ' OK! Reloading...';
  else msg.textContent = ok + '/' + files.length + ' OK. Reloading...';
  setTimeout(() => { location.href = location.href.split('#')[0] + '?_=' + Date.now(); }, 500);
}
</script>
</body>
</html>
        """.trimIndent()
    }

    private fun escapeHtml(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        fun getLocalIp(): String? {
            try {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                    if (iface.isLoopback || !iface.isUp) return@forEach
                    iface.inetAddresses.toList().forEach { addr ->
                        if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress
                    }
                }
            } catch (_: Exception) {}
            return null
        }
    }
}
