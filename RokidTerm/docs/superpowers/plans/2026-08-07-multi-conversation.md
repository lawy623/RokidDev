# Multi-Conversation Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let RokidTerm pick a project folder and a past Claude Code conversation at connect time or mid-session, switch via a server helper, and key all scrollback persistence per conversation.

**Architecture:** A server-side `rokid-sessions` helper (file enumeration of `~/.claude/projects/*/`, tmux `new-session`/`respawn-pane` launch, `status` verification) exposes list/status/switch verbs over SSH exec. The app shows a two-level local picker (folders → conversations), reusing the command-palette interaction contract. Scrollback files become `scrollback_<endpoint>_<folderKey>_<sessionId>.txt` with LRU pruning; a 30 s sync watcher reconciles any out-of-band session change (manual `/resume`, `/cd`).

**Tech Stack:** Kotlin (pure JVM state + Android), JSch exec channels (pattern: `ServerCommandFetcher`), bash helper (pattern: `server/rokid-commands`), tmux, JUnit4.

**Design spec:** `RokidTerm/docs/superpowers/specs/2026-08-07-multi-conversation-design.md` (approved 2026-08-07).

## Global Constraints

- The endpoint's existing `workspace` field is the base dir (default `/srv/projects`; the user's live endpoint sets it to `/srv`). No profile schema change.
- The remote launcher stays the fixed `/home/rokid/bin/rokid-claude`; the ONLY new args are whitelisted flags with server-derived IDs: `--resume <id>` and `--session-id <uuid>`.
- `rokid-sessions` runs as the unprivileged `rokid` user; it only reads `~/.claude/projects/*/` and lists the base dir — file enumeration, never pixel scraping; never prints message bodies.
- The helper MUST validate every value embedded in the tmux command (session id / uuid charset, dir under base) — command injection guard.
- Never log conversation titles, session IDs, or terminal content to logcat (existing invariant).
- Scrollback stays in app-private `filesDir`; per-conversation files ≤1000 rows each, ≤30 files per endpoint (LRU by mtime).
- Verify server support before hardware testing: `claude --version` must accept `--resume <id>` and `--session-id <uuid>` (v2.1.223+ covers both; v2.1.223 adds cross-project `--resume` search, not needed here since the helper `cd`s first).
- All unit tests run with: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; export ANDROID_HOME="$HOME/Library/Android/sdk"; ./gradlew testDebugUnitTest assembleDebug` (from `RokidTerm/`).
- Commit after every task; message style follows repo history (`feat:`, `fix:`, `docs:`).

---

### Task 1: SessionModels + SessionPickerState (pure JVM)

**Files:**
- Create: `app/src/main/java/com/rokid/terminal/SessionModels.kt`
- Create: `app/src/main/java/com/rokid/terminal/SessionPickerState.kt`
- Test: `app/src/test/java/com/rokid/terminal/SessionPickerStateTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin).
- Produces:
  - `data class RemoteSession(id: String, title: String, epochMillis: Long)`
  - `data class RemoteFolder(path: String, encodedDir: String, sessions: List<RemoteSession>)`
  - `data class SessionTarget(folderPath: String, sessionId: String?)` — `sessionId == null` means a new conversation
  - `class SessionPickerState` with: `folders`, `open`, `loading`, `error`, `level`, `folderIndex`, `sessionIndex`, `currentFolderPath`, `currentSessionId` (all read-only from outside), plus `conversationCount: Int`, `setFolders(value: List<RemoteFolder>, failed: Boolean)`, `open(preferredFolderPath: String?, preferredSessionId: String?)`, `close()`, `move(delta: Int)`, `back(): Boolean`, `selectedFolder(): RemoteFolder?`, `confirm(): SessionTarget?`, `markCurrent(folderPath: String?, sessionId: String?)`

- [ ] **Step 1: Write the failing test**

`SessionPickerStateTest.kt`:

```kotlin
package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPickerStateTest {
    private val folderA = RemoteFolder("/srv", "-srv", listOf(
        RemoteSession("id-1", "first chat", 1_700_000_000_000L),
        RemoteSession("id-2", "second chat", 1_700_000_100_000L),
    ))
    private val folderB = RemoteFolder("/srv/RokidDev", "-srv-RokidDev", emptyList())

    @Test
    fun openStartsAtFolderLevelWithPreferredMarkers() {
        val picker = SessionPickerState()
        picker.setFolders(listOf(folderA, folderB), failed = false)

        picker.open("/srv", "id-2")

        assertTrue(picker.open)
        assertEquals(0, picker.level)
        assertEquals(0, picker.folderIndex)
        assertEquals("/srv", picker.currentFolderPath)
        assertEquals("id-2", picker.currentSessionId)
        assertTrue(picker.loading)
        assertEquals(3, picker.conversationCount) // new-slot + 2 sessions
    }

    @Test
    fun moveWrapsWithinFolderLevel() {
        val picker = SessionPickerState().apply {
            open(null, null)
            setFolders(listOf(folderA, folderB), failed = false)
        }

        picker.move(-1)
        assertEquals(1, picker.folderIndex)
        picker.move(1)
        assertEquals(0, picker.folderIndex)
    }

    @Test
    fun confirmOnFolderLevelDescendsToConversations() {
        val picker = SessionPickerState().apply {
            open(null, null)
            setFolders(listOf(folderA, folderB), failed = false)
        }

        assertNull(picker.confirm()) // descends, returns null
        assertEquals(1, picker.level)
        assertEquals(0, picker.sessionIndex)
        assertEquals(3, picker.conversationCount) // new-slot + 2 sessions
    }

    @Test
    fun confirmOnConversationLevelReturnsTarget() {
        val picker = SessionPickerState().apply {
            open(null, null)
            setFolders(listOf(folderA), failed = false)
        }
        picker.confirm() // descend
        picker.move(2)   // wrap: 0 -> 1 -> 2 (the second session)

        assertEquals(SessionTarget("/srv", "id-2"), picker.confirm())
        assertTrue(picker.open) // confirm does not close; the app closes it
    }

    @Test
    fun newConversationSlotYieldsNullSessionId() {
        val picker = SessionPickerState().apply {
            open(null, null)
            setFolders(listOf(folderA), failed = false)
        }
        picker.confirm() // descend, sessionIndex = 0 = new slot

        assertEquals(SessionTarget("/srv", null), picker.confirm())
    }

    @Test
    fun backMovesUpOneLevelThenCloses() {
        val picker = SessionPickerState().apply {
            open(null, null)
            setFolders(listOf(folderA, folderB), failed = false)
        }
        picker.confirm() // descend

        assertTrue(picker.back())
        assertEquals(0, picker.level)
        assertFalse(picker.back()) // level 0: back() returns false, state stays open
        assertTrue(picker.open)
    }

    @Test
    fun moveIsBlockedWhileLoading() {
        val picker = SessionPickerState().apply {
            setFolders(listOf(folderA, folderB), failed = false)
            open(null, null)
        }

        picker.move(1) // loading == true after open()
        assertEquals(0, picker.folderIndex)
    }

    @Test
    fun emptyFoldersAreSafe() {
        val picker = SessionPickerState().apply {
            setFolders(emptyList(), failed = true)
            open(null, null)
        }

        assertTrue(picker.error)
        picker.move(1)
        assertEquals(0, picker.folderIndex)
        picker.confirm() // level 0 with no folders: no-op, stays at level 0
        assertEquals(0, picker.level)
        assertEquals(1, picker.conversationCount)
    }

    @Test
    fun setFoldersReappliedAfterOpenKeepsLevelsUsable() {
        val picker = SessionPickerState().apply {
            open(null, null)
            setFolders(listOf(folderA), failed = false)
        }
        picker.confirm()
        picker.move(1)
        picker.setFolders(listOf(folderA, folderB), failed = false)

        assertEquals(0, picker.level)
        assertEquals(0, picker.folderIndex)
        assertFalse(picker.loading)
    }
}
```

Note: `confirm()` at level 1 returns the target but does NOT close the state — the app closes it (`close()`) after launching the switch. Adjust the `confirmOnConversationLevelReturnsTarget` test accordingly: assert the state is still open.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.rokid.terminal.SessionPickerStateTest" -v`
Expected: FAIL — `SessionPickerState` not found.

- [ ] **Step 3: Implement the state**

`SessionModels.kt`:

```kotlin
package com.rokid.terminal

/** One remote Claude session (id = JSONL filename; title = first user message). */
data class RemoteSession(val id: String, val title: String, val epochMillis: Long)

/** A selectable folder (project) with its sessions (design 2026-08-07). */
data class RemoteFolder(val path: String, val encodedDir: String, val sessions: List<RemoteSession>)

/** What the user confirmed: a folder plus an optional resume id (null = new conversation). */
data class SessionTarget(val folderPath: String, val sessionId: String?)

/** `rokid-sessions status` output (pid is informational). */
data class SessionStatus(val pid: String, val cwd: String?, val sessionId: String?)
```

`SessionPickerState.kt`:

```kotlin
package com.rokid.terminal

/**
 * Two-level conversation picker state (design:
 * docs/superpowers/specs/2026-08-07-multi-conversation-design.md). Pure JVM
 * so navigation logic is unit-testable. Level 0 = folders; level 1 = a
 * "＋ 新对话" slot at index 0 followed by the selected folder's sessions.
 * Modal: while open, directional gestures navigate and confirm/cancel decide.
 */
class SessionPickerState {

    var folders: List<RemoteFolder> = emptyList()
        private set
    var open: Boolean = false
        private set
    var loading: Boolean = false
        private set
    var error: Boolean = false
        private set
    var level: Int = 0
        private set
    var folderIndex: Int = 0
        private set
    var sessionIndex: Int = 0
        private set
    var currentFolderPath: String? = null
        private set
    var currentSessionId: String? = null
        private set

    /** Level-1 list length: the new-conversation slot plus the folder's sessions. */
    val conversationCount: Int
        get() = (selectedFolder()?.sessions?.size ?: 0) + 1

    /** Applies the fetched list; resets navigation to the folder level. */
    fun setFolders(value: List<RemoteFolder>, failed: Boolean) {
        folders = value
        error = failed
        loading = false
        level = 0
        folderIndex = 0
        sessionIndex = 0
    }

    /** Opens with the remembered folder/session as the ▶ markers; loading until setFolders. */
    fun open(preferredFolderPath: String?, preferredSessionId: String?) {
        currentFolderPath = preferredFolderPath
        currentSessionId = preferredSessionId
        open = true
        loading = true
        // error deliberately NOT reset: a previous fetch failure stays
        // visible until the next setFolders reports a result.
        level = 0
        folderIndex = 0
        sessionIndex = 0
    }

    fun close() {
        open = false
        loading = false
    }

    /** Moves the selection with wrap-around within the current level; no-op while loading/empty. */
    fun move(delta: Int) {
        if (!open || loading) return
        if (level == 0) {
            if (folders.isEmpty()) return
            folderIndex = ((folderIndex + delta) % folders.size + folders.size) % folders.size
        } else {
            sessionIndex = ((sessionIndex + delta) % conversationCount + conversationCount) % conversationCount
        }
    }

    /** Level 1 -> level 0 (true); level 0 -> stays open, returns false (caller closes). */
    fun back(): Boolean {
        if (!open || level != 1) return false
        level = 0
        sessionIndex = 0
        return true
    }

    fun selectedFolder(): RemoteFolder? = folders.getOrNull(folderIndex)

    /**
     * Level 0: descends to conversations, returns null (no-op on empty
     * folders). Level 1: returns the chosen target (session id null = new
     * conversation). Does NOT close.
     */
    fun confirm(): SessionTarget? {
        if (!open) return null
        if (level == 0) {
            if (folders.isEmpty()) return null
            level = 1
            sessionIndex = 0
            return null
        }
        val folder = selectedFolder() ?: return null
        val session = folder.sessions.getOrNull(sessionIndex - 1)
        return SessionTarget(folder.path, session?.id)
    }

    /** Updates the ▶ markers after a successful switch. */
    fun markCurrent(folderPath: String?, sessionId: String?) {
        currentFolderPath = folderPath
        currentSessionId = sessionId
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.rokid.terminal.SessionPickerStateTest" -v`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rokid/terminal/SessionModels.kt app/src/main/java/com/rokid/terminal/SessionPickerState.kt app/src/test/java/com/rokid/terminal/SessionPickerStateTest.kt
git commit -m "feat: two-level session picker state (pure JVM)"
```

---

### Task 2: ServerSessionFetcher + protocol parsers

**Files:**
- Create: `app/src/main/java/com/rokid/terminal/ServerSessionFetcher.kt`
- Test: `app/src/test/java/com/rokid/terminal/ServerSessionFetcherParseTest.kt`

**Interfaces:**
- Consumes: `EndpointProfile`, `DeviceKeyStore.Identity`, `PinnedHostKeyRepository`, `RemoteFolder`, `RemoteSession`, `SessionStatus` (Task 1).
- Produces:
  - `class ServerSessionFetcher(endpoint: EndpointProfile, identity: DeviceKeyStore.Identity)` with
    - `fun listSessions(baseDir: String): List<RemoteFolder>?` (null only on transport failure)
    - `fun status(tmuxSession: String): SessionStatus?`
    - `fun switchConversation(tmuxSession: String, baseDir: String, folderPath: String, sessionId: String, isNew: Boolean): String?` (raw helper output)
  - Companion (pure, unit-tested):
    - `const val HELPER = "/home/rokid/bin/rokid-sessions 2>/dev/null || true"`
    - `fun encodeDir(path: String): String` — non-alphanumeric chars → `-`
    - `fun parseList(text: String): List<RemoteFolder>`
    - `fun parseStatus(text: String): SessionStatus?`
    - `fun parseSwitchResult(text: String): Pair<String, String>?` — `(encodedDir, sessionId)` from an `ok\t…` line; null on `error\t…` or garbage

Helper protocol (tab-separated):
```
F\t<real-path>\t<encoded-dir>                          (base dir + direct subdirs, dotdirs excluded)
S\t<encoded-dir>\t<session-id>\t<epoch>\t<title>        (per folder, newest 30)
status:  pid\t<pid>\t<cwd>\t<session-id|->   |   none
switch:  ok\t<encoded-dir>\t<session-id>    |   error\t<message>
```

- [ ] **Step 1: Write the failing test**

`ServerSessionFetcherParseTest.kt`:

```kotlin
package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSessionFetcherParseTest {

    @Test
    fun encodeDirMatchesClaudeCodePathEncoding() {
        assertEquals("-srv", ServerSessionFetcher.encodeDir("/srv"))
        assertEquals("-srv-RokidDev", ServerSessionFetcher.encodeDir("/srv/RokidDev"))
        assertEquals("-Users-l-wy623-Desktop", ServerSessionFetcher.encodeDir("/Users/l.wy623/Desktop"))
        assertEquals("plain", ServerSessionFetcher.encodeDir("plain"))
    }

    @Test
    fun parseListGroupsSessionsUnderFolders() {
        val text = "F\t/srv\t-srv\n" +
            "F\t/srv/RokidDev\t-srv-RokidDev\n" +
            "S\t-srv\t11111111-2222-3333-4444-555555555555\t1700000000000\tfirst  chat\n" +
            "S\t-srv\taaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\t1700000100000\tmulti line title\n"

        val folders = ServerSessionFetcher.parseList(text)

        assertEquals(2, folders.size)
        val srv = folders.first { it.path == "/srv" }
        assertEquals("-srv", srv.encodedDir)
        assertEquals(2, srv.sessions.size)
        assertEquals("first  chat", srv.sessions[0].title) // internal spaces preserved
        assertEquals(1_700_000_000_000L, srv.sessions[0].epochMillis)
        assertEquals("multi line title", srv.sessions[1].title)
        assertTrue(folders.first { it.path == "/srv/RokidDev" }.sessions.isEmpty())
    }

    @Test
    fun parseListIgnoresGarbageAndSkipsMalformedLines() {
        val text = "F\t/srv\t-srv\nsome noise\nS\t-srv\tid\tnotanumber\tno time\n"

        val folders = ServerSessionFetcher.parseList(text)

        assertEquals(1, folders.size)
        assertEquals(1, folders[0].sessions.size) // epoch falls back to 0
        assertEquals(0L, folders[0].sessions[0].epochMillis)
    }

    @Test
    fun parseListKeepsFolderOrderAndIsEmptySafe() {
        assertTrue(ServerSessionFetcher.parseList("").isEmpty())
        assertTrue(ServerSessionFetcher.parseList("# only comments").isEmpty())
    }

    @Test
    fun parseStatusReturnsSessionOrNull() {
        assertEquals(
            SessionStatus("123", "/srv", "id-9"),
            ServerSessionFetcher.parseStatus("pid\t123\t/srv\tid-9"),
        )
        assertNull(ServerSessionFetcher.parseStatus("pid\t123\t/srv\t-")?.sessionId)
        assertNull(ServerSessionFetcher.parseStatus("none"))
        assertNull(ServerSessionFetcher.parseStatus(""))
    }

    @Test
    fun parseSwitchResultDistinguishesOkAndError() {
        val ok = ServerSessionFetcher.parseSwitchResult("ok\t-srv\tid-9")
        assertEquals("-srv", ok?.first)
        assertEquals("id-9", ok?.second)
        assertNull(ServerSessionFetcher.parseSwitchResult("error\thelper missing"))
        assertNull(ServerSessionFetcher.parseSwitchResult(""))
    }
}
```

(The first assertion in `parseSwitchResultDistinguishesOkAndError` is intentionally replaced by the explicit form below it — delete that first line when writing the file.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.rokid.terminal.ServerSessionFetcherParseTest" -v`
Expected: FAIL — `ServerSessionFetcher` not found.

- [ ] **Step 3: Implement the fetcher and parsers**

`ServerSessionFetcher.kt` (mirror `ServerCommandFetcher`'s connection pattern exactly):

```kotlin
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

    /**
     * Takes the keystore, NOT an identity: this fetcher is called repeatedly
     * per connection (list, status every 30 s, switch), and a shared identity
     * would be zeroed by the first call's fill(0). A fresh identity is
     * decrypted per run and cleared after import (2026-08-08, review fix).
     */
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

    /** Returns null when the deadline expired without EOF (timeout ≠ empty output). */
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.rokid.terminal.ServerSessionFetcherParseTest" -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rokid/terminal/ServerSessionFetcher.kt app/src/test/java/com/rokid/terminal/ServerSessionFetcherParseTest.kt
git commit -m "feat: server session fetcher with pure protocol parsers"
```

---

### Task 3: ScrollbackStore (per-conversation persistence)

**Files:**
- Create: `app/src/main/java/com/rokid/terminal/ScrollbackStore.kt`
- Test: `app/src/test/java/com/rokid/terminal/ScrollbackStoreTest.kt`

**Interfaces:**
- Consumes: nothing (java.io.File only).
- Produces: `class ScrollbackStore(private val filesDir: File)` with
  - `fun file(endpointId: String, folderKey: String, sessionId: String): File`
  - `fun legacyFile(endpointId: String): File`
  - `fun read(file: File): List<String>`
  - `fun write(file: File, rows: List<String>)` — persists only `MAX_ROWS` newest rows
  - `fun prune(endpointId: String, maxFiles: Int = MAX_FILES)` — deletes oldest files of this endpoint beyond the cap
  - companion: `const val MAX_ROWS = 1000`, `const val MAX_FILES = 30`, `fun sanitize(value: String): String`

- [ ] **Step 1: Write the failing test**

`ScrollbackStoreTest.kt`:

```kotlin
package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ScrollbackStoreTest {
    private fun tempDir(): File = Files.createTempDirectory("scrollback-test").toFile()

    @Test
    fun fileNamesAreSanitizedAndKeyedByEndpointFolderSession() {
        val store = ScrollbackStore(tempDir())
        val file = store.file("cloud", "-srv", "11111111-2222-3333-4444-555555555555")

        assertEquals("scrollback_cloud_-srv_11111111-2222-3333-4444-555555555555.txt", file.name)
        assertTrue(store.file("a/b", "-srv", "id x").name.startsWith("scrollback_a_b_"))
    }

    @Test
    fun writeKeepsOnlyNewestRows() {
        val store = ScrollbackStore(tempDir())
        val file = store.file("cloud", "-srv", "id")
        val rows = (0 until 1500).map { "row $it" }

        store.write(file, rows)
        val loaded = store.read(file)

        assertEquals(ScrollbackStore.MAX_ROWS, loaded.size)
        assertEquals("row 500", loaded.first())
        assertEquals("row 1499", loaded.last())
    }

    @Test
    fun readMissingFileReturnsEmpty() {
        val store = ScrollbackStore(tempDir())
        assertTrue(store.read(store.file("cloud", "-srv", "id")).isEmpty())
    }

    @Test
    fun pruneDeletesOldestBeyondCap() {
        val dir = tempDir()
        val store = ScrollbackStore(dir)
        for (i in 0 until 5) {
            val f = store.file("cloud", "-srv", "id-$i")
            f.writeText("x")
            f.setLastModified(1_700_000_000_000L + i * 1000L)
        }

        store.prune("cloud", maxFiles = 3)

        val remaining = dir.listFiles { f -> f.name.startsWith("scrollback_cloud_") }!!.map { it.name }
        assertEquals(3, remaining.size)
        assertFalse(remaining.any { it.contains("id-0") || it.contains("id-1") })
        assertTrue(remaining.any { it.contains("id-4") })
    }

    @Test
    fun pruneIsNoopBelowCapAndIgnoresOtherEndpoints() {
        val dir = tempDir()
        val store = ScrollbackStore(dir)
        for (i in 0 until 4) store.file("cloud", "-srv", "id-$i").writeText("x")
        store.file("other", "-srv", "id-0").writeText("x")

        store.prune("cloud", maxFiles = 5)

        assertEquals(4, dir.listFiles { f -> f.name.startsWith("scrollback_cloud_") }!!.size)
        assertEquals(1, dir.listFiles { f -> f.name.startsWith("scrollback_other_") }!!.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.rokid.terminal.ScrollbackStoreTest" -v`
Expected: FAIL — `ScrollbackStore` not found.

- [ ] **Step 3: Implement the store**

`ScrollbackStore.kt`:

```kotlin
package com.rokid.terminal

import java.io.File

/**
 * Per-conversation scrollback persistence (design spec 2026-08-07).
 * Files: scrollback_<endpointId>_<folderKey>_<sessionId>.txt in filesDir,
 * bounded at MAX_ROWS rows per file and MAX_FILES per endpoint (LRU by
 * mtime). Only java.io.File — unit-testable on the JVM. The legacy
 * per-endpoint file (scrollback_<endpointId>.txt) is read via [legacyFile]
 * for one-time migration.
 */
class ScrollbackStore(private val filesDir: File) {

    fun file(endpointId: String, folderKey: String, sessionId: String): File =
        File(filesDir, "scrollback_${sanitize(endpointId)}_${sanitize(folderKey)}_${sanitize(sessionId)}.txt")

    /** Pre-conversation per-endpoint file (created by builds before 2026-08-08). */
    fun legacyFile(endpointId: String): File =
        File(filesDir, "scrollback_${sanitize(endpointId)}.txt")

    fun read(file: File): List<String> =
        if (file.exists()) {
            runCatching { file.readText().split("\n") }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

    fun write(file: File, rows: List<String>) {
        if (rows.isEmpty()) return
        runCatching {
            file.writeText(rows.takeLast(MAX_ROWS).joinToString("\n"))
        }
    }

    /** Deletes the oldest files of this endpoint until at most [maxFiles] remain. */
    fun prune(endpointId: String, maxFiles: Int = MAX_FILES) {
        val prefix = "scrollback_${sanitize(endpointId)}_"
        val files = filesDir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }
            ?.sortedBy { it.lastModified() }
            ?: return
        val overflow = files.size - maxFiles
        if (overflow > 0) files.take(overflow).forEach { runCatching { it.delete() } }
    }

    companion object {
        const val MAX_ROWS = 1000
        const val MAX_FILES = 30
        fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.rokid.terminal.ScrollbackStoreTest" -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rokid/terminal/ScrollbackStore.kt app/src/test/java/com/rokid/terminal/ScrollbackStoreTest.kt
git commit -m "feat: per-conversation scrollback store with LRU prune"
```

---

### Task 4: Palette display list + defaults update

**Files:**
- Modify: `app/src/main/java/com/rokid/terminal/CommandPaletteState.kt` (add companion)
- Modify: `app/src/main/java/com/rokid/terminal/MainActivity.kt:1640-1650` (`COMMAND_PALETTE_DEFAULTS`) and `:1365-1381` (`ensurePaletteCommands`)
- Test: `app/src/test/java/com/rokid/terminal/CommandPaletteStateTest.kt` (add tests)

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `CommandPaletteState.SESSION_PICKER_ITEM = "[切换对话]"` (companion const)
  - `CommandPaletteState.displayList(defaults: List<String>, remote: List<String>?): List<String>` — returns `["/", SESSION_PICKER_ITEM] + (defaults + remote).distinct().sorted()`

- [ ] **Step 1: Write the failing test (append to CommandPaletteStateTest.kt)**

```kotlin
    @Test
    fun displayListLeadsWithSlashAndSessionItemThenSortedUniqueCommands() {
        val defaults = listOf("/model", "/usage", "/clear")
        val remote = listOf("/usage", "/custom")

        val list = CommandPaletteState.displayList(defaults, remote)

        assertEquals("/", list[0])
        assertEquals(CommandPaletteState.SESSION_PICKER_ITEM, list[1])
        assertEquals(listOf("/clear", "/custom", "/model", "/usage"), list.drop(2))
    }

    @Test
    fun displayListWithoutRemoteStillLeadsWithSpecialItems() {
        val list = CommandPaletteState.displayList(listOf("/model"), null)

        assertEquals("/", list[0])
        assertEquals(CommandPaletteState.SESSION_PICKER_ITEM, list[1])
        assertEquals(listOf("/model"), list.drop(2))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.rokid.terminal.CommandPaletteStateTest" -v`
Expected: FAIL — companion members missing.

- [ ] **Step 3: Implement**

Add to `CommandPaletteState.kt` (end of class):

```kotlin
    companion object {
        /** Local palette action that opens the conversation picker (design 2026-08-07). */
        const val SESSION_PICKER_ITEM = "[切换对话]"

        /**
         * The displayed palette: the bare "/" (voice-continuation) and the
         * session-picker action always lead, followed by the sorted unique
         * defaults merged with any server-side custom commands.
         */
        fun displayList(defaults: List<String>, remote: List<String>?): List<String> =
            listOf("/", SESSION_PICKER_ITEM) + (defaults + (remote ?: emptyList())).distinct().sorted()
    }
```

In `MainActivity.kt`:

1. Replace the `COMMAND_PALETTE_DEFAULTS` list (lines ~1640-1650) — drop the bare `/` (now a display-list lead), `/resume`, and `/continue` (superseded by the local picker):

```kotlin
        /**
         * Full known built-in command set (server `claude` list + commands
         * verified in real use, 2026-08-06). The display list is built via
         * CommandPaletteState.displayList (bare "/" and the session-picker
         * action lead). `/resume` and `/continue` were removed 2026-08-08:
         * the local conversation picker supersedes them. The server helper
         * adds custom commands/skills when reachable; the UI never claims
         * completeness.
         */
        val COMMAND_PALETTE_DEFAULTS = listOf(
            "/add-dir", "/agents", "/bug", "/clear", "/codex", "/compact",
            "/config", "/copy", "/cost", "/doctor", "/effort", "/expose",
            "/export", "/fast", "/help", "/hooks", "/idle", "/init",
            "/install-github-app", "/keybindings", "/login", "/logout", "/mcp",
            "/memory", "/model", "/permissions", "/pr-comments",
            "/release-notes", "/reset", "/review", "/rewind",
            "/shortcuts", "/skills", "/status", "/statusline",
            "/terminal-setup", "/todos", "/update", "/usage", "/vim",
            "/wall-clock",
        )
```

2. In `ensurePaletteCommands()` (lines ~1365-1381), replace the two `palette.setItems(...)` calls:

```kotlin
    private fun ensurePaletteCommands() {
        if (palette.items.isEmpty()) {
            palette.setItems(CommandPaletteState.displayList(COMMAND_PALETTE_DEFAULTS, null))
        }
        if (paletteFetchDone || paletteFetchInFlight) return
        val fetcher = commandFetcher ?: return
        paletteFetchInFlight = true
        Thread {
            val remote = fetcher.fetch()
            runOnUiThread {
                paletteFetchInFlight = false
                paletteFetchDone = true
                if (remote != null && remote.isNotEmpty()) {
                    palette.setItems(CommandPaletteState.displayList(COMMAND_PALETTE_DEFAULTS, remote))
                    if (palette.open) paletteSyncToView()
                }
            }
        }.start()
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.rokid.terminal.CommandPaletteStateTest" -v`
Expected: PASS (8 tests).

- [ ] **Step 5: Build check and commit**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/java/com/rokid/terminal/CommandPaletteState.kt app/src/main/java/com/rokid/terminal/MainActivity.kt app/src/test/java/com/rokid/terminal/CommandPaletteStateTest.kt
git commit -m "feat: palette display list with session-picker action; drop /resume /continue"
```

---

### Task 5: Server helper `rokid-sessions`

**Files:**
- Create: `server/rokid-sessions`
- Deploy (manual, on the endpoint): `scp server/rokid-sessions <user>@<host>:/home/rokid/bin/rokid-sessions && ssh <user>@<host> chmod +x /home/rokid/bin/rokid-sessions`

**Interfaces:**
- Consumes: `~/.claude/projects/*/` (JSONL sessions), the base dir, tmux.
- Produces (tab-separated stdout, `#` comments ignored by the app):
  - `rokid-sessions list <base-dir>` → `F\t<real-path>\t<encoded-dir>` (base + direct subdirs, dotdirs excluded), then `S\t<encoded-dir>\t<session-id>\t<epoch>\t<title>` (newest 30 per folder)
  - `rokid-sessions status <tmux-session>` → `pid\t<pid>\t<cwd>\t<session-id|->` or `none`
  - `rokid-sessions switch <tmux-session> <base-dir> <real-dir> <resume:<id>|new:<uuid>>` → `ok\t<encoded-dir>\t<session-id>` or `error\t<message>`

- [ ] **Step 1: Write the helper**

`server/rokid-sessions`:

```bash
#!/usr/bin/env bash
# RokidTerm conversation switcher (server side; design spec 2026-08-07).
# Structured tab-separated output; NEVER prints message bodies. Session
# title = first user message. Requires python3 (session-title extraction)
# and tmux. Install:
#   scp server/rokid-sessions <user>@<host>:/home/<user>/bin/rokid-sessions
#   ssh <user>@<host> chmod +x /home/<user>/bin/rokid-sessions
set -u
PROJECTS_DIR="$HOME/.claude/projects"
CLAUDE_LAUNCHER="/home/rokid/bin/rokid-claude"

encode() { printf '%s' "$1" | tr -c 'A-Za-z0-9' '-'; }

# First user-message text of a session JSONL (single line, control chars
# stripped, <= 40 chars). Streams; exits at the first user text block.
first_user_title() {
  python3 -c '
import json, sys
p = sys.argv[1]
with open(p, encoding="utf-8", errors="replace") as fh:
    for line in fh:
        try:
            e = json.loads(line)
        except Exception:
            continue
        if e.get("type") != "user":
            continue
        c = e.get("content")
        t = ""
        if isinstance(c, str):
            t = c
        else:
            for b in (c or []):
                if isinstance(b, dict) and isinstance(b.get("text"), str):
                    t = b["text"]
                    break
        if t:
            t = "".join(ch if ord(ch) >= 0x20 else " " for ch in t)
            t = t.replace("\t", " ").strip()
            print(t[:40])
            sys.exit(0)
' "$1" 2>/dev/null
}

# Newest session id in a project dir, or "-".
newest_session_id() {
  local enc="$1" dir="$PROJECTS_DIR/$enc"
  [ -d "$dir" ] || { echo "-"; return; }
  local newest
  newest="$(ls -t "$dir"/*.jsonl 2>/dev/null | head -1)"
  [ -n "$newest" ] || { echo "-"; return; }
  basename "$newest" .jsonl
}

# Descendants of $1 up to 6 levels deep, one pid per line.
descendants() {
  local root="$1" level=0
  local -a frontier=("$root") next=()
  while [ ${#frontier[@]} -gt 0 ] && [ "$level" -lt 6 ]; do
    next=()
    local pid child
    for pid in "${frontier[@]}"; do
      echo "$pid"
      for child in $(pgrep -P "$pid" 2>/dev/null); do
        next+=("$child")
      done
    done
    if [ "${#next[@]}" -gt 0 ]; then
      frontier=("${next[@]}")
    else
      frontier=()
    fi
    level=$((level + 1))
  done
}

# First descendant of the pane whose cmdline mentions claude, or "".
first_claude_descendant() {
  local pane_pid="$1" pid
  for pid in $(descendants "$pane_pid"); do
    if ps -p "$pid" -o args= 2>/dev/null | grep -q '[c]laude'; then
      echo "$pid"
      return
    fi
  done
}

list_base() {
  local base="$1" bname enc
  bname="$(cd "$base" 2>/dev/null && pwd -P)" || return 0
  enc="$(encode "$bname")"
  printf 'F\t%s\t%s\n' "$bname" "$enc"
  local d
  for d in "$bname"/*/; do
    [ -d "$d" ] || continue
    local dn="${d%/}"
    case "$(basename "$dn")" in
      .*) continue ;;
    esac
    printf 'F\t%s\t%s\n' "$dn" "$(encode "$dn")"
  done
}

list_sessions() {
  local enc="$1" dir="$PROJECTS_DIR/$enc"
  [ -d "$dir" ] || return 0
  local f
  for f in "$dir"/*.jsonl; do
    [ -f "$f" ] || continue
    local id mtime title
    id="$(basename "$f" .jsonl)"
    mtime="$(stat -c %Y "$f" 2>/dev/null || echo 0)"
    title="$(first_user_title "$f")"
    printf 'S\t%s\t%s\t%s\t%s\n' "$enc" "$id" "$mtime" "$title"
  done | sort -t $'\t' -k4 -rn | head -30
}

cmd_list() {
  local base="$1"
  list_base "$base" | while IFS=$'\t' read -r kind path enc; do
    if [ "$kind" = "F" ]; then
      printf 'F\t%s\t%s\n' "$path" "$enc"
      list_sessions "$enc"
    fi
  done
}

cmd_status() {
  local session="$1" pane_pid claude_pid cwd enc newest
  pane_pid="$(tmux list-panes -t "$session" -F '#{pane_pid}' 2>/dev/null | head -1)"
  [ -n "$pane_pid" ] || { echo "none"; return 0; }
  claude_pid="$(first_claude_descendant "$pane_pid")"
  [ -n "$claude_pid" ] || { echo "none"; return 0; }
  cwd="$(readlink -f "/proc/$claude_pid/cwd" 2>/dev/null || true)"
  [ -n "$cwd" ] || { echo "none"; return 0; }
  enc="$(encode "$cwd")"
  newest="$(newest_session_id "$enc")"
  printf 'pid\t%s\t%s\t%s\n' "$claude_pid" "$cwd" "$newest"
}

cmd_switch() {
  local session="$1" base="$2" dir="$3" target="$4"
  local base_resolved dir_resolved
  base_resolved="$(cd "$base" 2>/dev/null && pwd -P)" || { echo "error\tbase not accessible"; return 1; }
  dir_resolved="$(cd "$dir" 2>/dev/null && pwd -P)" || { echo "error\tpath not accessible"; return 1; }
  # Validate the RESOLVED dir (handles .. and symlinks): base itself or a
  # path under it. ${var#prefix} literal-removal avoids case-pattern glob
  # metacharacters in the base path.
  if [ "$dir_resolved" = "$base_resolved" ] ||
     [ "${dir_resolved#"$base_resolved"/}" != "$dir_resolved" ]; then
    : # ok
  else
    echo "error\tpath outside base"; return 1
  fi

  local id launch_args
  case "$target" in
    resume:*)
      id="${target#resume:}"
      case "$id" in
        *[!A-Za-z0-9_-]*) echo "error\tbad session id"; return 1 ;;
      esac
      launch_args="--resume $id"
      ;;
    new:*)
      id="${target#new:}"
      case "$id" in
        *[!A-Za-z0-9-]*) echo "error\tbad session id"; return 1 ;;
      esac
      launch_args="--session-id $id"
      ;;
    *)
      echo "error\tbad target"; return 1 ;;
  esac

  local launch_cmd="$CLAUDE_LAUNCHER --effort max --dangerously-skip-permissions $launch_args"

  # Ensure the tmux session, then start Claude in its pane: respawn when a
  # session exists (kills the previous Claude), create otherwise. Fall back
  # to kill+send-keys if respawn-pane is unavailable.
  if tmux has-session -t "$session" 2>/dev/null; then
    if ! tmux respawn-pane -t "$session" -k -c "$dir_resolved" "$launch_cmd" 2>/dev/null; then
      local pane_pid claude_pid
      pane_pid="$(tmux list-panes -t "$session" -F '#{pane_pid}' 2>/dev/null | head -1)"
      [ -n "$pane_pid" ] || { echo "error\tno tmux pane"; return 1; }
      claude_pid="$(first_claude_descendant "$pane_pid")"
      if [ -n "$claude_pid" ]; then
        kill "$claude_pid" 2>/dev/null
        sleep 1
        kill -0 "$claude_pid" 2>/dev/null && kill -9 "$claude_pid" 2>/dev/null
        sleep 1
      fi
      local qdir
      qdir="$(printf '%q' "$dir_resolved")"
      tmux send-keys -t "$session" "cd $qdir && $launch_cmd" Enter
    fi
  else
    tmux new-session -d -s "$session" -c "$dir_resolved" "$launch_cmd"
  fi

  # Verify: a Claude process whose cwd is the target dir (poll <= 15 s).
  local i ok=""
  for i in $(seq 1 30); do
    local pane_pid2 claude_pid2 cwd2
    pane_pid2="$(tmux list-panes -t "$session" -F '#{pane_pid}' 2>/dev/null | head -1)"
    [ -n "$pane_pid2" ] || break
    claude_pid2="$(first_claude_descendant "$pane_pid2")"
    if [ -n "$claude_pid2" ]; then
      cwd2="$(readlink -f "/proc/$claude_pid2/cwd" 2>/dev/null || true)"
      if [ "$cwd2" = "$dir_resolved" ]; then
        ok="$claude_pid2"
        break
      fi
    fi
    sleep 0.5
  done
  [ -n "$ok" ] || { echo "error\tclaude did not start"; return 1; }

  # resume: the session file must exist (a fresh session's JSONL appears on
  # the first message, so for new: pid+cwd above is enough).
  case "$target" in
    resume:*)
      local enc expected
      enc="$(encode "$dir_resolved")"
      expected="$PROJECTS_DIR/$enc/$id.jsonl"
      for i in $(seq 1 10); do
        [ -f "$expected" ] && break
        sleep 0.5
      done
      [ -f "$expected" ] || { echo "error\tsession file missing"; return 1; }
      ;;
  esac

  printf 'ok\t%s\t%s\n' "$(encode "$dir_resolved")" "$id"
}

main() {
  local verb="${1:-}"
  case "$verb" in
    list)
      [ $# -ge 2 ] || { echo "error\tusage: list <base-dir>"; return 1; }
      cmd_list "$2"
      ;;
    status)
      [ $# -ge 2 ] || { echo "error\tusage: status <tmux-session>"; return 1; }
      cmd_status "$2"
      ;;
    switch)
      [ $# -ge 5 ] || { echo "error\tusage: switch <tmux-session> <base-dir> <real-dir> <resume:id|new:uuid>"; return 1; }
      cmd_switch "$2" "$3" "$4" "$5"
      ;;
    *)
      echo "error\tunknown verb"
      return 1
      ;;
  esac
}

main "$@"
```

- [ ] **Step 2: Syntax check**

Run: `bash -n server/rokid-sessions`
Expected: no output, exit 0.

- [ ] **Step 3: Local fixture smoke test (macOS has tmux)**

```bash
# 1. Staged fixture: fake HOME with one project dir and two sessions.
FIX="$(mktemp -d)"
mkdir -p "$FIX/.claude/projects/-srv" "$FIX/proj"
printf '{"type":"user","content":[{"type":"text","text":"hello world"}]}\n' > "$FIX/.claude/projects/-srv/aaaaaaaa-0000-1111-2222-333344445555.jsonl"
printf '{"type":"user","content":[{"type":"text","text":"second chat"}]}\n' > "$FIX/.claude/projects/-srv/bbbbbbbb-0000-1111-2222-333344445555.jsonl"
HOME="$FIX" bash server/rokid-sessions list "$FIX/proj" 2>&1
# Expected: one F line for the base dir (empty subdirs = none) and two S
# lines. NOTE: the script uses Linux `stat -c %Y`; on macOS mtime falls back
# to 0 so ordering is arbitrary — assert structure, not order, in the fixture.
HOME="$FIX" bash server/rokid-sessions status rokid-claude
# Expected: "none" (no tmux session named rokid-claude exists).
```

- [ ] **Step 4: Commit**

```bash
chmod +x server/rokid-sessions
git add server/rokid-sessions
git commit -m "feat: server rokid-sessions helper (list/status/switch)"
```

- [ ] **Step 5: Deploy to the endpoint (manual, before device testing)**

```bash
scp server/rokid-sessions <user>@<host>:/home/rokid/bin/rokid-sessions
ssh <user>@<host> chmod +x /home/rokid/bin/rokid-sessions
ssh <user>@<host> "claude --version"   # confirm --resume/--session-id era (v2.1.223+ preferred)
```

---

### Task 6: EndpointProfile attach-only + legacy fallback

**Files:**
- Modify: `app/src/main/java/com/rokid/terminal/EndpointProfile.kt:21-32` (`remoteCommand`)
- Modify: `app/src/main/java/com/rokid/terminal/SshTerminalSession.kt:35-111` (`connect`)
- Test: `app/src/test/java/com/rokid/terminal/EndpointProfileTest.kt` (update + add)

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `EndpointProfile.remoteCommand` — attach-only: ensures the tmux session exists (plain shell), applies status options, `exec tmux attach-session`. The server helper owns Claude's launch.
  - `EndpointProfile.legacyRemoteCommand` — the previous command (tmux new-session runs the fixed launcher with `--effort max --dangerously-skip-permissions`); used only when the helper is unreachable.
  - `SshTerminalSession.connect(config: EndpointProfile, identity: DeviceKeyStore.Identity, legacy: Boolean = false)` — uses `config.legacyRemoteCommand` when `legacy == true`.

- [ ] **Step 1: Write the failing test (update EndpointProfileTest.kt)**

```kotlin
package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointProfileTest {
    private fun profile() = EndpointProfile(
        id = "cloud",
        name = "Cloud",
        host = "example.com",
        port = 22,
        user = "rokid",
        knownHost = "example.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITest",
        workspace = "/srv/projects/my World",
        sessionName = "cloud-claude",
    )

    private val statusOptions =
        "tmux set-option -t cloud-claude status-left '[#{session_name}] ' && " +
            "tmux set-option -t cloud-claude status-left-length '20' && " +
            "tmux set-option -t cloud-claude status-right '%H:%M' && " +
            "tmux set-option -t cloud-claude status-right-length '5' && " +
            "tmux set-option -t cloud-claude window-status-format '#W#{?window_flags,#{window_flags}, }' && " +
            "tmux set-option -t cloud-claude window-status-current-format '#W#{?window_flags,#{window_flags}, }' && " +
            "tmux set-option -t cloud-claude window-status-separator ' ' && "

    @Test
    fun `remote command attaches without launching claude`() {
        val profile = profile()

        assertEquals(
            "(tmux has-session -t cloud-claude 2>/dev/null || " +
                "tmux new-session -d -s cloud-claude -c '/srv/projects/my World') && " +
                statusOptions +
                "exec tmux attach-session -t cloud-claude",
            profile.remoteCommand,
        )
    }

    @Test
    fun `legacy remote command launches claude in the workspace`() {
        // Full-string assertEquals locks byte-identity with the pre-
        // multi-conversation remoteCommand (fix round 1, 2026-08-08).
        assertEquals(
            "(tmux has-session -t cloud-claude 2>/dev/null || " +
                "tmux new-session -d -s cloud-claude -c '/srv/projects/my World' " +
                "/home/rokid/bin/rokid-claude --effort max --dangerously-skip-permissions) && " +
                statusOptions +
                "exec tmux attach-session -t cloud-claude",
            profile().legacyRemoteCommand,
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.rokid.terminal.EndpointProfileTest" -v`
Expected: FAIL — `remoteCommand` still contains the launcher.

- [ ] **Step 3: Implement**

In `EndpointProfile.kt`, replace the `remoteCommand` property (lines ~21-32):

```kotlin
    /**
     * Attach-only command (design 2026-08-07): ensures the tmux session
     * exists as a plain shell and attaches. The server helper
     * (`rokid-sessions switch`) owns launching Claude with the chosen
     * folder/session; this command no longer starts it.
     */
    val remoteCommand: String
        get() {
            val safeSession = sessionName.replace(Regex("[^A-Za-z0-9_.-]"), "-")
            val ensureSession = "(tmux has-session -t $safeSession 2>/dev/null || " +
                "tmux new-session -d -s $safeSession -c ${shellQuote(workspace)})"
            val configureStatus = TMUX_STATUS_OPTIONS.joinToString(" && ") { (option, value) ->
                "tmux set-option -t $safeSession $option ${shellQuote(value)}"
            }
            return "$ensureSession && $configureStatus && exec tmux attach-session -t $safeSession"
        }

    /**
     * Fallback used only when the session helper is unreachable: the
     * previous behavior — create the session running the fixed launcher in
     * the workspace. The sync watcher later reconciles the conversation
     * binding to the session Claude actually created.
     */
    val legacyRemoteCommand: String
        get() {
            val safeWorkspace = shellQuote(workspace)
            val safeSession = sessionName.replace(Regex("[^A-Za-z0-9_.-]"), "-")
            val launch = "tmux new-session -d -s $safeSession -c $safeWorkspace " +
                "$CLAUDE_LAUNCHER --effort max --dangerously-skip-permissions"
            val ensureSession = "(tmux has-session -t $safeSession 2>/dev/null || $launch)"
            val configureStatus = TMUX_STATUS_OPTIONS.joinToString(" && ") { (option, value) ->
                "tmux set-option -t $safeSession $option ${shellQuote(value)}"
            }
            return "$ensureSession && $configureStatus && exec tmux attach-session -t $safeSession"
        }
```

In `SshTerminalSession.kt`, change the signature and the write call:

```kotlin
    fun connect(config: EndpointProfile, identity: DeviceKeyStore.Identity, legacy: Boolean = false) {
        ...
                output.write(((if (legacy) config.legacyRemoteCommand else config.remoteCommand) + "\r").toByteArray())
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.rokid.terminal.EndpointProfileTest" -v`
Expected: PASS.

- [ ] **Step 5: Build check and commit**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/java/com/rokid/terminal/EndpointProfile.kt app/src/main/java/com/rokid/terminal/SshTerminalSession.kt app/src/test/java/com/rokid/terminal/EndpointProfileTest.kt
git commit -m "feat: attach-only tmux remote command with legacy fallback"
```

---

### Task 7: TerminalView session-picker UI

**Files:**
- Modify: `app/src/main/java/com/rokid/terminal/TerminalView.kt`

**Interfaces:**
- Consumes: `RemoteFolder` (Task 1).
- Produces:
  - `data class SessionPickerUi(...)` (top-level in TerminalView.kt)
  - `fun setSessionPicker(ui: SessionPickerUi)` — stores and invalidates
  - private `drawSessionPicker(canvas: Canvas)` and private `truncateToWidth(value: String, maxWidth: Float): String`
- No JVM test (custom View); verified by build + on-device checklist.

- [ ] **Step 1: Add the UI snapshot and state field**

Near the other state fields (after `commandPaletteSelected`, line ~115):

```kotlin
    private var sessionPickerUi = SessionPickerUi()
```

Add the data class at the end of the file (or near the top-level types):

```kotlin
/** Snapshot of the conversation picker overlay (design 2026-08-07). */
data class SessionPickerUi(
    val open: Boolean = false,
    val loading: Boolean = false,
    val error: Boolean = false,
    val level: Int = 0,
    val folders: List<RemoteFolder> = emptyList(),
    val folderIndex: Int = 0,
    val sessionIndex: Int = 0,
    val currentFolderPath: String? = null,
    val currentSessionId: String? = null,
)
```

After `setCommandPalette` (line ~123):

```kotlin
    /** Conversation-picker state for the modal overlay. */
    fun setSessionPicker(ui: SessionPickerUi) {
        sessionPickerUi = ui
        invalidate()
    }
```

- [ ] **Step 2: Hook the draw at the end of onDraw**

In `onDraw`, after the composer/history-preview/blinking-cursor block (after the `if (composerVisible) { ... } else if (historyPreviewText != null) { ... } else if (blinkOn) { ... }` block, i.e. before the closing brace of `onDraw`):

```kotlin
        if (sessionPickerUi.open) drawSessionPicker(canvas)
```

- [ ] **Step 3: Implement drawSessionPicker**

Add after `drawCommandPaletteList` (line ~851), mirroring its visual language (black overlay, green border, 12-row window, scrollbar, footer hints):

```kotlin
    private fun drawSessionPicker(canvas: Canvas) {
        val left = 24f
        val right = width - 24f
        val top = 90f
        val bottom = height - 60f

        paint.color = Color.BLACK
        paint.alpha = 246
        canvas.drawRect(left, top, right, bottom, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.GREEN
        paint.alpha = 245
        canvas.drawRect(left, top, right, bottom, paint)
        resetPaint()

        paint.isFakeBoldText = true
        paint.textSize = 16f
        val header = if (sessionPickerUi.level == 0) {
            "PROJECTS / UP-DOWN SELECT"
        } else {
            val folder = sessionPickerUi.folders.getOrNull(sessionPickerUi.folderIndex)
            "CONVERSATIONS / " + (folder?.path?.substringAfterLast('/')?.ifBlank { folder.path } ?: "?")
        }
        canvas.drawText(header, left + 12f, top + 24f, paint)
        paint.isFakeBoldText = false
        canvas.drawLine(left + 10f, top + 54f, right - 10f, top + 54f, paint)

        if (sessionPickerUi.loading) {
            paint.alpha = 255
            paint.textSize = 16f
            canvas.drawText("LOADING…", left + 12f, top + 96f, paint)
            resetPaint()
            return
        }

        val listLeft = left + 12f
        val listTop = top + 64f
        val rowHeight = 22f
        val rowWidth = right - left - 34f
        val items = if (sessionPickerUi.level == 0) {
            sessionPickerUi.folders.map { it.path }
        } else {
            val folder = sessionPickerUi.folders.getOrNull(sessionPickerUi.folderIndex)
            listOf("＋ 新对话") + (folder?.sessions?.map { it.title } ?: emptyList())
        }
        val selected = if (sessionPickerUi.level == 0) {
            sessionPickerUi.folderIndex
        } else {
            sessionPickerUi.sessionIndex
        }
        val visible = minOf(items.size, 12)
        val windowStart = (selected - visible / 2)
            .coerceIn(0, (items.size - visible).coerceAtLeast(0))

        if (sessionPickerUi.error && items.size <= 1) {
            paint.alpha = 170
            paint.textSize = 13f
            canvas.drawText("会话助手不可用 / 确认 = 新对话", listLeft, listTop + 90f, paint)
        }

        paint.textSize = 16f
        for (i in 0 until visible) {
            val itemIndex = windowStart + i
            val rowTop = listTop + i * rowHeight
            var text = items[itemIndex]
            if (itemIndex == selected) {
                paint.style = Paint.Style.FILL
                paint.color = Color.GREEN
                paint.alpha = 90
                canvas.drawRect(listLeft - 4f, rowTop, right - 14f, rowTop + rowHeight, paint)
                paint.alpha = 255
            } else {
                paint.alpha = 230
            }
            val current = if (sessionPickerUi.level == 0) {
                sessionPickerUi.currentFolderPath == sessionPickerUi.folders.getOrNull(itemIndex)?.path
            } else {
                itemIndex >= 1 && sessionPickerUi.currentSessionId ==
                    sessionPickerUi.folders.getOrNull(sessionPickerUi.folderIndex)
                        ?.sessions?.getOrNull(itemIndex - 1)?.id
            }
            if (current) text = "▶ $text"
            if (paint.measureText(text) > rowWidth) text = truncateToWidth(text, rowWidth)
            paint.style = Paint.Style.FILL
            canvas.drawText(text, listLeft, rowTop + 17f, paint)
        }

        if (items.size > visible) {
            val trackTop = listTop + 2f
            val trackBottom = listTop + visible * rowHeight - 2f
            val trackHeight = (trackBottom - trackTop).coerceAtLeast(1f)
            val thumbHeight = (trackHeight * visible / items.size).coerceIn(14f, trackHeight)
            val maxStart = (items.size - visible).coerceAtLeast(1)
            val thumbTop = trackTop + (trackHeight - thumbHeight) * windowStart / maxStart
            paint.style = Paint.Style.FILL
            paint.color = Color.GREEN
            paint.alpha = 65
            canvas.drawRect(right - 12f, trackTop, right - 10f, trackBottom, paint)
            paint.alpha = 210
            canvas.drawRect(right - 13f, thumbTop, right - 9f, thumbTop + thumbHeight, paint)
        }

        paint.alpha = 175
        paint.textSize = 11f
        canvas.drawLine(left + 10f, bottom - 60f, right - 10f, bottom - 60f, paint)
        val hint = if (sessionPickerUi.level == 0) {
            "UP/DOWN SELECT   CONFIRM OPEN"
        } else {
            "CONFIRM SWITCH   BACK = UP"
        }
        canvas.drawText(hint, left + 12f, bottom - 36f, paint)
        canvas.drawText("BACK / KNOB-R / GO-DOUBLE CANCEL", left + 12f, bottom - 15f, paint)
        resetPaint()
    }

    /** Ellipsizes [value] to fit [maxWidth] using the current paint. */
    private fun truncateToWidth(value: String, maxWidth: Float): String {
        if (paint.measureText(value) <= maxWidth) return value
        var end = value.length
        while (end > 1 && paint.measureText(value.substring(0, end) + "…") > maxWidth) end--
        return value.substring(0, end) + "…"
    }
```

- [ ] **Step 4: Build check**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rokid/terminal/TerminalView.kt
git commit -m "feat: session picker overlay rendering"
```

---

### Task 8: MainActivity — picker input dispatch + palette entry

**Files:**
- Modify: `app/src/main/java/com/rokid/terminal/MainActivity.kt`

**Interfaces:**
- Consumes: `SessionPickerState`, `SessionTarget`, `RemoteFolder`, `CommandPaletteState.SESSION_PICKER_ITEM` / `displayList` (Tasks 1, 4).
- Produces (used by Task 9):
  - fields `sessionPicker: SessionPickerState`, `sessionPickerConnectMode: Boolean`, `sessionFetcher: ServerSessionFetcher?`
  - `fun openSessionPicker(connectMode: Boolean)`, `fun sessionPickerMove(delta: Int)`, `fun sessionPickerConfirm()`, `fun sessionPickerCancel()`, `fun sessionPickerSyncToView()`
  - private `handleSessionPickerKey(keyCode: Int, event: KeyEvent): Boolean`

- [ ] **Step 1: Add fields**

After `commandFetcher` (line ~65):

```kotlin
    private val sessionPicker = SessionPickerState()
    private var sessionPickerConnectMode = false
    private var sessionFetcher: ServerSessionFetcher? = null
```

- [ ] **Step 2: Input dispatch hooks**

In `onKeyDown` (line ~365), insert the picker guard FIRST:

```kotlin
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (sessionPicker.open && handleSessionPickerKey(keyCode, event)) return true
        if (mode != Mode.ENDPOINTS && isPrimaryKey(keyCode)) {
            return handlePrimaryKeyDown(keyCode, event)
        }
        ...
```

In `onKeyUp` (line ~376), insert the picker guard FIRST (mirrors the F8-first structure):

```kotlin
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // The picker consumes every key-up; GO still arbitrates so its
        // double press can cancel the picker (2026-08-08).
        if (sessionPicker.open) {
            if (isRingKey(event) && keyCode == KeyEvent.KEYCODE_F8) handleGoKey(event)
            return true
        }
        if (isRingKey(event) && keyCode == KeyEvent.KEYCODE_F8) {
            handleGoKey(event)
            return true
        }
        ...
```

- [ ] **Step 3: handleSessionPickerKey + picker actions**

Add after `paletteMove` (line ~1191), before the Part 3 section:

```kotlin
    // --- Conversation picker (design 2026-08-07; rules/input.md contract) ---

    /**
     * Modal picker keys: navigate (COIDEA 2/4/5/6, TP swipes, Ring swipes
     * with its inverted arrival), confirm (TP single / Ring touchpad single
     * / COIDEA left knob), cancel (Back / COIDEA right knob / Ring GO
     * double via handleGoKey). Strict isolation: everything else is
     * consumed while the picker is open.
     */
    private fun handleSessionPickerKey(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_5,
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_6 -> {
            if (event.repeatCount == 0) {
                sessionPickerMove(if (keyCode == KeyEvent.KEYCODE_2 || keyCode == KeyEvent.KEYCODE_4) -1 else 1)
            }
            true
        }
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
            if (event.repeatCount == 0) {
                sessionPickerMove(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
            }
            true
        }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
            if (event.repeatCount == 0) {
                // Ring right-swipe arrives as DPAD_LEFT (inverted) = next.
                val ring = isRingEvent(event)
                val next = if (ring) keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                else keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                sessionPickerMove(if (next) 1 else -1)
            }
            true
        }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_8 -> {
            if (event.repeatCount == 0) sessionPickerConfirm()
            true
        }
        KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_BACK -> {
            if (event.repeatCount == 0) sessionPickerCancel()
            true
        }
        KeyEvent.KEYCODE_F8 -> {
            handleGoKey(event)
            true
        }
        else -> true
    }

    private fun sessionPickerSyncToView() {
        terminalView.setSessionPicker(
            SessionPickerUi(
                open = sessionPicker.open,
                loading = sessionPicker.loading,
                error = sessionPicker.error,
                level = sessionPicker.level,
                folders = sessionPicker.folders,
                folderIndex = sessionPicker.folderIndex,
                sessionIndex = sessionPicker.sessionIndex,
                currentFolderPath = sessionPicker.currentFolderPath,
                currentSessionId = sessionPicker.currentSessionId,
            ),
        )
    }

    private fun sessionPickerMove(delta: Int) {
        if (!sessionPicker.open) return
        sessionPicker.move(delta)
        sessionPickerSyncToView()
    }

    private fun sessionPickerConfirm() {
        if (!sessionPicker.open) return
        val target = sessionPicker.confirm()
        sessionPickerSyncToView()
        if (target == null) return // descended to the conversation level
        sessionPicker.close()
        sessionPickerSyncToView()
        val sessionId = target.sessionId ?: java.util.UUID.randomUUID().toString()
        switchToTarget(target.folderPath, sessionId, isNew = target.sessionId == null,
            thenConnect = sessionPickerConnectMode)
    }

    private fun sessionPickerCancel() {
        if (!sessionPicker.open) return
        if (sessionPicker.back()) {
            sessionPickerSyncToView()
            return
        }
        sessionPicker.close()
        sessionPickerSyncToView()
        val wasConnectMode = sessionPickerConnectMode
        sessionPickerConnectMode = false
        if (wasConnectMode) {
            asr.disconnect()
            showEndpointPicker()
        }
    }
```

- [ ] **Step 4: Palette entry for [切换对话]**

In `confirmPaletteSelection` (line ~1163), insert the local-action branch before the insert path:

```kotlin
    private fun confirmPaletteSelection() {
        val command = palette.select() ?: return
        if (command == CommandPaletteState.SESSION_PICKER_ITEM) {
            palette.close()
            paletteOpenedBySlash = false
            paletteSyncToView()
            cancelComposer("PICKER")
            openSessionPicker(connectMode = false)
            return
        }
        palette.close()
        ...
```

- [ ] **Step 5: handleGoKey picker branches**

In `handleGoKey` (line ~560):
1. Long-press branch: add `&& !sessionPicker.open` to the condition:
```kotlin
                    if (mode == Mode.TERMINAL && !panelMode && !sessionPicker.open && sshState == "CONNECTED") {
                        ssh.sendCharacters("")
                    }
```
2. Double-press branch: add the picker case FIRST in the `when`:
```kotlin
                    when {
                        sessionPicker.open -> sessionPickerCancel()
                        mode == Mode.TERMINAL && panelMode -> {
                            // Part 3: GO double = cancel & return (ESC + exit).
                            ssh.sendEscape()
                            cancelPanelMode()
                        }
                        mode == Mode.TERMINAL -> {
                            ssh.disconnect()
                            asr.disconnect()
                            showEndpointPicker()
                        }
                        mode == Mode.COMPOSER -> cancelComposer("CANCELLED")
                    }
```
3. Single-press Runnable: guard the composer action:
```kotlin
                    val single = Runnable {
                        goDoublePending = null
                        if (sessionPicker.open) {
                            // no-op: GO single does nothing in the picker
                        } else if (mode == Mode.COMPOSER) {
                            toggleCommandPalette()
                        }
                    }
```

- [ ] **Step 6: Build check and commit**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. Task 9 has not landed yet, so add temporary no-op stubs after `paletteMove` so the class compiles:

```kotlin
    // TODO(Task 9): replaced by the real implementations.
    private fun openSessionPicker(connectMode: Boolean) = Unit
    private fun switchToTarget(folderPath: String, sessionId: String, isNew: Boolean, thenConnect: Boolean) = Unit
```

```bash
git add app/src/main/java/com/rokid/terminal/MainActivity.kt
git commit -m "feat: conversation picker input dispatch and palette entry"
```

---

### Task 9: MainActivity — connect flow, switch execution, scrollback binding, sync watcher

**Files:**
- Modify: `app/src/main/java/com/rokid/terminal/MainActivity.kt`

**Interfaces:**
- Consumes: `ServerSessionFetcher` (Task 2), `ScrollbackStore` (Task 3), `SessionPickerState` (Task 1), `SshTerminalSession.connect(..., legacy)` (Task 6), `RemoteFolder` (Task 1).
- Produces:
  - `fun openSessionPicker(connectMode: Boolean)` (fills the Task 8 stub)
  - `fun switchToTarget(folderPath: String, sessionId: String, isNew: Boolean, thenConnect: Boolean)` (fills the Task 8 stub)
  - `fun bindScrollback(folderPath: String, sessionId: String)`, `rememberTarget(...)`, `pollSessionSync()`
  - fields `scrollbackStore: ScrollbackStore?`, `scrollbackFolderKey: String?`, `scrollbackSessionId: String?`, prefs accessor
  - `SESSION_SYNC_MS = 30_000L` companion const

- [ ] **Step 1: Fields and onCreate wiring**

Add fields (after `lastScrollbackCount`, line ~67):

```kotlin
    private var scrollbackStore: ScrollbackStore? = null
    private var scrollbackFolderKey: String? = null
    private var scrollbackSessionId: String? = null
```

In `onCreate` (after `terminalOutput`/`terminalView` setup, near line ~101):

```kotlin
        scrollbackStore = ScrollbackStore(filesDir)
        mainHandler.post(sessionSyncRunnable)
```

Add the runnable + prefs helpers near the other fields (after `lastScrollbackCount`):

```kotlin
    private val prefs = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)

    /**
     * Sync watcher (design 2026-08-07 §3.3): while connected with a bound
     * conversation, re-reads `rokid-sessions status` every SESSION_SYNC_MS
     * and re-binds local history when the server's active session changed
     * out-of-band (manual /resume, /cd). Local files are caches; the server
     * JSONL is authoritative.
     */
    private val sessionSyncRunnable = object : Runnable {
        override fun run() {
            pollSessionSync()
            mainHandler.postDelayed(this, SESSION_SYNC_MS)
        }
    }
```

- [ ] **Step 2: Rewrite connectSelected**

Replace `connectSelected` (line ~1490):

```kotlin
    private fun connectSelected() {
        val endpoint = endpoints.getOrNull(selectedIndex) ?: return
        traceRecorder.reset()
        endpointStore.select(endpoint.id)
        activeEndpoint = endpoint
        mode = Mode.TERMINAL
        panelMode = false
        terminalView.showTerminal(endpoint, terminalOutput.reset())
        val identity = try {
            DeviceKeyStore(this, endpoint.id).getOrCreate()
        } catch (error: Exception) {
            terminalView.setState("KEY ERROR: ${error.message}")
            return
        }
        commandFetcher = ServerCommandFetcher(endpoint, identity)
        // The session fetcher takes the keystore, not an identity: it is
        // called repeatedly per connection (list, status every 30 s, switch)
        // and must fetch a fresh identity per call (fill(0) zeroes it).
        sessionFetcher = ServerSessionFetcher(endpoint, DeviceKeyStore(this, endpoint.id))
        // Every connect starts with the conversation picker (user decision
        // 2026-08-07); the chosen target launches via the server helper.
        openSessionPicker(connectMode = true)
    }
```

- [ ] **Step 3: openSessionPicker + switchToTarget + binding**

Add after `connectSelected` (before `reconnectActiveEndpoint`):

```kotlin
    private fun openSessionPicker(connectMode: Boolean) {
        val endpoint = activeEndpoint ?: return
        sessionPickerConnectMode = connectMode
        clearPrimaryGesture()
        sessionPicker.open(rememberedFolder(endpoint.id), rememberedSession(endpoint.id))
        sessionPickerSyncToView()
        val fetcher = sessionFetcher ?: return
        val workspace = endpoint.workspace
        Thread {
            val folders = fetcher.listSessions(workspace)
            runOnUiThread {
                if (folders.isNullOrEmpty()) {
                    // Helper unreachable or no folders: fall back to a single
                    // "new conversation" entry in the workspace.
                    sessionPicker.setFolders(
                        listOf(RemoteFolder(workspace, ServerSessionFetcher.encodeDir(workspace), emptyList())),
                        failed = folders == null,
                    )
                } else {
                    sessionPicker.setFolders(folders, failed = false)
                }
                sessionPickerSyncToView()
            }
        }.start()
    }

    /**
     * Runs the server-side switch (kill/respawn Claude in the tmux pane with
     * the target folder/session), then binds local history to the target.
     * At connect time a failure falls back to the legacy fixed launch.
     */
    private fun switchToTarget(folderPath: String, sessionId: String, isNew: Boolean, thenConnect: Boolean) {
        val endpoint = activeEndpoint ?: return
        val fetcher = sessionFetcher ?: return
        persistScrollback()
        terminalView.setState(if (thenConnect) "CONNECTING / STARTING" else "SWITCHING…")
        val tmuxSession = endpoint.sessionName
        val workspace = endpoint.workspace
        Thread {
            val raw = fetcher.switchConversation(tmuxSession, workspace, folderPath, sessionId, isNew)
            val ok = raw != null && ServerSessionFetcher.parseSwitchResult(raw) != null
            runOnUiThread {
                if (ok) {
                    bindScrollback(folderPath, sessionId)
                    rememberTarget(folderPath, sessionId)
                    sessionPicker.markCurrent(folderPath, sessionId)
                    if (thenConnect) connectAfterSwitch(endpoint)
                    updateHeader()
                    android.widget.Toast.makeText(
                        this, "已切换会话", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else if (thenConnect) {
                    // Helper unavailable (not yet deployed / server error):
                    // preserve the pre-change behavior via the legacy launch.
                    // (Fix round 2026-08-08: this branch MUST pass
                    // useLegacy = true — the plan's original always-false
                    // call would attach to an empty tmux shell.)
                    android.util.Log.w("RokidTerminal", "session switch failed; legacy launch")
                    bindScrollback(workspace, sessionId)
                    rememberTarget(workspace, sessionId)
                    connectAfterSwitch(endpoint, useLegacy = true)
                } else {
                    android.widget.Toast.makeText(
                        this, "切换失败", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    updateHeader()
                }
            }
        }.start()
    }

    private fun connectAfterSwitch(endpoint: EndpointProfile, useLegacy: Boolean = false) {
        val identity = try {
            DeviceKeyStore(this, endpoint.id).getOrCreate()
        } catch (error: Exception) {
            terminalView.setState("KEY ERROR: ${error.message}")
            return
        }
        ssh.connect(endpoint, identity, legacy = useLegacy)
        asr.connect(endpoint)
    }

    /** Sets the scrollback binding and imports that conversation's history. */
    private fun bindScrollback(folderPath: String, sessionId: String) {
        scrollbackFolderKey = ServerSessionFetcher.encodeDir(folderPath)
        scrollbackSessionId = sessionId
        val endpoint = activeEndpoint
        val store = scrollbackStore
        var rows = loadScrollback()
        if (rows.isEmpty() && endpoint != null && store != null) {
            // One-time migration from the pre-conversation per-endpoint file.
            val legacy = store.legacyFile(endpoint.id)
            if (legacy.exists()) {
                rows = store.read(legacy)
                runCatching { legacy.delete() }
            }
        }
        terminalOutput.importScrollbackText(rows)
    }

    private fun rememberTarget(folderPath: String, sessionId: String) {
        val endpoint = activeEndpoint ?: return
        prefs.edit()
            .putString("last_folder_${endpoint.id}", folderPath)
            .putString("last_session_${endpoint.id}", sessionId)
            .apply()
    }

    private fun rememberedFolder(endpointId: String): String? = prefs.getString("last_folder_$endpointId", null)
    private fun rememberedSession(endpointId: String): String? = prefs.getString("last_session_$endpointId", null)
```

Note: `prefs` is declared with `android.content.Context.MODE_PRIVATE.let { getSharedPreferences(...) }` — simplify to `private val prefs = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)` (MODE_PRIVATE is inherited from Context in an Activity).

- [ ] **Step 4: Re-key persistScrollback / loadScrollback**

Replace `scrollbackFile` / `persistScrollback` / `loadScrollback` (lines ~1530-1556):

```kotlin
    /**
     * App-private per-conversation scrollback persistence (design
     * 2026-08-07): history is captured in memory during a session and saved
     * on disconnect/exit under the bound conversation's key, then restored
     * when that conversation is bound again. Files live in filesDir (never
     * shared storage); bounded at ScrollbackStore.MAX_ROWS rows per file and
     * MAX_FILES per endpoint (LRU). The binding is set by bindScrollback.
     */
    private fun persistScrollback() {
        val endpoint = activeEndpoint ?: return
        val folderKey = scrollbackFolderKey ?: return
        val sessionId = scrollbackSessionId ?: return
        val store = scrollbackStore ?: return
        val rows = terminalOutput.exportScrollbackText()
        store.write(store.file(endpoint.id, folderKey, sessionId), rows)
        store.prune(endpoint.id)
    }

    private fun loadScrollback(): List<String> {
        val endpoint = activeEndpoint ?: return emptyList()
        val folderKey = scrollbackFolderKey ?: return emptyList()
        val sessionId = scrollbackSessionId ?: return emptyList()
        val store = scrollbackStore ?: return emptyList()
        return store.read(store.file(endpoint.id, folderKey, sessionId))
    }
```

Delete the old `scrollbackFile(endpointId)` helper (its logic moved into `ScrollbackStore`).

- [ ] **Step 5: Sync watcher**

Add after `pollSessionSync`-related helpers (after `rememberedSession`):

```kotlin
    private fun pollSessionSync() {
        val fetcher = sessionFetcher ?: return
        val endpoint = activeEndpoint ?: return
        if (sshState != "CONNECTED" || sessionPicker.open) return
        val folderKey = scrollbackFolderKey ?: return
        val sessionId = scrollbackSessionId ?: return
        Thread {
            val status = fetcher.status(endpoint.sessionName)
            runOnUiThread {
                if (status == null || status.cwd == null) return@runOnUiThread
                val newFolderKey = ServerSessionFetcher.encodeDir(status.cwd)
                val folderChanged = newFolderKey != folderKey
                val sessionChanged = status.sessionId != null && status.sessionId != sessionId
                if (!folderChanged && !sessionChanged) return@runOnUiThread
                // The server's active conversation moved (manual /resume or
                // /cd): persist under the old key, rebind to the new one.
                persistScrollback()
                scrollbackFolderKey = newFolderKey
                scrollbackSessionId = status.sessionId ?: sessionId
                val store = scrollbackStore
                if (store != null) {
                    terminalOutput.importScrollbackText(
                        store.read(store.file(endpoint.id, newFolderKey, scrollbackSessionId!!)),
                    )
                }
                sessionPicker.markCurrent(status.cwd, scrollbackSessionId)
                android.widget.Toast.makeText(this, "已切换会话", android.widget.Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
```

Add the companion consts next to `PERSISTED_SCROLLBACK_ROWS` — delete `PERSISTED_SCROLLBACK_ROWS` itself (Step 4 replaced its only use; bounds now live in `ScrollbackStore.MAX_ROWS`):

```kotlin
        const val SESSION_SYNC_MS = 30_000L
        private const val SESSION_PREFS = "session_picker"
```

- [ ] **Step 6: Full build + test run**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL, all unit tests PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rokid/terminal/MainActivity.kt
git commit -m "feat: connect-time picker, server switch execution, per-conversation binding, sync watcher"
```

---

### Task 11: Picker keymap corrections + two parked one-liners

User-corrected keymap (2026-08-07, after Task 8 shipped): TP **double-tap**
cancels (not Back); COIDEA normal navigation is keys 2/5 ONLY (4/6 are
reserved for the armed delete selector, Task 16). Also folds in two
deferred minors from Task 9's review.

**Files:**
- Modify: `app/src/main/java/com/rokid/terminal/MainActivity.kt`

- [ ] **Step 1: TP single/double arbitration in the picker**

Currently `handleSessionPickerKey` confirms on primary-key DOWN instantly.
Replace with the codebase's standard single/double window (mirrors
`handlePrimaryKeyUp`'s pattern, self-contained in the picker):

```kotlin
    private var pickerPrimaryPending: Runnable? = null

    private fun pickerPrimaryPressed() {
        val pending = pickerPrimaryPending
        if (pending != null) {
            // Double-tap: cancel (user contract: TP double = cancel).
            mainHandler.removeCallbacks(pending)
            pickerPrimaryPending = null
            sessionPickerCancel()
        } else {
            val single = Runnable {
                pickerPrimaryPending = null
                sessionPickerConfirm()
            }
            pickerPrimaryPending = single
            mainHandler.postDelayed(single, ViewConfiguration.getDoubleTapTimeout().toLong())
        }
    }
```

In `handleSessionPickerKey`, replace the confirm branch:

```kotlin
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_8 -> {
            // TP/ring single = confirm after the double-tap window; a second
            // press within the window = cancel (user contract 2026-08-07).
            // The ring's touchpad double arrives as KEYCODE_DEL (firmware),
            // so its ENTER never double-fires — the window only affects TP.
            if (event.repeatCount == 0) pickerPrimaryPressed()
            true
        }
```

Note: `sessionPickerCancel` must clear `pickerPrimaryPending` (add
`pickerPrimaryPending?.let(mainHandler::removeCallbacks); pickerPrimaryPending = null`
at its top), and `openSessionPicker`/`sessionPickerConfirm` must also clear
it (`clearPrimaryGesture()` already runs in openSessionPicker; add the
clear to `sessionPickerConfirm` and `sessionPickerCancel`).

- [ ] **Step 2: COIDEA navigation 2/5 only**

In `handleSessionPickerKey`, the navigation branch currently maps
2/5/4/6. Change it to 2/5 only:

```kotlin
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_5 -> {
            if (event.repeatCount == 0) {
                sessionPickerMove(if (keyCode == KeyEvent.KEYCODE_2) -1 else 1)
            }
            true
        }
```

(4/6 fall through to `else -> true`, consumed — they are reserved for the
armed delete selector in Task 16.)

- [ ] **Step 3: Back stays a secondary cancel**

Keep `KeyEvent.KEYCODE_BACK` in the cancel branch (double-tap is the
primary TP cancel; Back remains a harmless fallback).

- [ ] **Step 4: Fold in the two Task 9 deferred minors**

1. `onDestroy`: add `mainHandler.removeCallbacks(sessionSyncRunnable)` next
   to the existing `removeCallbacks(keyboardPoll)`.
2. `pollSessionSync`: re-read `scrollbackFolderKey`/`scrollbackSessionId`
   INSIDE `runOnUiThread` instead of using the values captured before the
   fetch (fast double-switch race):

```kotlin
    private fun pollSessionSync() {
        val fetcher = sessionFetcher ?: return
        val endpoint = activeEndpoint ?: return
        if (sshState != "CONNECTED" || sessionPicker.open) return
        Thread {
            val status = fetcher.status(endpoint.sessionName)
            runOnUiThread {
                if (status == null || status.cwd == null) return@runOnUiThread
                val folderKey = scrollbackFolderKey ?: return@runOnUiThread
                val sessionId = scrollbackSessionId ?: return@runOnUiThread
                val newFolderKey = ServerSessionFetcher.encodeDir(status.cwd)
                val folderChanged = newFolderKey != folderKey
                val sessionChanged = status.sessionId != null && status.sessionId != sessionId
                if (!folderChanged && !sessionChanged) return@runOnUiThread
                persistScrollback()
                scrollbackFolderKey = newFolderKey
                scrollbackSessionId = status.sessionId ?: sessionId
                val store = scrollbackStore
                if (store != null) {
                    terminalOutput.importScrollbackText(
                        store.read(store.file(endpoint.id, newFolderKey, scrollbackSessionId!!)),
                    )
                }
                sessionPicker.markCurrent(status.cwd, scrollbackSessionId)
                android.widget.Toast.makeText(this, "已切换会话", android.widget.Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
```

- [ ] **Step 5: Build + full suite + commit**

Run: `./gradlew assembleDebug testDebugUnitTest` — all pass, zero regressions.

```bash
git add app/src/main/java/com/rokid/terminal/MainActivity.kt
git commit -m "fix: picker TP double-tap cancel, COIDEA 2/5 nav, watcher cleanup"
```

### Task 12: Server helper `delete` verb

**Files:**
- Modify: `server/rokid-sessions`

**Interfaces:**
- Produces: `rokid-sessions delete <tmux-session> <base-dir> <dir> <session-id>` → `ok\t<encoded-dir>\t<session-id>` or `error\t<message>`.

- [ ] **Step 1: Implement the verb**

Add to `main()`:

```bash
    delete)
      [ $# -ge 5 ] || { echo "error\tusage: delete <tmux-session> <base-dir> <real-dir> <session-id>"; return 1; }
      cmd_delete "$2" "$3" "$4" "$5"
      ;;
```

Add `cmd_delete` (reuses the same validation discipline as `cmd_switch`;
refuses the ACTIVE session — the running Claude's current conversation):

```bash
cmd_delete() {
  local session="$1" base="$2" dir="$3" id="$4"
  case "$id" in
    *[!A-Za-z0-9_-]*) echo "error\tbad session id"; return 1 ;;
  esac
  local base_resolved dir_resolved
  base_resolved="$(cd "$base" 2>/dev/null && pwd -P)" || { echo "error\tbase not accessible"; return 1; }
  dir_resolved="$(cd "$dir" 2>/dev/null && pwd -P)" || { echo "error\tpath not accessible"; return 1; }
  if [ "$dir_resolved" = "$base_resolved" ] ||
     [ "${dir_resolved#"$base_resolved"/}" != "$dir_resolved" ]; then
    : # ok
  else
    echo "error\tpath outside base"; return 1
  fi
  local enc target
  enc="$(encode "$dir_resolved")"
  target="$PROJECTS_DIR/$enc/$id.jsonl"
  # Refuse the running conversation (defense in depth; the app also blocks ▶).
  local pane_pid claude_pid cwd newest
  pane_pid="$(tmux list-panes -t "$session" -F '#{pane_pid}' 2>/dev/null | head -1)"
  if [ -n "$pane_pid" ]; then
    claude_pid="$(first_claude_descendant "$pane_pid")"
    if [ -n "$claude_pid" ]; then
      cwd="$(readlink -f "/proc/$claude_pid/cwd" 2>/dev/null || true)"
      if [ "$cwd" = "$dir_resolved" ]; then
        newest="$(newest_session_id "$enc")"
        if [ "$newest" = "$id" ]; then
          echo "error\tactive session"
          return 1
        fi
      fi
    fi
  fi
  [ -f "$target" ] || { echo "error\tnot found"; return 1; }
  rm -f "$target" || { echo "error\tdelete failed"; return 1; }
  printf 'ok\t%s\t%s\n' "$enc" "$id"
}
```

- [ ] **Step 2: Syntax + fixture checks**

`bash -n server/rokid-sessions` — clean. Fixture (reuse the Task 5 encoded
fixture): create a session JSONL for a NON-active id, `delete` it → `ok`
and the file is gone; delete again → `error\tnot found`; delete with a bad
id → `error\tbad session id`; delete a path outside the base → `error\tpath
outside base`.

- [ ] **Step 3: Commit**

```bash
git add server/rokid-sessions
git commit -m "feat: rokid-sessions delete verb"
```

### Task 13: ServerSessionFetcher.deleteConversation

**Files:**
- Modify: `app/src/main/java/com/rokid/terminal/ServerSessionFetcher.kt`

- [ ] **Step 1: Add the method**

```kotlin
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
```

(Reuses `parseSwitchResult` — the delete verb prints the same ok/error
format. No new parser logic; no new tests needed beyond the existing
parser suite, which already covers the format.)

- [ ] **Step 2: Build + full suite + commit**

Run: `./gradlew assembleDebug testDebugUnitTest` — all pass.

```bash
git add app/src/main/java/com/rokid/terminal/ServerSessionFetcher.kt
git commit -m "feat: server session delete call"
```

### Task 14: SessionPickerState delete-arm state

**Files:**
- Modify: `app/src/main/java/com/rokid/terminal/SessionPickerState.kt`
- Test: `app/src/test/java/com/rokid/terminal/SessionPickerStateTest.kt` (append)

**Interfaces:**
- Produces: `deleteArmed: Boolean`, `deleteOption: Int` (0=取消, 1=删除),
  `armDelete(): Boolean`, `disarmDelete()`, `moveDeleteOption(delta: Int)`,
  `confirmDeleteOption(): Boolean`, `removeCurrentSession()`.

- [ ] **Step 1: Append the failing tests**

```kotlin
    @Test
    fun armDeleteWorksOnSessionRowAndBlocksCurrent() {
        val picker = SessionPickerState().apply {
            open(null, "id-1")
            setFolders(listOf(folderA), failed = false)
        }
        picker.confirm() // descend
        picker.move(1)   // session "id-1" — the CURRENT one (▶)

        assertFalse(picker.armDelete()) // current session is not deletable

        picker.move(1)   // session "id-2"
        assertTrue(picker.armDelete())
        assertTrue(picker.deleteArmed)
        assertEquals(0, picker.deleteOption) // default = cancel (safe)
    }

    @Test
    fun armDeleteFailsOnNewConversationSlot() {
        val picker = SessionPickerState().apply {
            open(null, null)
            setFolders(listOf(folderA), failed = false)
        }
        picker.confirm() // new-slot selected

        assertFalse(picker.armDelete())
        assertFalse(picker.deleteArmed)
    }

    @Test
    fun moveDeleteOptionWrapsAndConfirmExecutesOnlyOnDelete() {
        val picker = SessionPickerState().apply {
            open(null, "id-1")
            setFolders(listOf(folderA), failed = false)
        }
        picker.confirm()
        picker.move(2) // "id-2"
        picker.armDelete()

        assertFalse(picker.confirmDeleteOption()) // on 取消 → caller disarms
        picker.moveDeleteOption(1)
        assertTrue(picker.confirmDeleteOption())  // on 删除 → caller deletes
        picker.moveDeleteOption(1)                // wraps back to 取消
        assertFalse(picker.confirmDeleteOption())
    }

    @Test
    fun armedStateBlocksNormalNavigation() {
        val picker = SessionPickerState().apply {
            open(null, "id-1")
            setFolders(listOf(folderA), failed = false)
        }
        picker.confirm()
        picker.move(2)
        picker.armDelete()

        val level = picker.level
        val index = picker.sessionIndex
        picker.move(1)
        assertEquals(level, picker.level)
        assertEquals(index, picker.sessionIndex)
    }

    @Test
    fun removeCurrentSessionClampsSelectionAndDisarms() {
        val picker = SessionPickerState().apply {
            open(null, "id-1")
            setFolders(listOf(folderA), failed = false)
        }
        picker.confirm()
        picker.move(2)
        picker.armDelete()

        picker.removeCurrentSession()

        assertFalse(picker.deleteArmed)
        assertEquals(2, picker.conversationCount) // new-slot + id-1
        assertEquals(1, picker.sessionIndex)      // clamped to last row
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.rokid.terminal.SessionPickerStateTest" -v`
Expected: FAIL — members missing.

- [ ] **Step 3: Implement**

```kotlin
    var deleteArmed: Boolean = false
        private set
    var deleteOption: Int = 0
        private set

    /**
     * Arms the delete selector on the selected session row. False when the
     * picker is closed, not on a session row, or the row IS the current
     * conversation (▶) — the running conversation is never deletable.
     */
    fun armDelete(): Boolean {
        if (!open || level != 1 || sessionIndex < 1) return false
        val session = selectedFolder()?.sessions?.getOrNull(sessionIndex - 1) ?: return false
        if (session.id == currentSessionId) return false
        deleteArmed = true
        deleteOption = 0 // default on 取消 (safe position)
        return true
    }

    fun disarmDelete() {
        deleteArmed = false
        deleteOption = 0
    }

    /** Moves between 取消 (0) and 删除 (1) with wrap; no-op when not armed. */
    fun moveDeleteOption(delta: Int) {
        if (!deleteArmed) return
        deleteOption = ((deleteOption + delta) % 2 + 2) % 2
    }

    /** True only when armed on 删除 — the caller executes the delete. */
    fun confirmDeleteOption(): Boolean = deleteArmed && deleteOption == 1

    /** Removes the selected session from the folder and clamps the selection. */
    fun removeCurrentSession() {
        val folder = selectedFolder() ?: return
        val index = sessionIndex - 1
        val updated = folder.sessions.filterIndexed { i, _ -> i != index }
        folders = folders.map { if (it.encodedDir == folder.encodedDir) it.copy(sessions = updated) else it }
        sessionIndex = sessionIndex.coerceAtMost(conversationCount - 1)
        disarmDelete()
    }
```

Also gate the normal navigation while armed — in `move()`, `confirm()`, and
`back()`:

```kotlin
    fun move(delta: Int) {
        if (!open || loading || deleteArmed) return
        ...
    }
    fun back(): Boolean {
        if (!open || deleteArmed || level != 1) return false
        ...
    }
    fun confirm(): SessionTarget? {
        if (!open || deleteArmed) return null
        ...
    }
```

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.rokid.terminal.SessionPickerStateTest" -v`
Expected: PASS (14 tests).

- [ ] **Step 5: Full suite + commit**

Run: `./gradlew testDebugUnitTest` — all pass.

```bash
git add app/src/main/java/com/rokid/terminal/SessionPickerState.kt app/src/test/java/com/rokid/terminal/SessionPickerStateTest.kt
git commit -m "feat: session picker delete-arm state"
```

### Task 15: TerminalView armed rendering

**Files:**
- Modify: `app/src/main/java/com/rokid/terminal/TerminalView.kt`

- [ ] **Step 1: Extend the UI snapshot**

Add two fields to `SessionPickerUi`:

```kotlin
data class SessionPickerUi(
    val open: Boolean = false,
    val loading: Boolean = false,
    val error: Boolean = false,
    val level: Int = 0,
    val folders: List<RemoteFolder> = emptyList(),
    val folderIndex: Int = 0,
    val sessionIndex: Int = 0,
    val currentFolderPath: String? = null,
    val currentSessionId: String? = null,
    val deleteArmed: Boolean = false,
    val deleteOption: Int = 0,
)
```

- [ ] **Step 2: Render the armed state**

In `drawSessionPicker`, when `sessionPickerUi.deleteArmed && sessionPickerUi.level == 1`:

1. The selected row text gets a `删除?` suffix before ellipsization
   (`if (deleteArmed && itemIndex == selected) text = "$text 删除?"`).
2. Replace the footer hint area with a two-option bar above the footer
   line:

```kotlin
        if (sessionPickerUi.deleteArmed && sessionPickerUi.level == 1) {
            paint.alpha = 255
            paint.textSize = 16f
            val cancelX = left + 12f
            val deleteX = right - 12f - paint.measureText("删除")
            val cancelText = if (sessionPickerUi.deleteOption == 0) "◀ 取消" else "取消"
            val deleteText = if (sessionPickerUi.deleteOption == 1) "删除 ▶" else "删除"
            if (sessionPickerUi.deleteOption == 0) {
                paint.style = Paint.Style.FILL
                paint.color = Color.GREEN
                paint.alpha = 90
                canvas.drawRect(left + 4f, bottom - 92f, right - 4f, bottom - 62f, paint)
            } else {
                paint.style = Paint.Style.FILL
                paint.color = Color.GREEN
                paint.alpha = 90
                canvas.drawRect(left + 4f, bottom - 92f, right - 4f, bottom - 62f, paint)
                paint.alpha = 90
                canvas.drawRect(right / 2f, bottom - 92f, right - 4f, bottom - 62f, paint)
            }
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            canvas.drawText(cancelText, cancelX, bottom - 68f, paint)
            canvas.drawText(deleteText, deleteX, bottom - 68f, paint)
            paint.alpha = 175
            paint.textSize = 11f
            canvas.drawText("SWIPE SELECT   CONFIRM DELETE   CANCEL UNMARK", left + 12f, bottom - 36f, paint)
        } else {
            // existing hint block
        }
```

(Simpler alternative if the two-rect version reads awkwardly: draw ONE
highlight rect whose left/right halves depend on `deleteOption` — the
intent is: the selected option is visibly highlighted, 取消 on the left,
删除 on the right.)

- [ ] **Step 3: Build + commit**

Run: `./gradlew assembleDebug` — BUILD SUCCESSFUL.

```bash
git add app/src/main/java/com/rokid/terminal/TerminalView.kt
git commit -m "feat: session picker delete-arm rendering"
```

### Task 16: MainActivity delete wiring

**Files:**
- Modify: `app/src/main/java/com/rokid/terminal/MainActivity.kt`

- [ ] **Step 1: Arm triggers**

In `handleSessionPickerKey`, add:

```kotlin
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MOVE_HOME -> {
            // Ring touchpad long press: arm the delete selector.
            if (event.repeatCount == 0) sessionPickerArmDelete()
            true
        }
        KeyEvent.KEYCODE_3 -> {
            // COIDEA spare key: arm the delete selector.
            if (event.repeatCount == 0) sessionPickerArmDelete()
            true
        }
```

In `handleSystemKeyAction`, replace the Task 9 guard so the TP long-press
broadcast arms the delete selector:

```kotlin
    private fun handleSystemKeyAction(action: Int) {
        if (sessionPicker.open) {
            // Strict isolation: only the long-press broadcast acts (delete
            // selector arm); the Shutter broadcast is consumed.
            if (action == ACTION_LONG_PRESS) sessionPickerArmDelete()
            return
        }
        ...
```

- [ ] **Step 2: Armed-state key routing**

In `handleSessionPickerKey`, when the picker is armed the navigation and
confirm/cancel keys route to the delete selector instead of the list:

```kotlin
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_5,
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_6 -> {
            if (event.repeatCount == 0) {
                if (sessionPicker.deleteArmed) {
                    // Armed: COIDEA 4/6 (and 2/5) move the 取消/删除 selector.
                    sessionPickerMoveDeleteOption(if (keyCode == KeyEvent.KEYCODE_4 || keyCode == KeyEvent.KEYCODE_2) -1 else 1)
                } else if (keyCode == KeyEvent.KEYCODE_2 || keyCode == KeyEvent.KEYCODE_5) {
                    sessionPickerMove(if (keyCode == KeyEvent.KEYCODE_2) -1 else 1)
                }
            }
            true
        }
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
            if (event.repeatCount == 0) {
                if (sessionPicker.deleteArmed) {
                    // Armed: any swipe moves the selector (right/down = 删除).
                    val ring = isRingEvent(event)
                    val next = when {
                        keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> true
                        ring -> keyCode == KeyEvent.KEYCODE_DPAD_LEFT // ring right-swipe arrival
                        else -> false
                    }
                    sessionPickerMoveDeleteOption(if (next) 1 else -1)
                } else {
                    sessionPickerMove(if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                }
            }
            true
        }
```

`sessionPickerConfirm` and `sessionPickerCancel` route through the armed
state first (Task 11's `pickerPrimaryPressed` double-tap also lands here —
its second press cancels, which disarms):

```kotlin
    private fun sessionPickerConfirm() {
        if (!sessionPicker.open) return
        if (sessionPicker.deleteArmed) {
            if (sessionPicker.confirmDeleteOption()) {
                val folder = sessionPicker.selectedFolder() ?: return
                val session = folder.sessions.getOrNull(sessionPicker.sessionIndex - 1) ?: return
                sessionPicker.disarmDelete()
                sessionPickerSyncToView()
                runDeleteConversation(folder.path, session.id)
            } else {
                sessionPicker.disarmDelete()
                sessionPickerSyncToView()
            }
            return
        }
        val target = sessionPicker.confirm()
        ...
    }

    private fun sessionPickerCancel() {
        if (!sessionPicker.open) return
        pickerPrimaryPending?.let(mainHandler::removeCallbacks)
        pickerPrimaryPending = null
        if (sessionPicker.deleteArmed) {
            sessionPicker.disarmDelete()
            sessionPickerSyncToView()
            return
        }
        if (sessionPicker.back()) {
            sessionPickerSyncToView()
            return
        }
        ...
    }
```

- [ ] **Step 3: runDeleteConversation + local file cleanup**

```kotlin
    private fun sessionPickerArmDelete() {
        if (sessionPicker.armDelete()) sessionPickerSyncToView()
    }

    private fun sessionPickerMoveDeleteOption(delta: Int) {
        sessionPicker.moveDeleteOption(delta)
        sessionPickerSyncToView()
    }

    /** Deletes the transcript on the server + the local scrollback file. */
    private fun runDeleteConversation(folderPath: String, sessionId: String) {
        val endpoint = activeEndpoint ?: return
        val fetcher = sessionFetcher ?: return
        Thread {
            val raw = fetcher.deleteConversation(endpoint.sessionName, endpoint.workspace, folderPath, sessionId)
            val ok = raw != null && ServerSessionFetcher.parseSwitchResult(raw) != null
            runOnUiThread {
                if (ok) {
                    sessionPicker.removeCurrentSession()
                    val store = scrollbackStore
                    if (store != null) {
                        runCatching {
                            store.file(endpoint.id, ServerSessionFetcher.encodeDir(folderPath), sessionId).delete()
                        }
                    }
                    sessionPickerSyncToView()
                    android.widget.Toast.makeText(this, "已删除会话", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, "删除失败", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
```

- [ ] **Step 4: sessionPickerSyncToView passes the armed state**

```kotlin
    private fun sessionPickerSyncToView() {
        terminalView.setSessionPicker(
            SessionPickerUi(
                open = sessionPicker.open,
                loading = sessionPicker.loading,
                error = sessionPicker.error,
                level = sessionPicker.level,
                folders = sessionPicker.folders,
                folderIndex = sessionPicker.folderIndex,
                sessionIndex = sessionPicker.sessionIndex,
                currentFolderPath = sessionPicker.currentFolderPath,
                currentSessionId = sessionPicker.currentSessionId,
                deleteArmed = sessionPicker.deleteArmed,
                deleteOption = sessionPicker.deleteOption,
            ),
        )
    }
```

- [ ] **Step 5: Build + full suite + commit**

Run: `./gradlew assembleDebug testDebugUnitTest` — all pass, zero regressions.

```bash
git add app/src/main/java/com/rokid/terminal/MainActivity.kt
git commit -m "feat: conversation delete via armed selector"
```



**Files:**
- Modify: `RokidTerm/rules/composer.md` — palette contract: the `[切换对话]` local action item, `/resume`+`/continue` removal, and a pointer to the session-picker section.
- Modify: `RokidTerm/rules/input.md` — conversation-picker key contract (Part 4 table: navigate/confirm/cancel per device, strict isolation, GO double cancel via arbitration).
- Modify: `RokidTerm/rules/rendering.md` — scrollback persistence keyed per conversation; file layout; 30-file LRU; sync watcher.
- Modify: `RokidTerm/CLAUDE.md` — Implemented section (2026-08-08), Open/pending: mark session-resume in progress with the design/plan links, and the per-conversation keying note; update the "keyed per endpoint today" sentence in the scrollback bullet.

- [ ] **Step 1: Update rules/composer.md**

In the "Implemented: local command palette (2026-08-06)" section, extend the list source + triggers with:

```markdown
- A local action item `[切换对话]` sits directly after the bare `/`
  (selecting it opens the conversation picker instead of inserting text;
  contract in `input.md` Part 4). `/resume` and `/continue` were removed
  from the defaults 2026-08-08 — the local picker supersedes them; typed or
  speech `/resume` still passes through, and the sync watcher re-binds local
  history when the session changes.
```

- [ ] **Step 2: Update rules/input.md**

Add a Part 4 section (conversation picker) with the user-corrected keymap
(2026-08-07): TP double-tap cancels (NOT Back); COIDEA normal navigation is
keys 2/5 only (4/6 reserved for the armed delete selector):

```markdown
### Part 4: Conversation picker (2026-08-08)

Local two-level picker (folders → conversations) opened at connect time or
from the palette's `[切换对话]` action. Strict isolation: while open, only
navigate/confirm/cancel act; everything else is consumed (incl. the
long-press/Shutter broadcasts — Shutter is a no-op; the TP long-press
broadcast arms the delete selector).

| Device | Navigate | Confirm | Cancel |
|---|---|---|---|
| Rokid TP | swipe up/down (left/right = up/down) | single click (after the double-tap window) | double click |
| COIDEA KM | keys 2/5 | left knob (8) | right knob (D) |
| INMO Ring4 | swipe (right-swipe arrival = next) | touchpad single | GO double |

Back remains a secondary cancel fallback on the glasses. GO double cancels
via the same F8 arbitration as Part 3; GO long and single are blocked. Back
at level 1 steps up to folders; Back at level 0 closes the picker
(connect-time: returns to the endpoint list).

Delete selector (armed by long-press on a session row — TP long-press
broadcast / Ring touchpad long / COIDEA key 3): a two-option bar
`取消 | 删除` appears with 取消 selected by default; swipes (or COIDEA
4/6) move between the options; confirm on 删除 deletes the transcript on
the server + the local scrollback file (irrecoverable); confirm on 取消 or
any cancel key disarms. The current conversation (▶) can never be armed.
```

- [ ] **Step 3: Update rules/rendering.md**

Extend the scrollback persistence bullet:

```markdown
- Scrollback persistence is keyed per CONVERSATION (2026-08-08): files are
  `scrollback_<endpointId>_<folderKey>_<sessionId>.txt` (folderKey = the
  server's encoded project dir; sessionId = the Claude session uuid — the
  app supplies it for new conversations via `--session-id`). Bounded at
  1000 rows/file and 30 files per endpoint (LRU by mtime). Binding follows
  the conversation picker's choice; a 30 s sync watcher re-binds when the
  server's active session changes out-of-band (manual `/resume`, `/cd`).
```

- [ ] **Step 4: Update CLAUDE.md**

In the "Verified 2026-08-06" scrollback bullet, replace the final sentence "Files are keyed per endpoint today; when session-resume lands, key them per Claude session/conversation instead." with "Files are now keyed per conversation (2026-08-08); see Open/pending." Add an Implemented 2026-08-08 section (conversation picker, server helper, per-conversation keying, sync watcher, conversation deletion via the armed selector, TP double-tap cancel) and move the session-resume item in Open/pending to "implemented — see design + plan docs". Add a new Open/pending item:

```markdown
- **Claude interactive panels with input fields** — panels that combine a
  list with a text input (e.g. the option panels Claude Code shows for
  choosing an implementation approach) are NOT yet handled; not observed in
  real use as of 2026-08-07. Design the interaction (list nav + input
  focus) only after a real case is captured.
```

- [ ] **Step 5: Commit**

```bash
git add rules/composer.md rules/input.md rules/rendering.md CLAUDE.md
git commit -m "docs: conversation picker contract, per-conversation scrollback, status"
```

---

## Verification checklist (on device, after Task 10)

Run `./dev.sh build && ./dev.sh run`, then:

1. **Deploy helper first**: scp `server/rokid-sessions`, chmod, `claude --version` on the server.
2. **Connect flow**: endpoint click → picker appears with `/srv` (+ its subfolders) → confirm (level 1, `＋ 新对话`) → terminal starts in the workspace with a fresh session; a second connect shows the new session in the list with its first message as the title.
3. **Resume**: pick a past conversation → the conversation replays; the `▶` marker moves; local history import is browsable (TP swipes).
4. **In-session switch**: composer → palette → `[切换对话]` → folder/session → confirm → "已切换会话" toast; the pane redraws; history gestures browse the new conversation.
5. **Cancel paths**: Back at level 1 → up; Back at level 0 (connect-time) → endpoint list; Ring GO double → cancel; right knob → cancel; TP single confirms; Ring touchpad single confirms; COIDEA keys 2/5/4/6 navigate.
6. **Scrollback files**: `adb shell run-as com.rokid.terminal ls files/` shows per-conversation files; old `scrollback_<endpoint>.txt` migrated on first bind.
7. **Manual /resume convergence**: type `/resume` via the palette passthrough (or speech), pick a session in Claude's native picker, wait ≤30 s → toast "已切换会话" and local files re-keyed.
8. **Busy switch**: run a long task, switch conversation → task interrupted, conversation preserved, switch completes.
9. **Failure path**: temporarily rename the helper on the server → connect falls back to legacy launch (new conversation in workspace).
10. **Privacy**: `adb logcat -s RokidTerminal` shows no titles/session IDs/conversation content.

## Self-review notes (2026-08-08)

- Spec §3.1 protocol matches Tasks 2+5 verb-for-verb (`list <base>`/`status <session>`/`switch <session> <base> <dir> <resume:id|new:uuid>`, `F`/`S`/`pid`/`ok`/`error` lines).
- Spec §3.2 entry points: connect picker (Task 9 connectSelected), palette `[切换对话]` (Task 8 Step 4), folder level from the filesystem (Task 5 `list_base`), `baseDir` = `workspace` (Global Constraints).
- Spec §3.2 scrollback re-keying + bounds + LRU (Task 3), binding + legacy migration (Task 9 Step 4), `--session-id` for new sessions (Task 5 launch_args; Task 9 uuid generation).
- Spec §3.3 sync guarantee: pinned launch (Task 5), post-switch verification inside `switch` (Task 5), 30 s watcher (Task 9 Step 5).
- Spec §4 security: fixed binary + whitelisted args (Tasks 5/6), path-under-base + charset validation (Task 5), no message bodies (Task 5), no titles/session ids in logcat (Task 10 + checklist item 10).
- Spec §5 edge cases: busy-switch banner (Task 9 `setState("SWITCHING…")`), version prerequisite (Global Constraints + deploy step), `status` `none` keeps binding (Task 9 Step 5 guard), switch failure rebinds nothing in-session / legacy fallback at connect (Task 9 Step 3).
