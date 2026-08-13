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

    /** Deletes a conversation's transcript on the server (irrecoverable). */
    fun deleteConversation(
        tmuxSession: String,
        baseDir: String,
        folderPath: String,
        sessionId: String,
    ): String? = run(
        "$HELPER delete ${shellQuote(tmuxSession)} ${shellQuote(baseDir)} " +
            "${shellQuote(folderPath)} ${shellQuote(sessionId)}",
    )

    /**
     * Exports a conversation's transcript as plain text rows (user messages
     * with a ❯ prefix, assistant text plain, tool results skipped) so the
     * app can rebuild local scrollback for a resumed conversation.
     */
    fun exportConversation(baseDir: String, folderPath: String, sessionId: String): String? = run(
        "$HELPER export ${shellQuote(baseDir)} ${shellQuote(folderPath)} ${shellQuote(sessionId)}",
    )

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

    /**
     * Renames the ACTIVE window to the conversation's real id after new-chat
     * discover convergence (the server may have ignored --session-id;
     * design 2026-08-11 §3.3). Best-effort: the caller logs, never blocks.
     */
    fun adoptConversation(
        tmuxSession: String,
        folderPath: String,
        newSessionId: String,
    ): String? = run(
        "$HELPER adopt ${shellQuote(tmuxSession)} ${shellQuote(folderPath)} ${shellQuote(newSessionId)}",
    )

    /**
     * Ends idle background conversations server-side (design 2026-08-11
     * §3.6). Takes minutes (CPU sampling); the caller runs it on its own
     * thread. Returns the helper's raw output; parse with [parseSweepResult].
     */
    fun sweepIdle(tmuxSession: String, baseDir: String): String? = run(
        "$HELPER sweep ${shellQuote(tmuxSession)} ${shellQuote(baseDir)}",
        timeoutMs = SWEEP_TIMEOUT_MS,
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
            val out = readAll(stdout, timeoutMs)
            // Diagnostic (length + exit only; never log helper content):
            // on-device "empty list" investigation 2026-08-07.
            android.util.Log.i(
                "RokidTerminal",
                "session helper: len=${out?.length ?: -1} " +
                    "exit=${runCatching { channel.exitStatus }.getOrDefault(-999)}",
            )
            out
        } catch (error: Exception) {
            android.util.Log.w("RokidTerminal", "session helper failed: ${error.message ?: error.javaClass.simpleName}")
            null
        } finally {
            runCatching { channel?.disconnect() }
            session?.disconnect()
        }
    }

    /**
     * Reads until EOF, a quiet period, or the deadline. On this device the
     * JSch exec channel's EOF does NOT arrive (the remote command exits 0,
     * its output is delivered via available/read, but EOF never comes) — so
     * waiting for EOF alone burns the whole timeout, and discarding on
     * timeout threw away real data (fixed 2026-08-07, on-device evidence:
     * len=-1 exit=0). Return once the stream has been quiet for QUIET_MS.
     * Only transport exceptions yield null (handled in run()).
     */
    private fun readAll(stdout: java.io.InputStream, timeoutMs: Int): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        val deadline = System.currentTimeMillis() + timeoutMs
        var quietSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            if (stdout.available() > 0) {
                val count = stdout.read(buffer, 0, buffer.size)
                if (count < 0) break
                bytes.write(buffer, 0, count)
                quietSince = System.currentTimeMillis()
            } else {
                if (System.currentTimeMillis() - quietSince > QUIET_MS) break
                Thread.sleep(50)
            }
        }
        // toString(Charset) is API 33+ and missing on this Rokid firmware
        // (NoSuchMethodError — an Error, not an Exception, so it crashed the
        // fetch thread; fixed 2026-08-07). Use the legacy overload.
        return bytes.toString("UTF-8")
    }

    companion object {
        /**
         * Server helper run on the endpoint's terminal user. NOTE: no
         * `|| true` here — the app appends the verb AFTER this constant
         * (`$HELPER list ...`); a `|| true` in the middle swallows the verb
         * (the shell runs the helper with no args and `true` wins). Fixed
         * 2026-08-07 after on-device discovery: every verb invocation
         * failed, so the picker only ever showed the /srv fallback.
         */
        const val HELPER = "/home/rokid/bin/rokid-sessions 2>/dev/null"
        private const val FETCH_TIMEOUT_MS = 15_000
        private const val SWITCH_TIMEOUT_MS = 25_000
        /** The sweep's CPU sampling alone takes ~2 min per run. */
        private const val SWEEP_TIMEOUT_MS = 180_000
        private const val QUIET_MS = 750L

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

        /** Count from a `swept\t<count>` line; null when the sweep failed. */
        fun parseSweepResult(text: String): Int? {
            val line = text.lineSequence().firstOrNull { it.startsWith("swept\t") } ?: return null
            return line.substringAfter('\t').toIntOrNull()
        }

        /**
         * The session whose transcript file APPEARED after a new-chat
         * switch: the first session whose id is neither in the pre-switch
         * baseline nor the app's placeholder id. Never converges to a
         * pre-existing old conversation (bug 2026-08-13: "newest other
         * session" reused arbitrary old conversations' ids/history for new
         * chats). Null when no such session exists yet.
         */
        fun firstNewSession(folder: RemoteFolder, baseline: Set<String>, tempId: String): RemoteSession? =
            folder.sessions.firstOrNull { it.id !in baseline && it.id != tempId }

        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
