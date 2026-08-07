package com.rokid.terminal

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.util.Properties

/**
 * Fetches the remote Claude slash-command list for the command palette
 * (contract: structured data only — never scraped terminal pixels). Opens a
 * short-lived SSH exec channel as the endpoint's terminal user, runs the
 * server helper `COMMAND_HELPER`, and parses its output as one command per
 * line (`#` comments and blank lines ignored). One-shot; returns null on
 * any failure so the caller falls back to the local default list.
 *
 * The helper lists custom commands from the Claude command/skill
 * directories (file names are command names); built-in commands live in the
 * app's default list and are merged by the caller.
 */
class ServerCommandFetcher(
    private val endpoint: EndpointProfile,
    private val identity: DeviceKeyStore.Identity,
) {
    fun fetch(timeoutMs: Int = FETCH_TIMEOUT_MS): List<String>? {
        var session: Session? = null
        return try {
            val jsch = JSch()
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            try {
                jsch.addIdentity("palette-device", identity.privateKey, null, null)
            } finally {
                identity.privateKey.fill(0)
            }
            jsch.hostKeyRepository = PinnedHostKeyRepository(endpoint.host, endpoint.port, endpoint.knownHost)

            session = jsch.getSession(endpoint.user, endpoint.host, endpoint.port).apply {
                setConfig(Properties().apply {
                    put("StrictHostKeyChecking", "yes")
                    put("PreferredAuthentications", "publickey")
                    put("server_host_key", "ssh-ed25519")
                })
                serverAliveInterval = 15_000
                serverAliveCountMax = 3
                connect(15_000)
            }
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(COMMAND_HELPER)
            val stdout = channel.inputStream
            channel.connect(5_000)
            parse(stdout, timeoutMs)
        } catch (error: Exception) {
            android.util.Log.w("RokidTerminal", "command list fetch failed: ${error.message ?: error.javaClass.simpleName}")
            null
        } finally {
            session?.disconnect()
        }
    }

    private fun parse(stdout: java.io.InputStream, timeoutMs: Int): List<String>? {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (stdout.available() > 0) {
                val count = stdout.read(buffer, 0, buffer.size)
                if (count < 0) break
                bytes.write(buffer, 0, count)
            } else {
                Thread.sleep(50)
            }
        }
        return parseLines(bytes.toString("UTF-8"))
    }

    /** One command per line; `#` comments and blanks ignored. */
    private fun parseLines(text: String): List<String>? {
        val commands = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.startsWith("/") }
            .distinct()
            .toList()
        return commands.takeIf { it.isNotEmpty() }
    }

    companion object {
        /** Server helper run on the endpoint's terminal user (see asr-server deploy docs). */
        const val COMMAND_HELPER = "/home/rokid/bin/rokid-commands 2>/dev/null || true"
        private const val FETCH_TIMEOUT_MS = 10_000
    }
}
