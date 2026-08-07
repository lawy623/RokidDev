package com.rokid.terminal

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.util.Properties

/**
 * Runs the server-side `rokid-sessions` helper (design spec 2026-08-07).
 * Same pattern as ServerCommandFetcher: short-lived exec channels as the
 * endpoint's terminal user; structured tab-separated output; never scrapes
 * terminal pixels. Parsers are pure so the protocol is unit-tested without
 * JSch. The helper's `switch` verb verifies the launch server-side (polls
 * up to ~15 s), so its timeout is longer than list/status.
 *
 * Unlike the one-shot ServerCommandFetcher, this fetcher is called many
 * times per connection (list once, status every ~30 s, switch per switch),
 * so a fresh identity is decrypted from the keystore on every run() and
 * zeroed right after JSch imports it — never cached across calls.
 */
class ServerSessionFetcher(
    private val endpoint: EndpointProfile,
    private val keyStore: DeviceKeyStore,
) {
    fun listSessions(baseDir: String): List<RemoteFolder>? =
        run("$HELPER list ${shellQuote(baseDir)}")?.let(::parseList)

    fun status(tmuxSession: String): SessionStatus? {
        val out = run("$HELPER status ${shellQuote(tmuxSession)}") ?: return null
        return parseStatus(out)
    }

    /** Returns the helper's raw output; the caller parses with [parseSwitchResult]. */
    fun switchConversation(
        tmuxSession: String,
        baseDir: String,
        folderPath: String,
        sessionId: String,
        isNew: Boolean,
    ): String? = run(
        "$HELPER switch ${shellQuote(tmuxSession)} ${shellQuote(baseDir)} " +
            "${shellQuote(folderPath)} ${if (isNew) "new:$sessionId" else "resume:$sessionId"}",
        timeoutMs = SWITCH_TIMEOUT_MS,
    )

    private fun run(command: String, timeoutMs: Int = FETCH_TIMEOUT_MS): String? {
        var session: Session? = null
        var channel: ChannelExec? = null
        return try {
            val jsch = JSch()
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            val identity = keyStore.getOrCreate()
            try {
                jsch.addIdentity("sessions-device", identity.privateKey, null, null)
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
            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val stdout = channel.inputStream
            channel.connect(5_000)
            readAll(stdout, timeoutMs)
        } catch (error: Exception) {
            android.util.Log.w("RokidTerminal", "session helper failed: ${error.message ?: error.javaClass.simpleName}")
            null
        } finally {
            runCatching { channel?.disconnect() }
            session?.disconnect()
        }
    }

    private fun readAll(stdout: java.io.InputStream, timeoutMs: Int): String? {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (stdout.available() > 0) {
                val count = stdout.read(buffer, 0, buffer.size)
                if (count < 0) return bytes.toString(Charsets.UTF_8)
                bytes.write(buffer, 0, count)
            } else {
                Thread.sleep(50)
            }
        }
        return null // timed out without EOF
    }

    companion object {
        /** Server helper run on the endpoint's terminal user. */
        const val HELPER = "/home/rokid/bin/rokid-sessions 2>/dev/null || true"
        private const val FETCH_TIMEOUT_MS = 15_000
        private const val SWITCH_TIMEOUT_MS = 25_000

        /**
         * Claude Code's project-dir encoding: every non-alphanumeric char
         * becomes '-'. Must match the helper's `tr -c 'A-Za-z0-9' '-'`.
         */
        fun encodeDir(path: String): String =
            path.map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")

        /** F lines (folders) + S lines (sessions); folder order preserved. */
        fun parseList(text: String): List<RemoteFolder> {
            val folders = LinkedHashMap<String, RemoteFolder>()
            val sessionGroups = LinkedHashMap<String, MutableList<RemoteSession>>()
            for (line in text.lineSequence()) {
                val parts = line.split('\t')
                when {
                    parts.size >= 3 && parts[0] == "F" -> {
                        val path = parts[1]
                        val encoded = parts[2]
                        if (path.isNotBlank() && encoded.isNotBlank()) {
                            folders[encoded] = RemoteFolder(path, encoded, emptyList())
                        }
                    }
                    parts.size >= 5 && parts[0] == "S" -> {
                        val session = RemoteSession(
                            id = parts[2],
                            title = parts[4],
                            epochMillis = parts[3].toLongOrNull() ?: 0L,
                        )
                        sessionGroups.getOrPut(parts[1]) { mutableListOf() }.add(session)
                    }
                }
            }
            return folders.map { (encoded, folder) -> folder.copy(sessions = sessionGroups[encoded] ?: emptyList()) }
        }

        fun parseStatus(text: String): SessionStatus? {
            val line = text.lineSequence().firstOrNull { it.startsWith("pid\t") } ?: return null
            val parts = line.split('\t')
            if (parts.size < 3) return null
            return SessionStatus(
                pid = parts[1],
                cwd = parts[2],
                sessionId = parts.getOrNull(3)?.takeIf { it != "-" },
            )
        }

        /** (encodedDir, sessionId) from an `ok\t…` line; null when the switch failed. */
        fun parseSwitchResult(text: String): Pair<String, String>? {
            val line = text.lineSequence().firstOrNull { it.startsWith("ok\t") } ?: return null
            val parts = line.split('\t')
            if (parts.size < 3) return null
            return parts[1] to parts[2]
        }

        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
