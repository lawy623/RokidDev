package com.rokid.terminal

import android.util.Base64
import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.BufferedInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.Executors

class SshTerminalSession(
    private val onState: (String) -> Unit,
    private val onOutput: (String) -> Unit,
    private val traceRecorder: TerminalTraceRecorder? = null,
) {
    // One worker may block while reading the PTY; the second remains available
    // for user input, reconnect, and disconnect operations.
    private val executor = Executors.newFixedThreadPool(2)
    @Volatile
    private var session: Session? = null
    @Volatile
    private var channel: ChannelShell? = null
    @Volatile
    private var input: OutputStream? = null
    @Volatile
    private var viewport = TerminalViewport.default()

    fun connect(config: EndpointProfile, identity: DeviceKeyStore.Identity, legacy: Boolean = false) {
        executor.execute {
            disconnectInternal()
            try {
                onState("CONNECTING")
                val jsch = JSch()
                JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
                try {
                    jsch.addIdentity("rokid-device", identity.privateKey, null, null)
                } finally {
                    identity.privateKey.fill(0)
                }
                jsch.hostKeyRepository = PinnedHostKeyRepository(config.host, config.port, config.knownHost)

                val newSession = jsch.getSession(config.user, config.host, config.port).apply {
                    setConfig(Properties().apply {
                        put("StrictHostKeyChecking", "yes")
                        put("PreferredAuthentications", "publickey")
                        put("server_host_key", "ssh-ed25519")
                    })
                    serverAliveInterval = 15_000
                    serverAliveCountMax = 3
                    try {
                        connect(15_000)
                    } catch (error: Exception) {
                        hostKey?.let { key ->
                            val rawKey = Base64.decode(key.key, Base64.DEFAULT)
                            val digest = MessageDigest.getInstance("SHA-256").digest(rawKey)
                            rawKey.fill(0)
                            val fingerprint = Base64.encodeToString(digest, Base64.NO_WRAP).trimEnd('=')
                            Log.e(TAG, "SSH rejected host key type=${key.type} SHA256:$fingerprint")
                        }
                        throw error
                    }
                }
                val initialViewport = viewport
                val newChannel = (newSession.openChannel("shell") as ChannelShell).apply {
                    setPtyType(
                        "xterm-256color",
                        initialViewport.columns,
                        initialViewport.rows,
                        initialViewport.pixelWidth,
                        initialViewport.pixelHeight,
                    )
                }
                val output = newChannel.outputStream
                val remoteOutput = InputStreamReader(
                    BufferedInputStream(newChannel.inputStream),
                    Charsets.UTF_8,
                )
                newChannel.connect(10_000)

                session = newSession
                channel = newChannel
                input = output
                applyViewport(newChannel, viewport)
                output.write(((if (legacy) config.legacyRemoteCommand else config.remoteCommand) + "\r").toByteArray())
                output.flush()
                onState("CONNECTED")

                val buffer = CharArray(2048)
                while (newChannel.isConnected) {
                    val count = remoteOutput.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        val value = String(buffer, 0, count)
                        traceRecorder?.recordChunk(value)
                        onOutput(value)
                    }
                }
                onState("DISCONNECTED")
            } catch (error: Exception) {
                onState("ERROR: ${error.message ?: error.javaClass.simpleName}")
                disconnectInternal()
            }
        }
    }

    /**
     * Keep the server PTY in lock-step with the local emulator grid. JSch sends
     * the SSH window-change request, which causes tmux/Claude to receive
     * SIGWINCH and redraw for the new terminal dimensions.
     */
    fun updateViewport(value: TerminalViewport) {
        viewport = value
        executor.execute {
            channel?.takeIf { it.isConnected }?.let { current ->
                try {
                    applyViewport(current, viewport)
                } catch (error: Exception) {
                    // A rejected window-change does not mean the SSH transport died.
                    // Keep the connected state intact; the next viewport change can retry.
                    Log.w(TAG, "SSH PTY resize request failed", error)
                }
            }
        }
    }

    private fun applyViewport(target: ChannelShell, value: TerminalViewport) {
        target.setPtySize(
            value.columns,
            value.rows,
            value.pixelWidth,
            value.pixelHeight,
        )
    }

    fun sendText(text: String) = send(text + "\r")
    fun sendCharacters(text: String) = send(text)
    fun sendEnter() = send("\r")
    fun sendEscape() = send("\u001b")
    fun sendArrowLeft() = send("\u001b[D")
    fun sendArrowRight() = send("\u001b[C")
    fun sendArrowUp() = send("\u001b[A")
    fun sendArrowDown() = send("\u001b[B")

    fun disconnect() {
        executor.execute {
            disconnectInternal()
            onState("DISCONNECTED")
        }
    }

    private fun send(value: String) {
        executor.execute {
            try {
                input?.apply {
                    write(value.toByteArray())
                    flush()
                }
            } catch (error: Exception) {
                onState("ERROR: ${error.message ?: "send failed"}")
            }
        }
    }

    private fun disconnectInternal() {
        try {
            channel?.disconnect()
        } catch (_: Exception) {
        }
        try {
            session?.disconnect()
        } catch (_: Exception) {
        }
        channel = null
        session = null
        input = null
    }

    companion object {
        private const val TAG = "RokidTerminal"
    }
}
