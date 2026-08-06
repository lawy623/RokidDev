package com.rokid.terminal

import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import java.util.concurrent.Executors

/**
 * ASR access over the dedicated `asr-fwd` SSH channel.
 *
 * The terminal SSH session (rokid user) cannot do port forwarding
 * (`AllowTcpForwarding no` in the server's sshd_config.d/99-rokid.conf), so the
 * glasses open a *separate* SSH connection as `asr-fwd` — an account whose
 * authorized_keys carries `permitopen="127.0.0.1:8765"` and nothing else — and
 * create a local forward (127.0.0.1:18765 -> 127.0.0.1:8765). A WAV upload over
 * that forward reaches the ASR service on the server's loopback.
 *
 * The identity is stored with the same DeviceKeyStore mechanism as the terminal
 * identity (Android Keystore AES-GCM), keyed by profile id. The host key is
 * pinned via PinnedHostKeyRepository so no trust-on-first-use is ever used.
 */
class ServerAsrClient(
    private val profile: AsrProfile,
    private val identity: DeviceKeyStore.Identity,
) {
    interface Listener {
        /** progress/state text for the composer status line */
        fun onStatus(status: String)
        /** final recognized text; called on the calling thread */
        fun onResult(text: String)
        /** failure with a short user-facing message */
        fun onError(message: String)
    }

    @Volatile
    private var session: Session? = null

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "asr-client").apply { isDaemon = true }
    }

    val isConnected: Boolean
        get() = session?.isConnected == true

    /**
     * Open the asr-fwd SSH connection and create the local forward. Safe to
     * call repeatedly; a session already connected is left untouched.
     */
    fun connect(listener: Listener?) {
        executor.execute {
            Log.i(TAG, "ASR client connect task start")
            if (isConnected) return@execute
            try {
                Log.i(TAG, "ASR client connect: calling onStatus(CONNECTING)")
                listener?.onStatus("ASR CONNECTING")
                val jsch = JSch()
                JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
                try {
                    jsch.addIdentity("asr-device", identity.privateKey, null, null)
                } finally {
                    identity.privateKey.fill(0)
                }
                jsch.hostKeyRepository = PinnedHostKeyRepository(profile.host, profile.port, profile.knownHost)

                val newSession = jsch.getSession(profile.user, profile.host, profile.port).apply {
                    setConfig(Properties().apply {
                        put("StrictHostKeyChecking", "yes")
                        put("PreferredAuthentications", "publickey")
                        put("server_host_key", "ssh-ed25519")
                    })
                    serverAliveInterval = 15_000
                    serverAliveCountMax = 3
                    connect(15_000)
                }
                newSession.setPortForwardingL(
                    profile.localForwardPort,
                    profile.remoteForwardTarget,
                    profile.remoteForwardPort,
                )
                session = newSession
                listener?.onStatus("ASR READY")
            } catch (error: Exception) {
                session?.disconnect()
                session = null
                listener?.onError("ASR CONNECT FAIL: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    /**
     * Trigger the server's lazy model load so the first real transcription is
     * fast instead of paying the ~30 s cold load. Returns true when the server
     * answered (model loaded); false when the channel is down or the request
     * failed (caller may retry).
     */
    fun warmup(): Boolean {
        return try {
            // The first request also pays the server's lazy funasr/torch
            // import (~60 s) plus the model load (~30 s), so the read timeout
            // must cover the whole warm-up, not just one transcription.
            transcribe(silentWav(), readTimeoutMs = WARMUP_READ_TIMEOUT_MS)
            true
        } catch (error: Exception) {
            Log.w(TAG, "ASR warmup failed: ${error.message ?: error.javaClass.simpleName}")
            false
        }
    }

    /**
     * Upload a 16 kHz mono signed-16-bit PCM WAV and return the recognized
     * text. Runs on the caller's thread; the server is CPU-bound so a short
     * blocking call is acceptable, but callers should avoid the UI thread.
     */
    fun transcribe(wav: ByteArray, language: String = "auto", readTimeoutMs: Int = 60_000): String {
        val target = session ?: throw IOException("ASR not connected")
        if (!target.isConnected) throw IOException("ASR connection lost")
        val url = URL(profile.baseUrl + "/v1/transcribe")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")

            DataOutputStream(connection.outputStream).use { out ->
                out.writeBytes("--$BOUNDARY\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"utterance.wav\"\r\n")
                out.writeBytes("Content-Type: audio/wav\r\n\r\n")
                out.write(wav)
                out.writeBytes("\r\n--$BOUNDARY--\r\n")
                out.flush()
            }

            val code = connection.responseCode
            if (code != 200) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("ASR HTTP $code: ${body.take(200)}")
            }
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            return parseText(json)
        } finally {
            connection.disconnect()
        }
    }

    fun disconnect() {
        executor.execute {
            try {
                session?.disconnect()
            } catch (_: Exception) {
            }
            session = null
        }
    }

    fun destroy() {
        disconnect()
        executor.shutdown()
    }

    /** Extract the `text` field from the ASR JSON response, JSON-unescaping it. */
    private fun parseText(json: String): String {
        val regex = Regex("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        val match = regex.find(json) ?: throw IOException("ASR response missing text")
        val raw = match.groupValues[1]
        val result = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (char == '\\' && index + 1 < raw.length) {
                val next = raw[index + 1]
                when (next) {
                    '"' -> { result.append('"'); index += 2 }
                    '\\' -> { result.append('\\'); index += 2 }
                    'n' -> { result.append('\n'); index += 2 }
                    'r' -> { result.append('\r'); index += 2 }
                    't' -> { result.append('\t'); index += 2 }
                    'u' -> {
                        if (index + 5 < raw.length) {
                            val hex = raw.substring(index + 2, index + 6)
                            result.append(hex.toInt(16).toChar())
                            index += 6
                        } else {
                            result.append('u'); index += 2
                        }
                    }
                    else -> { result.append(char); index += 1 }
                }
            } else {
                result.append(char)
                index += 1
            }
        }
        return result.toString()
    }

    companion object {
        private const val TAG = "RokidTerminal"
        private const val BOUNDARY = "----RokidAsrBoundary7MDp"
        private const val WARMUP_READ_TIMEOUT_MS = 180_000
    }
}
