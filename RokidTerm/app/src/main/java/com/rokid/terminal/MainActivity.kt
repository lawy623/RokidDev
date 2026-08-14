package com.rokid.terminal

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.app.Activity
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MainActivity : Activity() {
    private enum class Mode { ENDPOINTS, TERMINAL, COMPOSER }

    private lateinit var terminalView: TerminalView
    private lateinit var inputHistory: InputHistory
    private var keyboardConnected = false
    private var ringConnected = false
    private var historyPreview: String? = null
    private var terminalColumns = TerminalSpec.DEFAULT_COLUMNS
    private val terminalOutput = TerminalOutputProcessor()
    private lateinit var ssh: SshTerminalSession
    private lateinit var speech: SpeechInput
    private lateinit var asr: AsrController
    private lateinit var endpointStore: EndpointStore
    private lateinit var traceRecorder: TerminalTraceRecorder
    private val composer = InputComposerState()

    /**
     * COIDEA knob letter/digit pickers (user design 2026-08-14): left knob
     * = a-zA-Z, right knob = 0-9. At most one is ACTIVE at a time — the
     * last-rotated knob wins (only one candidate can render at the cursor);
     * the 1 s stop commits the candidate as normal text.
     */
    private val letterPicker = KnobPicker(KnobPicker.LETTERS)
    private val digitPicker = KnobPicker(KnobPicker.DIGITS)
    private var activePicker: KnobPicker? = null
    private var knobConfirmRunnable: Runnable = Runnable {}
    private var knobConfirmPending = false
    private val speechDraft = SpeechDraftState(composer)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var endpoints: List<EndpointProfile> = emptyList()
    private var selectedIndex = 0
    private var activeEndpoint: EndpointProfile? = null

    /** Last endpoint bound to a conversation — persists the scrollback even
     *  after the picker cleared [activeEndpoint] (bug 2026-08-14). */
    private var lastBoundEndpoint: EndpointProfile? = null
    private var mode = Mode.ENDPOINTS
    private var composerStatus = ""
    private var asrStatus = ""

    /**
     * Development-only send simulation: long-press does not write to the remote
     * PTY; it reports success via a toast so input/ASR flows can be tested
     * without interacting with the real Claude Code session. Set false for
     * real sends (and remove before release).
     */
    private val simulateSend: Boolean = false
    private var activePrimaryKey: Int? = null
    private var primaryLongTriggered = false
    private var pendingPrimarySingle: Runnable? = null
    private val primaryLongRunnable = Runnable { triggerPrimaryLongPress() }
    @Volatile
    private var sshState = "DISCONNECTED"
    private val pendingTerminalFrame = AtomicReference<TerminalFrame?>(null)
    private val terminalFrameScheduled = AtomicBoolean(false)
    private var lastSuggestion: String? = null

    private val palette = CommandPaletteState()
    private var paletteOpenedBySlash = false
    private var paletteFetchInFlight = false
    private var paletteFetchDone = false
    private var commandFetcher: ServerCommandFetcher? = null
    private val sessionPicker = SessionPickerState()
    private var sessionPickerConnectMode = false
    private var sessionFetcher: ServerSessionFetcher? = null

    /** Last fetched folder list; shown instantly on the next picker open. */
    private var cachedFolders: List<RemoteFolder>? = null

    /** Swipe pair-dedup for the picker (fast swipes emit DPAD pairs). */
    private var lastPickerSwipe: String? = null
    private var lastPickerSwipeTime = 0L

    /** True while a conversation switch is in flight — input is locked
     *  (composer/picker/sends/Back), like the delete in-flight state
     *  (user 2026-08-07). */
    private var switchInFlight = false

    /** Battery HUD state (user 2026-08-12): -1 = unknown (not drawn). */
    private var batteryLevel = -1
    private var batteryCharging = false

    /** Battery HUD (user 2026-08-12): push-updated via the sticky
     *  ACTION_BATTERY_CHANGED broadcast (fires immediately on register). */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (scale > 0 && level >= 0) batteryLevel = level * 100 / scale
            val status = intent.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN,
            )
            batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            terminalView.setBattery(batteryLevel, batteryCharging)
        }
    }

    /** Terminal-history swipe pair-dedup (TP fast swipes emit DPAD pairs). */
    private var lastTerminalSwipe: String? = null
    private var lastTerminalSwipeTime = 0L

    /** Last ctrl+c send (nanoTime) — double-ctrl+c must never exit Claude. */
    private var lastCtrlCNanos = 0L

    /**
     * Sends a single ctrl+c to the PTY; a second one within CTRL_C_DEDUP_NANOS
     * is dropped, so Claude Code's double-ctrl+c session exit can never fire
     * from the glasses (user 2026-08-07; the desktop habit is not wanted
     * here).
     */
    private fun sendCtrlC() {
        val now = System.nanoTime()
        if (now - lastCtrlCNanos < CTRL_C_DEDUP_NANOS) return
        lastCtrlCNanos = now
        ssh.sendCharacters("\u0003")
    }

    /** Same-direction dedup for terminal-history swipes (120 ms window). */
    private fun terminalSwipeAllowed(arrow: String): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (arrow == lastTerminalSwipe && now - lastTerminalSwipeTime < SWIPE_PAIR_DEDUP_MS) {
            return false
        }
        lastTerminalSwipe = arrow
        lastTerminalSwipeTime = now
        return true
    }

    /** Start of the fresh-switch grace window (watcher rebinds suppressed). */
    private var lastSwitchNanos = 0L

    /**
     * True while the bound conversation is a freshly created chat whose real
     * server session id has not converged yet (the JSONL only appears on the
     * first message). While pending, the sync watcher must NOT rebind: the
     * server's "newest session" is still the previous conversation, and a
     * rebind would import ITS scrollback into the new chat (user report
     * 2026-08-07). Cleared when discover converges or the next switch starts.
     */
    private var newSessionPending = false
    private var newSessionFolderPath: String? = null

    private var lastScrollbackCount = -1
    private var lastPersistedRows = 0
    private var scrollbackStore: ScrollbackStore? = null
    /** Cells of the last frame handed to the View (status-tick suppression). */
    private var renderedFrameCells: List<List<TerminalCell>>? = null
    private var scrollbackFolderKey: String? = null
    private var scrollbackSessionId: String? = null

    // Lazy: field initializers run before attachBaseContext, so a direct
    // getSharedPreferences here NPEs at activity instantiation (crash fixed
    // 2026-08-07, caught on device).
    private val prefs by lazy { getSharedPreferences(SESSION_PREFS, MODE_PRIVATE) }

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

    /** Idle-conversation sweep (design 2026-08-11 §3.6): ends idle background
     *  conversations every SWEEP_INTERVAL_MS while connected. Single-flight;
     *  runs on its own thread (the sweep's CPU sampling takes minutes). */
    @Volatile
    private var sweepInFlight = false
    private val sweepRunnable = object : Runnable {
        override fun run() {
            runSweep()
            mainHandler.postDelayed(this, SWEEP_INTERVAL_MS)
        }
    }

    private val drainTerminalFrame = Runnable {
        // Status-tick suppression (2026-08-14): Claude's thinking/tool
        // status row repaints ~1/s with ONLY that row changing — rendering
        // every tick (a full 54×36 grid redraw on the glasses) made the
        // real-time stream janky. Publish a frame only when a non-status
        // row changed; ticking rows stay frozen on screen (the user still
        // sees "✻ Combobulating…", it just doesn't animate).
        val frame = pendingTerminalFrame.getAndSet(null)
        if (frame != null && hasRenderableChange(frame)) {
            terminalView.setTerminalFrame(frame)
            renderedFrameCells = frame.cells
        }
        // Diagnostics: how fast is scrollback growing (alternate-screen capture)?
        val sb = terminalOutput.scrollbackRows
        if (sb != lastScrollbackCount) {
            lastScrollbackCount = sb
            android.util.Log.i("RokidTerminal", "scrollback rows: $sb")
        }
        // Claude Code's next-input suggestion lives on the "❯" line; update
        // the history suggestion slot when the line text changes.
        val suggestion = terminalView.inputLineText()
        if (suggestion != lastSuggestion) {
            lastSuggestion = suggestion
            inputHistory.setSuggestion(suggestion)
        }
        updateAskPanelState()
        // Incremental scrollback persistence (2026-08-14): write the file
        // as the in-memory history grows (every PERSIST_INCREMENT_ROWS new
        // rows) so an abnormal exit (process kill, lost onDestroy) loses at
        // most the last few dozen rows instead of the whole session. The
        // exit-time persist remains the final write.
        if (sshState == "CONNECTED" && sb >= lastPersistedRows + PERSIST_INCREMENT_ROWS) {
            persistScrollback()
            lastPersistedRows = terminalOutput.scrollbackRows
        }
        // Panel-mode auto-exit runs on its own poller (panelExitRunnable,
        // reply-signal design 2026-08-07): it exits when Claude's reply has
        // rendered and holds while a numbered picker is on screen.
        // Turn-end persistence (2026-08-14): restart the quiet window on
        // every published frame; when the stream stays quiet for
        // TURN_SETTLE_MS the conversation turn is over (response done, the
        // user is reading/typing the next prompt — an invisible write
        // point) and the file is written. The delta guard makes mid-turn
        // "cooking" waits no-ops (lastPersistedRows is already current).
        mainHandler.removeCallbacks(persistOnSettle)
        mainHandler.postDelayed(persistOnSettle, TURN_SETTLE_MS)
        terminalFrameScheduled.set(false)
        if (pendingTerminalFrame.get() != null) scheduleTerminalFrame()
    }

    /**
     * Turn-end persist (2026-08-14): one-shot quiet-settle write, restarted
     * per frame by [drainTerminalFrame]. Fires when the conversation output
     * has been quiet for TURN_SETTLE_MS — the end of a turn. Writes only if
     * new rows were captured since the last persist.
     */
    private val persistOnSettle = object : Runnable {
        override fun run() {
            val sb = terminalOutput.scrollbackRows
            // != not >: the settle-trim inside persistScrollback can SHRINK
            // the scrollback (dropping screen-duplicate rows), and the file
            // must be rewritten without them (bug 2026-08-14: the file kept
            // the current turn's repaint copies).
            if (sshState == "CONNECTED" && sb != lastPersistedRows) {
                persistScrollback()
                lastPersistedRows = terminalOutput.scrollbackRows
            }
        }
    }

    /**
     * Whether [frame] changes anything the user should see, vs. being a
     * status-tick repaint. Compares cells against the last rendered frame;
     * a frame whose only differing rows are Claude status rows (in both the
     * old and the new text) is not worth a full grid redraw.
     */
    private fun hasRenderableChange(frame: TerminalFrame): Boolean {
        val prev = renderedFrameCells ?: return true
        if (prev.size != frame.cells.size) return true
        for (row in frame.cells.indices) {
            val old = rowText(prev[row])
            val new = rowText(frame.cells[row])
            if (old != new) {
                // Suppress only PURE animation (spinner/thinking rows with
                // no timer). Timer rows must keep repainting — the elapsed
                // time is information the user needs live (2026-08-14).
                if (!ClaudeStatusRows.isRenderSuppressible(old) || !ClaudeStatusRows.isRenderSuppressible(new)) {
                    return true
                }
            }
        }
        return false
    }

    private fun rowText(row: List<TerminalCell>): String =
        row.joinToString("") { cell -> if (cell.continuation) "" else cell.text }.trimEnd()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Screenshot protection (enabled 2026-08-14 after the README
        // screenshots were captured — open-source hardening). The window
        // content is no longer capturable via ADB screencap.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        endpointStore = EndpointStore(this)
        traceRecorder = TerminalTraceRecorder(filesDir)
        importPendingProfile()

        terminalView = TerminalView(this)
        // Unbound placeholder; bindScrollback rebinds it per conversation
        // (input history is keyed per conversation, user 2026-08-07).
        inputHistory = InputHistory(filesDir)

        ssh = SshTerminalSession(
            onState = { value ->
                sshState = value
                android.util.Log.i("RokidTerminal", "SSH state: $value")
                // Persist the captured history when the link drops so it is
                // not lost; reconnects to the same tmux session repaint the
                // screen but the scrollback would otherwise reset.
                if (value.startsWith("DISCONNECTED") || value.startsWith("ERROR")) {
                    persistScrollback()
                }
                runOnUiThread { updateHeader() }
            },
            onOutput = { value ->
                val frame = terminalOutput.consume(value)
                traceRecorder.recordFrame(frame)
                publishTerminalFrame(frame)
            },
            traceRecorder = traceRecorder,
        )
        terminalView.setOnViewportChangedListener { viewport ->
            terminalColumns = viewport.columns
            publishTerminalFrame(terminalOutput.resize(viewport.columns, viewport.rows))
            ssh.updateViewport(viewport)
        }
        setContentView(terminalView)
        terminalView.post { terminalView.requestFocus() }
        terminalView.post { updateKeyboardIndicator() }
        scrollbackStore = ScrollbackStore(filesDir)
        mainHandler.post(sessionSyncRunnable)
        mainHandler.postDelayed(sweepRunnable, SWEEP_INTERVAL_MS)
        mainHandler.post(panelExitRunnable)
        // Sticky broadcast: delivers the current state immediately on
        // registration, then on every change (battery HUD, user 2026-08-12).
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(
            inputDeviceReceiver,
            android.content.IntentFilter(ACTION_INPUT_DEVICE_CHANGED),
        )
        mainHandler.post(keyboardPoll)

        speech = SpeechInput(
            context = this,
            onState = { value ->
                runOnUiThread {
                    if (mode != Mode.COMPOSER) return@runOnUiThread
                    if (value.startsWith("ASR ") || value.startsWith("NO SPEECH")) {
                        // Keep the latest visible partial as editable draft text.
                        speechDraft.commitActive()
                    }
                    refreshComposer(value)
                }
            },
            onPartialText = { value ->
                runOnUiThread {
                    if (mode == Mode.COMPOSER && speechDraft.updatePartial(value)) {
                        refreshComposer("LISTENING / PARTIAL")
                    }
                }
            },
            onFinalText = { value ->
                runOnUiThread {
                    if (mode == Mode.COMPOSER) {
                        speechDraft.finalize(value)
                        refreshComposer("VOICE INSERTED / HOLD TO SEND")
                    }
                }
            },
        )

        asr = AsrController(
            context = this,
            onStatus = { value ->
                runOnUiThread {
                    android.util.Log.i("RokidTerminal", "ASR status: $value")
                    if (value.startsWith("ASR ")) {
                        asrStatus = shortAsrStatus(value)
                        updateHeader()
                    }
                    if (mode == Mode.COMPOSER) refreshComposer(value)
                }
            },
            onResult = { value ->
                runOnUiThread {
                    // Never log the recognized text (security invariant).
                    android.util.Log.i("RokidTerminal", "ASR result received")
                    if (mode == Mode.COMPOSER) {
                        speechDraft.finalize(value)
                        refreshComposer("VOICE INSERTED / HOLD TO SEND")
                    }
                }
            },
            onError = { value ->
                runOnUiThread {
                    android.util.Log.w("RokidTerminal", "ASR error: $value")
                    if (value.startsWith("ASR ")) {
                        asrStatus = "ASR FAIL"
                        updateHeader()
                    }
                    if (mode == Mode.COMPOSER) refreshComposer(value)
                }
            },
        )

        ensureAudioPermission()
        showEndpointPicker()
    }

    /**
     * Current firmware converts the TP long press and the Shutter button into
     * ordered system broadcasts (ACTION_AI_START / ACTION_SPRITE_BUTTON_UP)
     * before any KeyEvent reaches the app. The broadcasts are sent with
     * FLAG_RECEIVER_REGISTERED_ONLY, so a manifest receiver never fires; a
     * foreground-only, high-priority DYNAMIC receiver intercepts them instead
     * (the same pattern RokidMusic verified on this firmware). Aborting keeps
     * the system assistant/camera from launching while this app is visible.
     */
    private val systemKeyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                ACTION_AI_START -> {
                    android.util.Log.i("RokidTerminal", "system key intercepted: long-press")
                    if (isOrderedBroadcast) abortBroadcast()
                    handleSystemKeyAction(ACTION_LONG_PRESS)
                }
                ACTION_SPRITE_BUTTON_UP -> {
                    android.util.Log.i("RokidTerminal", "system key intercepted: shutter")
                    if (isOrderedBroadcast) abortBroadcast()
                    handleSystemKeyAction(ACTION_SHUTTER)
                }
            }
        }
    }

    /**
     * Fires when an input device (e.g. a Bluetooth keyboard) is added or
     * removed, so the keyboard indicator follows connection state even with
     * no key events in between.
     */
    private val inputDeviceReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == ACTION_INPUT_DEVICE_CHANGED) {
                updateKeyboardIndicator()
            }
        }
    }

    /**
     * Fallback polling for the keyboard indicator: this firmware does not
     * deliver INPUT_DEVICE_CHANGED, and without key events in between the
     * indicator would stay stale. A 1 s poll of the small InputDevice list is
     * negligible.
     */
    private val keyboardPoll = object : Runnable {
        override fun run() {
            updateKeyboardIndicator()
            mainHandler.postDelayed(this, KEYBOARD_POLL_MS)
        }
    }

    private var systemKeyReceiverRegistered = false

    override fun onStart() {
        super.onStart()
        if (!systemKeyReceiverRegistered) {
            val filter = android.content.IntentFilter().apply {
                addAction(ACTION_AI_START)
                addAction(ACTION_SPRITE_BUTTON_UP)
                priority = 1000
            }
            registerReceiver(systemKeyReceiver, filter)
            systemKeyReceiverRegistered = true
        }
    }

    override fun onStop() {
        persistScrollback()
        if (systemKeyReceiverRegistered) {
            runCatching { unregisterReceiver(systemKeyReceiver) }
            systemKeyReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        persistScrollback()
        mainHandler.removeCallbacks(keyboardPoll)
        mainHandler.removeCallbacks(sessionSyncRunnable)
        mainHandler.removeCallbacks(sweepRunnable)
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { unregisterReceiver(inputDeviceReceiver) }
        clearPrimaryGesture()
        speech.destroy()
        asr.destroy()
        ssh.disconnect()
        super.onDestroy()
    }

    /**
     * Semantic actions forwarded by [RokidSystemKeyReceiver] — the firmware
     * consumes the physical long-press and Shutter key before any KeyEvent
     * reaches the app, so their ordered system broadcasts are the only signal.
     */
    private fun handleSystemKeyAction(action: Int) {
        // Switch in flight: the long-press/Shutter broadcasts are consumed
        // (no ESC/ctrl+c to the dying pane, 2026-08-07).
        if (switchInFlight) return
        if (sessionPicker.open) {
            // Strict isolation: only the long-press broadcast acts (delete
            // selector arm); the Shutter broadcast is consumed.
            if (action == ACTION_LONG_PRESS) sessionPickerArmDelete()
            return
        }
        when (action) {
            ACTION_LONG_PRESS -> {
                if (mode == Mode.COMPOSER) {
                    sendComposer()
                } else if (panelMode) {
                    // Part 3: TP long press = confirm. Routed through
                    // panelConfirm so AskUserQuestion multi-select/tab
                    // panels get their submit sequence (2026-08-10) — a
                    // bare Enter on those toggles/selects instead of
                    // submitting (user report 2026-08-10).
                    if (sshState == "CONNECTED") panelConfirm()
                } else {
                    // Terminal mode: fill Claude's suggested next input as a
                    // preview (never sends directly). The 2026-08-06
                    // reassignment to ESC was not user-requested — restored
                    // 2026-08-10. An ESC-to-PTY path stays in the codebase
                    // for a future stale-picker recovery gesture, currently
                    // unbound (user decision 2026-08-10).
                    fillSuggestion()
                }
            }
            ACTION_SHUTTER -> {
                if (mode == Mode.COMPOSER) {
                    // Single press = immediate grapheme delete (never part of
                    // an arbitration window, contract); a second press within
                    // 500 ms = command palette (implemented 2026-08-06).
                    handleComposerShutterPress()
                } else if (!panelMode) {
                    // Terminal mode: single press = ctrl+c to the PTY,
                    // double press = return to live/bottom (offset -> 0).
                    // Same 500 ms window as the COIDEA right knob (contract).
                    // Blocked entirely while the panel is open (strict
                    // isolation — only nav/confirm/cancel act there).
                    handleTerminalShutterPress()
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            InputEventDiagnostics.log(event, mode.name)
        }
        updateKeyboardIndicator()
        return super.dispatchKeyEvent(event)
    }

    /**
     * Zero-permission external-device detection via the InputManager device
     * list: the TP (ROKID,PSOC-TP-R) is excluded by name; keyboards
     * (COIDEA KM etc.) light the keyboard glyph, INMO rings the ring glyph.
     */
    private fun updateKeyboardIndicator() {
        val devices = ArrayList<InputDevice>()
        for (id in InputDevice.getDeviceIds()) InputDevice.getDevice(id)?.let(devices::add)
        val keyboard = devices.any { d ->
            !d.isVirtual && d.isExternal &&
                (d.sources and InputDevice.SOURCE_KEYBOARD) != 0 &&
                !d.name.contains("ROKID", ignoreCase = true) &&
                !d.name.contains("INMO", ignoreCase = true)
        }
        val ring = devices.any { d -> d.name.contains("INMO", ignoreCase = true) }
        if (keyboard != keyboardConnected) {
            keyboardConnected = keyboard
            android.util.Log.i("RokidTerminal", "keyboard indicator: $keyboard")
            terminalView.setKeyboardConnected(keyboard)
        }
        if (ring != ringConnected) {
            ringConnected = ring
            android.util.Log.i("RokidTerminal", "ring indicator: $ring")
            terminalView.setRingConnected(ring)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (sessionPicker.open && handleSessionPickerKey(keyCode, event)) return true
        // Conversation switch in flight: ALL input is locked (user
        // 2026-08-07) — ctrl+c, disconnect, ESC, GO arbitration included.
        if (switchInFlight) return true
        // While a command/ask panel is open in its OPTION state the primary
        // key (TP single click = DPAD_CENTER / ENTER) must reach the
        // panel's own handler (multi-select toggle, 2026-08-10) — the
        // single/double/long arbitration below would swallow it. The
        // composer sub-state (askPanelComposer) keeps the arbitration:
        // single = record, double = cancel back to the panel, long = send
        // (2026-08-10).
        if (mode != Mode.ENDPOINTS && !(panelMode && mode == Mode.TERMINAL) && isPrimaryKey(keyCode)) {
            return handlePrimaryKeyDown(keyCode, event)
        }
        return when (mode) {
            Mode.ENDPOINTS -> handleEndpointKey(keyCode, event)
            Mode.TERMINAL -> handleTerminalKey(keyCode, event)
            Mode.COMPOSER -> handleComposerKey(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // The picker consumes every key-up; GO still arbitrates so its
        // double press can cancel the picker (2026-08-07).
        if (sessionPicker.open) {
            if (isRingKey(event) && keyCode == KeyEvent.KEYCODE_F8) handleGoKey(event)
            return true
        }
        // Switch in flight: consume every key-up (incl. GO arbitration).
        if (switchInFlight) return true
        // GO button arbitration (single/double/long press) resolves on key UP.
        if (isRingKey(event) && keyCode == KeyEvent.KEYCODE_F8) {
            handleGoKey(event)
            return true
        }
        if (isPrimaryKey(keyCode) && (activePrimaryKey == keyCode || mode != Mode.ENDPOINTS)) {
            return handlePrimaryKeyUp(keyCode)
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        // Strict isolation: characters never reach the PTY/composer while
        // the conversation picker is open or a switch is in flight.
        if (sessionPicker.open || switchInFlight) return true
        event.characters?.takeIf { it.isNotEmpty() }?.let { characters ->
            when (mode) {
                Mode.COMPOSER -> {
                    prepareManualComposerEdit()
                    composer.insertText(characters)
                    refreshComposer("EDITING / CLICK TO LISTEN")
                    return true
                }
                Mode.TERMINAL -> if (sshState == "CONNECTED" && !switchInFlight) {
                    ssh.sendCharacters(characters)
                    return true
                }
                Mode.ENDPOINTS -> Unit
            }
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    private fun publishTerminalFrame(frame: TerminalFrame) {
        while (true) {
            val pending = pendingTerminalFrame.get()
            if (pending != null && pending.revision >= frame.revision) return
            if (pendingTerminalFrame.compareAndSet(pending, frame)) break
        }
        scheduleTerminalFrame()
    }

    private fun scheduleTerminalFrame() {
        if (terminalFrameScheduled.compareAndSet(false, true)) {
            terminalView.post(drainTerminalFrame)
        }
    }

    private fun handleEndpointKey(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                moveSelection(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveSelection(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount == 0) connectSelected()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** True when the event comes from the COIDEA KM Bluetooth keyboard. */
    private fun isCoideaKey(event: KeyEvent): Boolean =
        event.device?.name?.contains("COIDEA") == true

    /** True when the event comes from an INMO ring. */
    private fun isRingKey(event: KeyEvent): Boolean =
        event.device?.name?.contains("INMO") == true

    private fun isRingEvent(event: KeyEvent): Boolean = isRingKey(event)

    /**
     * INMO Ring4 semantic dispatch (contract in rules/input.md). Touchpad
     * actions are distinct keys; the GO button is always KEY_F8 and is
     * arbitrated by hold duration and press interval.
     */
    private fun handleRingTerminalKey(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount == 0) openComposer()
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                if (event.repeatCount == 0) publishTerminalFrame(terminalOutput.returnToLive())
                return true
            }
            // Ring swipe reports INVERTED keycodes (left swipe = KEY_RIGHT,
            // right swipe = KEY_LEFT); swap so the user's gesture matches
            // semantics: left swipe = older, right swipe = newer.
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                publishTerminalFrame(terminalOutput.scrollNewer())
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                publishTerminalFrame(terminalOutput.scrollOlder())
                return true
            }
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MOVE_HOME -> {
                // Touchpad long press in terminal mode: fill Claude's
                // suggested next input as a preview (user decision 2026-08-06).
                if (event.repeatCount == 0) fillSuggestion()
                return true
            }
            KeyEvent.KEYCODE_F8 -> {
                handleGoKey(event)
                return true
            }
        }
        return false
    }

    private fun handleRingComposerKey(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                // Ring touchpad single: confirm the palette when open,
                // otherwise start/toggle listening.
                if (event.repeatCount == 0) {
                    if (palette.open) confirmPaletteSelection() else startSpeech()
                }
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                if (event.repeatCount == 0) {
                    prepareManualComposerEdit()
                    composer.deletePrevious()
                }
                refreshComposer("EDITING / CLICK TO LISTEN")
                return true
            }
            // Same inversion correction as terminal mode: left swipe reports
            // DPAD_RIGHT but must move the cursor left.
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // Palette open: right-swipe (arrives as LEFT) = next item.
                if (palette.open) {
                    paletteMove(1)
                    return true
                }
                prepareManualComposerEdit()
                composer.moveRight()
                refreshComposer("EDITING / CLICK TO LISTEN")
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Palette open: left-swipe (arrives as RIGHT) = previous item.
                if (palette.open) {
                    paletteMove(-1)
                    return true
                }
                prepareManualComposerEdit()
                composer.moveLeft()
                refreshComposer("EDITING / CLICK TO LISTEN")
                return true
            }
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MOVE_HOME -> {
                // Ring touchpad long press in composer = send (swapped with
                // GO single, user decision 2026-08-06 — matches the Rokid TP
                // long-press send).
                if (event.repeatCount == 0) sendComposer()
                return true
            }
            KeyEvent.KEYCODE_F8 -> {
                handleGoKey(event)
                return true
            }
        }
        return false
    }

    private var goKeyDownTime = 0L
    private var goDoublePending: Runnable? = null

    /**
     * GO button (KEY_F8) arbitration: hold >= 800 ms = long press
     * (terminal: ctrl+c); else second press within 500 ms = double press
     * (terminal: Back/disconnect, composer: cancel); else single press
     * (composer: send, terminal: no-op).
     */
    private fun handleGoKey(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) goKeyDownTime = android.os.SystemClock.uptimeMillis()
            }
            KeyEvent.ACTION_UP -> {
                val hold = android.os.SystemClock.uptimeMillis() - goKeyDownTime
                if (hold >= GO_LONG_PRESS_MS) {
                    // Long press: ctrl+c normally; blocked in panel mode
                    // (strict isolation — GO single is the panel cancel).
                    if (mode == Mode.TERMINAL && !panelMode && !sessionPicker.open && sshState == "CONNECTED") {
                        sendCtrlC()
                    }
                    return
                }
                val pending = goDoublePending
                if (pending != null) {
                    mainHandler.removeCallbacks(pending)
                    goDoublePending = null
                    when {
                        sessionPicker.open -> sessionPickerCancel()
                        mode == Mode.TERMINAL && panelMode -> {
                            // Part 3: GO double = cancel & return (ESC + exit).
                            // AskUserQuestion panels are NOT cancellable —
                            // they must be answered (user decision 2026-08-10).
                            if (!askPanelMode) {
                                ssh.sendEscape()
                                cancelPanelMode()
                            }
                        }
                        mode == Mode.TERMINAL -> {
                            ssh.disconnect()
                            asr.disconnect()
                            showEndpointPicker()
                        }
                        mode == Mode.COMPOSER -> cancelComposer("CANCELLED")
                    }
                } else {
                    val single = Runnable {
                        goDoublePending = null
                        if (sessionPicker.open) {
                            // no-op: GO single does nothing in the picker
                        } else if (mode == Mode.COMPOSER) {
                            // Composer: GO single = command palette (swapped
                            // with touchpad long press, user decision
                            // 2026-08-06). GO single is blocked in panel mode.
                            toggleCommandPalette()
                        }
                    }
                    goDoublePending = single
                    mainHandler.postDelayed(single, RIGHT_KNOB_DOUBLE_WINDOW_MS)
                }
            }
        }
    }

    /**
     * COIDEA KM semantic dispatch (contract in rules/input.md). Only keys
     * produced by the COIDEA keyboard are remapped; other devices keep
     * their generic behavior.
     */
    private fun handleCoideaTerminalKey(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_1 -> {
                // Return to live/bottom (offset -> 0); leaves history preview.
                clearHistoryPreview()
                publishTerminalFrame(terminalOutput.returnToLive())
                return true
            }
            KeyEvent.KEYCODE_2 -> {
                // Terminal-history browsing dismisses any input-history preview.
                clearHistoryPreview()
                publishTerminalFrame(terminalOutput.scrollOlder())
                return true
            }
            KeyEvent.KEYCODE_5 -> {
                clearHistoryPreview()
                publishTerminalFrame(terminalOutput.scrollNewer())
                return true
            }
            KeyEvent.KEYCODE_3 -> {
                if (event.repeatCount == 0) sendCtrlC()
                return true
            }
            KeyEvent.KEYCODE_4 -> {
                if (event.repeatCount == 0) browseInputHistory(-1)
                return true
            }
            KeyEvent.KEYCODE_6 -> {
                if (event.repeatCount == 0) browseInputHistory(1)
                return true
            }
            KeyEvent.KEYCODE_8 -> {
                if (event.repeatCount == 0) openComposer()
                return true
            }
            KeyEvent.KEYCODE_D -> {
                // Right knob (cancel side) in terminal mode: single = ctrl+c,
                // double = exit terminal (Back), 500 ms window (user decision
                // 2026-08-06).
                if (event.repeatCount == 0) handleTerminalRightKnobPress()
                return true
            }
        }
        return false
    }

    private var terminalRightKnobDoublePending: Runnable? = null

    private fun handleTerminalRightKnobPress() {
        val pending = terminalRightKnobDoublePending
        if (pending != null) {
            mainHandler.removeCallbacks(pending)
            terminalRightKnobDoublePending = null
            ssh.disconnect()
            asr.disconnect()
            showEndpointPicker()
        } else {
            val single = Runnable {
                terminalRightKnobDoublePending = null
                if (sshState == "CONNECTED") sendCtrlC()
            }
            terminalRightKnobDoublePending = single
            mainHandler.postDelayed(single, RIGHT_KNOB_DOUBLE_WINDOW_MS)
        }
    }

    private fun handleTerminalKey(keyCode: Int, event: KeyEvent): Boolean {
        // Part 3 (command panel): while Claude's own picker/menu is open the
        // directional/confirm/cancel keys pass through to the PTY instead of
        // browsing local history or acting on the terminal.
        if (panelMode) {
            if (handlePanelKey(keyCode, event)) return true
        }
        if (isCoideaKey(event) && handleCoideaTerminalKey(keyCode, event)) return true
        if (isRingKey(event) && handleRingTerminalKey(keyCode, event)) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                // TP fast swipes emit DPAD PAIRS — dedup like the picker or
                // one swipe scrolls 6 rows instead of 3 (user 2026-08-07).
                if (terminalSwipeAllowed(ARROW_UP)) {
                    publishTerminalFrame(terminalOutput.scrollOlder())
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (terminalSwipeAllowed(ARROW_DOWN)) {
                    publishTerminalFrame(terminalOutput.scrollNewer())
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                // Locked during a conversation switch: disconnecting
                // mid-respawn would strand the pane (2026-08-07).
                if (!switchInFlight) {
                    persistScrollback()
                    ssh.disconnect()
                    asr.disconnect()
                    showEndpointPicker()
                }
                return true
            }
            KeyEvent.KEYCODE_TV -> {
                ssh.sendEscape()
                return true
            }
            KeyEvent.KEYCODE_NOTIFICATION -> {
                // Generic TP swipe marker (single- and two-finger alike emit
                // 83, verified 2026-08-05; not distinguishable). Directional
                // history scrolling is handled by the DPAD keys above; 83
                // itself is consumed to keep the system Dashboard from
                // firing. Input-history browsing is keyboard-only.
                return true
            }
        }
        if (event.repeatCount == 0 && sshState == "CONNECTED") {
            printableText(event)?.let {
                ssh.sendCharacters(it)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleCoideaComposerKey(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_2 -> {
                // Up: previous visual wrapped line, pixel-aligned with the
                // renderer's wrapping.
                prepareManualComposerEdit()
                if (terminalView.moveCursorVertical(-1)) {
                    composer.setCursor(terminalView.composerCursor())
                    refreshComposer("EDITING / CLICK TO LISTEN")
                }
                return true
            }
            KeyEvent.KEYCODE_4 -> {
                prepareManualComposerEdit()
                composer.moveLeft()
                refreshComposer("EDITING / CLICK TO LISTEN")
                return true
            }
            KeyEvent.KEYCODE_5 -> {
                // Down: next visual wrapped line, pixel-aligned with the
                // renderer's wrapping.
                prepareManualComposerEdit()
                if (terminalView.moveCursorVertical(1)) {
                    composer.setCursor(terminalView.composerCursor())
                    refreshComposer("EDITING / CLICK TO LISTEN")
                }
                return true
            }
            KeyEvent.KEYCODE_6 -> {
                prepareManualComposerEdit()
                composer.moveRight()
                refreshComposer("EDITING / CLICK TO LISTEN")
                return true
            }
            KeyEvent.KEYCODE_3 -> {
                if (event.repeatCount == 0) {
                    prepareManualComposerEdit()
                    composer.deletePrevious()
                }
                refreshComposer("EDITING / SHUTTER DELETE / CLICK TO LISTEN")
                return true
            }
            KeyEvent.KEYCODE_9 -> {
                // Left knob rotate RIGHT — letter picker (user design
                // 2026-08-14); previously the detent typed "9" into the
                // draft. One step per detent (every event, no repeat gate).
                knobRotate(letterPicker, +1)
                return true
            }
            KeyEvent.KEYCODE_7 -> {
                knobRotate(letterPicker, -1)
                return true
            }
            KeyEvent.KEYCODE_E -> {
                // Right knob rotate RIGHT — digit picker (was "E").
                knobRotate(digitPicker, +1)
                return true
            }
            KeyEvent.KEYCODE_C -> {
                knobRotate(digitPicker, -1)
                return true
            }
            KeyEvent.KEYCODE_8 -> {
                // Left knob (confirm side, user decision 2026-08-06):
                // single = recording toggle, double = send, 500 ms window.
                if (event.repeatCount == 0) handleLeftKnobPress()
                return true
            }
            KeyEvent.KEYCODE_D -> {
                // Right knob (cancel side): single = cancel/discard. Double
                // press is a second cancel — harmless (cancelComposer guards
                // on mode), so no arbitration needed.
                if (event.repeatCount == 0) cancelComposer("CANCELLED")
                return true
            }
            KeyEvent.KEYCODE_1 -> {
                // Command palette trigger (contract: composer key 1).
                if (event.repeatCount == 0) toggleCommandPalette()
                return true
            }
        }
        return false
    }

    private fun handleCoideaPaletteKey(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_2 -> {
            paletteMove(-1)
            true
        }
        KeyEvent.KEYCODE_5 -> {
            paletteMove(1)
            true
        }
        KeyEvent.KEYCODE_8 -> {
            // Left knob (confirm side): while the palette is open it confirms
            // the selection instead of toggling recording.
            if (event.repeatCount == 0) confirmPaletteSelection()
            true
        }
        KeyEvent.KEYCODE_D -> {
            if (event.repeatCount == 0) cancelCommandPalette()
            true
        }
        else -> false
    }

    private var pendingRightKnobSingle: Runnable? = null
    private var leftKnobDoublePending: Runnable? = null
    private var pendingShutterSingle: Runnable? = null
    private var composerShutterDoublePending: Runnable? = null

    /**
     * Composer-mode shutter: first press deletes immediately (contract: delete
     * never waits for arbitration); a second press within 500 ms opens the
     * command palette (placeholder) instead of deleting again.
     */
    private fun handleComposerShutterPress() {
        val pending = composerShutterDoublePending
        if (pending != null) {
            mainHandler.removeCallbacks(pending)
            composerShutterDoublePending = null
            toggleCommandPalette()
            return
        }
        prepareManualComposerEdit()
        composer.deletePrevious()
        refreshComposer("EDITING / SHUTTER DELETE / CLICK TO LISTEN")
        val window = Runnable { composerShutterDoublePending = null }
        composerShutterDoublePending = window
        mainHandler.postDelayed(window, RIGHT_KNOB_DOUBLE_WINDOW_MS)
    }

    /**
     * Knob rotation → picker step (user design 2026-08-14). Voice-off
     * only: while recording the draft is voice-owned and rotations are
     * ignored. The other knob's pending candidate is abandoned (only one
     * candidate renders at the cursor); every detent re-arms the 1 s
     * confirmation window — the candidate commits 1 s after the LAST
     * rotation, and rotating at a boundary (before 'a' / after 'Z') is a
     * no-op that still extends the window.
     */
    private fun knobRotate(picker: KnobPicker, direction: Int) {
        if (asr.isListening) return
        if (activePicker != picker) {
            activePicker?.reset()
            activePicker = picker
        }
        if (direction > 0) picker.stepRight() else picker.stepLeft()
        val candidate = picker.candidate()
        armKnobConfirm()
        refreshComposer(
            if (candidate != null) {
                val kind = if (picker === letterPicker) "LETTER" else "DIGIT"
                "$kind $candidate · 1S CONFIRM"
            } else {
                "KNOB PICK · ROTATE"
            },
        )
    }

    /** (Re)arms the 1 s stop-to-confirm window for the active picker. */
    private fun armKnobConfirm() {
        cancelKnobConfirm()
        val picker = activePicker ?: return
        knobConfirmPending = true
        knobConfirmRunnable = Runnable {
            knobConfirmPending = false
            val candidate = picker.candidate() ?: return@Runnable
            picker.reset()
            activePicker = null
            prepareManualComposerEdit()
            composer.insertText(candidate.toString())
            refreshComposer("EDITING / CLICK TO LISTEN")
        }
        mainHandler.postDelayed(knobConfirmRunnable, KNOB_CONFIRM_MS)
    }

    private fun cancelKnobConfirm() {
        knobConfirmPending = false
        mainHandler.removeCallbacks(knobConfirmRunnable)
    }

    /** Any other composer interaction abandons the pending picker — the
     *  uncommitted candidate is never part of the draft text. */
    private fun cancelKnobPicker() {
        cancelKnobConfirm()
        activePicker?.reset()
        activePicker = null
    }

    /** Left knob in composer: single = recording toggle, double = send. */
    private fun handleLeftKnobPress() {
        val pending = leftKnobDoublePending
        if (pending != null) {
            mainHandler.removeCallbacks(pending)
            leftKnobDoublePending = null
            sendComposer()
        } else {
            val single = Runnable {
                leftKnobDoublePending = null
                startSpeech()
            }
            leftKnobDoublePending = single
            mainHandler.postDelayed(single, RIGHT_KNOB_DOUBLE_WINDOW_MS)
        }
    }

    /**
     * Terminal-mode shutter (user decision 2026-08-06, swapped): SINGLE press
     * = return to live/bottom — high-frequency, executed immediately, no
     * arbitration delay; DOUBLE press within 500 ms = ctrl+c interrupt
     * (low-frequency). Previously the frequent single press was misread as
     * ctrl+c, cancelling input.
     */
    private fun handleTerminalShutterPress() {
        val pending = pendingShutterSingle
        if (pending != null) {
            mainHandler.removeCallbacks(pending)
            pendingShutterSingle = null
            if (sshState == "CONNECTED") sendCtrlC()
        } else {
            publishTerminalFrame(terminalOutput.returnToLive())
            val window = Runnable { pendingShutterSingle = null }
            pendingShutterSingle = window
            mainHandler.postDelayed(window, RIGHT_KNOB_DOUBLE_WINDOW_MS)
        }
    }

    private fun handleRightKnobPress() {
        val pending = pendingRightKnobSingle
        if (pending != null) {
            mainHandler.removeCallbacks(pending)
            pendingRightKnobSingle = null
            cancelComposer("CANCELLED")
        } else {
            val send = Runnable {
                pendingRightKnobSingle = null
                sendComposer()
            }
            pendingRightKnobSingle = send
            mainHandler.postDelayed(send, RIGHT_KNOB_DOUBLE_WINDOW_MS)
        }
    }

    private fun handleComposerKey(keyCode: Int, event: KeyEvent): Boolean {
        if (isCoideaKey(event)) {
            // While the palette is open, COIDEA keys drive the list modally.
            if (palette.open && handleCoideaPaletteKey(keyCode, event)) return true
            if (handleCoideaComposerKey(keyCode, event)) return true
        }
        if (isRingKey(event) && handleRingComposerKey(keyCode, event)) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (palette.open) return true
                prepareManualComposerEdit()
                composer.moveLeft()
                refreshComposer("EDITING / CLICK TO LISTEN")
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (palette.open) return true
                prepareManualComposerEdit()
                composer.moveRight()
                refreshComposer("EDITING / CLICK TO LISTEN")
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // TP swipe: palette navigation when open, otherwise reserved.
                paletteMove(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                prepareManualComposerEdit()
                composer.deletePrevious()
                refreshComposer("EDITING / CLICK TO LISTEN")
                return true
            }
            KeyEvent.KEYCODE_CAMERA -> {
                // Standard Android full-shutter event. The exact physical Rokid
                // Shutter/Capture event still has to be verified on this firmware.
                if (event.repeatCount == 0) {
                    prepareManualComposerEdit()
                    composer.deletePrevious()
                }
                refreshComposer("EDITING / SHUTTER DELETE / CLICK TO LISTEN")
                return true
            }
            KeyEvent.KEYCODE_FOCUS -> {
                // Some two-stage shutter controls emit FOCUS before CAMERA. Keep
                // it local while composing, but do not delete twice for one press.
                if (event.repeatCount == 0) {
                    refreshComposer("SHUTTER FOCUS / PRESS FULL")
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (palette.open) cancelCommandPalette() else cancelComposer("CANCELLED")
                return true
            }
            KeyEvent.KEYCODE_TV -> {
                // Older Rokid firmware reports a TP long press as KEYCODE_TV.
                sendComposer()
                return true
            }
        }
        if (event.repeatCount == 0) {
            printableText(event)?.let { value ->
                // "/" at command-prefix position (blank before the cursor)
                // opens the palette; elsewhere it is a literal slash.
                if (value == "/" && composer.text.take(composer.cursor).isBlank()) {
                    openCommandPaletteFromSlash()
                    return true
                }
                prepareManualComposerEdit()
                composer.insertText(value)
                refreshComposer("EDITING / CLICK TO LISTEN")
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handlePrimaryKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0 && activePrimaryKey == null) {
            activePrimaryKey = keyCode
            primaryLongTriggered = false
            mainHandler.postDelayed(primaryLongRunnable, ViewConfiguration.getLongPressTimeout().toLong())
        } else if (event.isLongPress && !primaryLongTriggered) {
            triggerPrimaryLongPress()
        }
        return true
    }

    private fun handlePrimaryKeyUp(keyCode: Int): Boolean {
        if (activePrimaryKey != keyCode) return true
        mainHandler.removeCallbacks(primaryLongRunnable)
        activePrimaryKey = null
        if (primaryLongTriggered) {
            primaryLongTriggered = false
            return true
        }

        val firstClick = pendingPrimarySingle
        if (firstClick != null) {
            mainHandler.removeCallbacks(firstClick)
            pendingPrimarySingle = null
            handlePrimaryDoubleClick()
        } else {
            val single = Runnable {
                pendingPrimarySingle = null
                handlePrimarySingleClick()
            }
            pendingPrimarySingle = single
            mainHandler.postDelayed(single, ViewConfiguration.getDoubleTapTimeout().toLong())
        }
        return true
    }

    private fun triggerPrimaryLongPress() {
        if (activePrimaryKey == null || primaryLongTriggered) return
        mainHandler.removeCallbacks(primaryLongRunnable)
        pendingPrimarySingle?.let(mainHandler::removeCallbacks)
        pendingPrimarySingle = null
        primaryLongTriggered = true
        if (mode == Mode.COMPOSER) sendComposer()
    }

    private fun handlePrimarySingleClick() {
        when (mode) {
            Mode.TERMINAL -> {
                if (switchInFlight) {
                    // Locked during a conversation switch (2026-08-07).
                } else if (panelMode) {
                    // Strict isolation: TP single does nothing while the
                    // panel is open (confirm = TP long, cancel = TP double).
                } else if (sshState.startsWith("ERROR") || sshState == "DISCONNECTED") {
                    reconnectActiveEndpoint()
                } else {
                    openComposer()
                }
            }
            Mode.COMPOSER -> {
                if (palette.open) confirmPaletteSelection() else startSpeech()
            }
            Mode.ENDPOINTS -> Unit
        }
    }

    private fun handlePrimaryDoubleClick() {
        when {
            mode == Mode.COMPOSER -> cancelComposer("CANCELLED")
            mode == Mode.TERMINAL && panelMode -> {
                // Part 3: TP double click = cancel & return (ESC + exit).
                ssh.sendEscape()
                cancelPanelMode()
            }
        }
    }

    private fun openComposer() {
        // Locked while a conversation switch is in flight: input must not
        // reach the dying/restarting pane (user 2026-08-07).
        if (switchInFlight) return
        android.util.Log.i("RokidTerminal", "mode -> COMPOSER (openComposer)")
        panelMode = false
        terminalOutput.captureSuspended = false
        publishTerminalFrame(terminalOutput.returnToLive())
        speechDraft.reset()
        composer.clear()
        historyPreview?.let {
            // Input-history preview (browsed with COIDEA keys 4/6) is loaded
            // into the draft; the preview state is consumed.
            composer.insertText(it)
            historyPreview = null
            terminalView.setHistoryPreview(null)
        }
        mode = Mode.COMPOSER
        refreshComposer("CLICK TO RECORD / LONG TO SEND")
    }

    /**
     * Sends a composer draft to the PTY, then presses Enter.
     *
     * Claude Code's paste-burst detector treats a trailing \r that arrives in
     * the SAME read as long text as a newline: the text shows in the input
     * line but is never submitted (user report 2026-08-07 — 100-500 char
     * drafts "died" on the input line; short drafts worked because the burst
     * window closed before the \r arrived). Long drafts therefore go
     * text-first, with Enter sent alone after the burst window has closed.
     */
    private fun sendTextWithEnter(text: String) {
        if (text.length < LONG_SEND_CHARS) {
            ssh.sendText(text)
        } else {
            ssh.sendCharacters(text)
            mainHandler.postDelayed(
                { if (sshState == "CONNECTED") ssh.sendEnter() },
                SEND_ENTER_DELAY_MS,
            )
        }
        scheduleSubmitVerify(text, if (text.length >= LONG_SEND_CHARS) SEND_ENTER_DELAY_MS else 0L)
    }

    /**
     * Verify-and-retry submit (design 2026-08-13): Claude Code's paste-burst
     * detector swallows a trailing \r that arrives inside its burst window,
     * leaving a long draft stuck on the input line (long texts, network
     * jitter — a fixed delay is a guess that jitter can break). After the
     * initial Enter, watch the rendered input line: a draft that is STILL
     * there after the stream has settled gets a bare retry Enter — fired
     * only after the observed failure, seconds after the burst, so it is
     * provably outside any burst window (deterministic, no window guessing).
     * After [VERIFY_MAX_RETRIES] failed retries the stuck line is cleared
     * with Ctrl+U (Ink TextInput) so it cannot merge into the user's next
     * message. [firstDelayMs] offsets the first check past the initial
     * Enter's own delay (long drafts send Enter separately).
     */
    private fun scheduleSubmitVerify(text: String, firstDelayMs: Long) {
        // Compare against the draft's TAIL: the input line is single-line
        // and horizontally scrolls — only the end of a long draft is
        // visible. 20 chars stays within the ~64-column viewport even for
        // CJK (20 CJK = 40 columns) and can never coincide with Claude's
        // next-input suggestion.
        val tail = text.takeLast(VERIFY_TAIL_CHARS)
        if (tail.isEmpty()) return
        var retries = 0
        fun recheck() {
            val line = terminalView.inputLineText() ?: return   // no text on the line = submitted
            if (!line.endsWith(tail)) return                    // the line moved on = submitted
            if (retries >= VERIFY_MAX_RETRIES) {
                // Give up submitting; clear the stuck line so the draft
                // cannot merge into the user's next message.
                if (sshState == "CONNECTED") ssh.sendCharacters("")   // Ctrl+U
                android.util.Log.w(
                    "RokidTerminal",
                    "input stuck after $retries retries; cleared (len=${text.length})",
                )
                return
            }
            retries++
            if (sshState == "CONNECTED") ssh.sendEnter()
            mainHandler.postDelayed({ recheck() }, VERIFY_RECHECK_MS)
        }
        mainHandler.postDelayed({ recheck() }, firstDelayMs + VERIFY_FIRST_MS)
    }

    /**
     * Sends a Type-something answer (chroxy two-stage protocol, verified
     * against AskUserQuestion's Ink Select/TextInput implementation
     * 2026-08-10):
     * 1. the option's DIGIT — NOT Enter — triggers Select's onSelect for
     *    the Other entry and switches the picker into text-input mode
     *    (Enter on Type something. would call onSubmit with an EMPTY
     *    answer → "User Declined to answer question", user report
     *    2026-08-10);
     * 2. the text, then a delayed Enter submits the buffer (the delay
     *    also keeps a trailing \r out of the paste-burst window).
     * Nothing was sent at composer-open, so the event and the text travel
     * together on send.
     */
    private fun sendTextToAskPanel(text: String) {
        val typeNumber = askPanelTypeNumber ?: return
        ssh.sendCharacters(typeNumber.toString())
        // Stage 1 settle: the picker switches into its text-input mode
        // (chroxy-verified ~150 ms).
        mainHandler.postDelayed({
            if (sshState != "CONNECTED") return@postDelayed
            ssh.sendCharacters(text)
            // Stage 2 settle: the paste-burst window must close before the
            // submit Enter — a trailing \r arriving inside the burst
            // window is swallowed and long drafts stay on the input line
            // without ever submitting (user report 2026-08-10).
            mainHandler.postDelayed(
                { if (sshState == "CONNECTED") ssh.sendEnter() },
                ASK_TEXT_SUBMIT_DELAY_MS,
            )
            // Verify the answer left the input line; retry/clear when the
            // Enter was swallowed (same paste-burst hazard, 2026-08-13).
            scheduleSubmitVerify(text, ASK_TEXT_SUBMIT_DELAY_MS)
        }, ASK_TYPE_SWITCH_DELAY_MS)
    }

    private fun sendComposer() {
        if (mode != Mode.COMPOSER) return
        if (composer.text.isBlank()) {
            refreshComposer("EMPTY INPUT / TYPE OR SPEAK")
            return
        }
        if (sshState != "CONNECTED") {
            refreshComposer("NOT CONNECTED / DRAFT NOT SENT")
            return
        }
        // A pending knob candidate is not part of the draft — sending must
        // not carry it (user design 2026-08-14).
        cancelKnobPicker()
        // Drop any in-flight recording: its audio must never be transcribed
        // into the draft after the text has already been sent.
        asr.cancelRecording()
        speechDraft.commitActive()
        val text = composer.text
        speech.cancel()
        if (simulateSend) {
            android.widget.Toast.makeText(this, "TEST SEND OK: $text", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            // AskUserQuestion Type-something: the picker-into-input Enter
            // is sent HERE with the text (nothing was sent at composer
            // open) — see sendTextToAskPanel (2026-08-10).
            if (askPanelComposer) sendTextToAskPanel(text) else sendTextWithEnter(text)
            // The first message creates the server JSONL: if this chat's
            // cached row is still the "New chat" placeholder, refetch now so
            // the real title appears without exiting the terminal (bug 1
            // follow-up, 2026-08-07).
            refreshNewChatTitleIfNeeded()
            // A long dictation can outlast the 12 s discovery window: the
            // first message just created the JSONL, so restart the loop and
            // converge the binding (clears newSessionPending) — otherwise
            // the sync watcher stays off and /resume-like moves stop being
            // followed (2026-08-07).
            if (newSessionPending) {
                val path = newSessionFolderPath
                val tempId = scrollbackSessionId
                if (path != null && tempId != null) {
                    discoverNewSessionId(path, tempId)
                }
            }
        }
        inputHistory.add(text)
        composer.clear()
        speechDraft.reset()
        palette.close()
        paletteOpenedBySlash = false
        terminalView.setCommandPalette(emptyList(), 0, false)
        android.util.Log.i("RokidTerminal", "mode -> TERMINAL (sendComposer)")
        mode = Mode.TERMINAL
        terminalView.hideComposer()
        // AskUserQuestion sub-state: the text was submitted to Claude's
        // picker; the panel overlay stays until the reply closes it
        // (2026-08-10).
        askPanelComposer = false
        askPanelTypeNumber = null
        // Slash commands open Claude's own picker (e.g. /model): enter the
        // command panel passthrough so navigation keys reach it.
        if (text.startsWith("/")) {
            lastSentCommand = text.trim()
            enterPanelMode()
        }
        updateHeader()
        clearPrimaryGesture()
    }

    private fun cancelComposer(status: String) {
        if (mode != Mode.COMPOSER) return
        cancelKnobPicker()
        // Drop any in-flight recording so it cannot linger or transcribe
        // later; the composer state is also never written to the header.
        asr.cancelRecording()
        speech.cancel()
        speechDraft.discardActive()
        composer.clear()
        speechDraft.reset()
        palette.close()
        paletteOpenedBySlash = false
        terminalView.setCommandPalette(emptyList(), 0, false)
        android.util.Log.i("RokidTerminal", "mode -> TERMINAL (cancelComposer): $status")
        mode = Mode.TERMINAL
        terminalView.hideComposer()
        // AskUserQuestion sub-state: cancelling the composer sends NO key —
        // the panel never left its option state (the Type-something event
        // only travels with the text on send, 2026-08-10), so returning to
        // the panel is a pure local transition.
        if (askPanelComposer) {
            askPanelComposer = false
            askPanelTypeNumber = null
        }
        updateHeader()
        clearPrimaryGesture()
    }

    private fun prepareManualComposerEdit() {
        // Any manual edit abandons the pending knob candidate — it is not
        // committed text yet (user design 2026-08-14).
        cancelKnobPicker()
        // Stop the current recognition round before moving or editing its active
        // hypothesis. This prevents a late partial/final callback from anchoring
        // another span and duplicating text after a manual edit.
        speech.cancel()
        speechDraft.commitActive()
    }

    private fun refreshComposer(status: String? = null) {
        if (mode != Mode.COMPOSER) return
        if (status != null) composerStatus = status
        terminalView.showComposer(
            composer.text, composer.cursor, composerStatus, activePicker?.candidate(),
        )
    }

    // --- Command palette (contract in rules/composer.md) ---

    private fun paletteSyncToView() {
        terminalView.setCommandPalette(palette.items, palette.selectedIndex, palette.open)
    }

    private fun toggleCommandPalette() {
        if (!palette.open) {
            palette.open()
            paletteOpenedBySlash = false
            ensurePaletteCommands()
            paletteSyncToView()
        } else {
            palette.close()
            paletteSyncToView()
        }
    }

    /** "/" typed at command-prefix position opens the palette (contract). */
    private fun openCommandPaletteFromSlash() {
        palette.open()
        paletteOpenedBySlash = true
        ensurePaletteCommands()
        paletteSyncToView()
    }

    /** Confirms the selection: insert "/command " into the draft, close. */
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
        paletteOpenedBySlash = false
        paletteSyncToView()
        prepareManualComposerEdit()
        // The bare "/" item keeps no trailing space so voice input can
        // continue the command name right after the slash.
        composer.insertText(if (command == "/") "/" else "$command ")
        refreshComposer("EDITING / CLICK TO LISTEN")
    }

    /** Cancels without changing the draft; a "/"-opened palette restores the literal slash. */
    private fun cancelCommandPalette() {
        palette.close()
        paletteSyncToView()
        if (paletteOpenedBySlash) {
            prepareManualComposerEdit()
            composer.insertText("/")
            refreshComposer("EDITING / CLICK TO LISTEN")
        }
        paletteOpenedBySlash = false
    }

    private fun paletteMove(delta: Int) {
        if (!palette.open) return
        palette.moveSelection(delta)
        paletteSyncToView()
    }

    // --- Conversation picker (design 2026-08-07; rules/input.md contract) ---

    /**
     * Modal picker keys: navigate (COIDEA 2/4/5/6, TP swipes, Ring swipes
     * with its inverted arrival), confirm (TP single / Ring touchpad single
     * / COIDEA left knob), cancel (Back / COIDEA right knob / Ring GO
     * double via handleGoKey). Strict isolation: everything else is
     * consumed while the picker is open.
     */
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

    private fun handleSessionPickerKey(keyCode: Int, event: KeyEvent): Boolean {
        // Delete round trip in flight: everything is consumed (strict
        // isolation until the result lands, 2026-08-07).
        if (sessionPicker.deleteInFlight) return true
        return when (keyCode) {
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
                // Ring arrivals are inverted: physical right-swipe arrives
                // as DPAD_LEFT = next (+1); physical left-swipe arrives as
                // DPAD_RIGHT = previous (-1).
                val ring = isRingEvent(event)
                val next = when {
                    keyCode == KeyEvent.KEYCODE_DPAD_UP -> false
                    keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> true
                    ring -> keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    else -> keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                }
                // Fast swipes emit DPAD PAIRS (LEFT+UP / RIGHT+DOWN) within a
                // few ms — dedup the same direction like panel mode, or one
                // swipe moves two items (2026-08-07).
                val arrow = if (next) ARROW_DOWN else ARROW_UP
                val now = android.os.SystemClock.uptimeMillis()
                if (arrow == lastPickerSwipe && now - lastPickerSwipeTime < SWIPE_PAIR_DEDUP_MS) {
                    // second half of the pair — skip
                } else {
                    lastPickerSwipe = arrow
                    lastPickerSwipeTime = now
                    if (sessionPicker.deleteArmed) {
                        sessionPickerMoveDeleteOption(if (next) 1 else -1)
                    } else {
                        sessionPickerMove(if (next) 1 else -1)
                    }
                }
            }
            true
        }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_8 -> {
            // TP single = confirm after the double-tap window; a second
            // press within the window = cancel (user contract 2026-08-07).
            // COIDEA 8 doubles also cancel; the ring's touchpad double
            // arrives as KEYCODE_DEL (firmware), so its ENTER never
            // double-fires.
            if (event.repeatCount == 0) pickerPrimaryPressed()
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
                deleteArmed = sessionPicker.deleteArmed,
                deleteOption = sessionPicker.deleteOption,
                deleteInFlight = sessionPicker.deleteInFlight,
            ),
        )
    }

    private fun sessionPickerMove(delta: Int) {
        if (!sessionPicker.open || sessionPicker.deleteInFlight) return
        sessionPicker.move(delta)
        sessionPickerSyncToView()
    }

    private fun sessionPickerArmDelete() {
        if (sessionPicker.armDelete()) {
            sessionPickerSyncToView()
        } else {
            // Tell the user WHY the arm failed (user 2026-08-07): the ▶
            // current conversation is never deletable; anything else (folder
            // level / + New Chat slot) needs a session row selected.
            val onCurrent = sessionPicker.open && sessionPicker.level == 1 &&
                sessionPicker.sessionIndex >= 1 &&
                sessionPicker.selectedFolder()?.sessions
                    ?.getOrNull(sessionPicker.sessionIndex - 1)?.id == sessionPicker.currentSessionId
            android.widget.Toast.makeText(
                this,
                if (onCurrent) "CURRENT SESSION NOT DELETABLE" else "SELECT A SESSION",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun sessionPickerMoveDeleteOption(delta: Int) {
        sessionPicker.moveDeleteOption(delta)
        sessionPickerSyncToView()
    }

    private fun sessionPickerConfirm() {
        pickerPrimaryPending?.let(mainHandler::removeCallbacks)
        pickerPrimaryPending = null
        if (!sessionPicker.open || sessionPicker.deleteInFlight) return
        if (sessionPicker.deleteArmed) {
            if (sessionPicker.confirmDeleteOption()) {
                val folder = sessionPicker.selectedFolder() ?: return
                val session = folder.sessions.getOrNull(sessionPicker.sessionIndex - 1) ?: return
                sessionPicker.disarmDelete()
                runDeleteConversation(folder.path, session.id)
            } else {
                sessionPicker.disarmDelete()
                sessionPickerSyncToView()
            }
            return
        }
        val target = sessionPicker.confirm()
        if (target == null) {
            // Descended: the conversation-level selection starts on the
            // CURRENT (▶) conversation in BOTH flows (user 2026-08-10: the
            // connect flow previously kept the new-chat default, but the
            // highlight must land on the current conversation for a fast
            // resume — like the folder level does).
            sessionPicker.selectCurrentSession()
            sessionPickerSyncToView()
            return
        }
        sessionPicker.close()
        sessionPickerSyncToView()
        val sessionId = target.sessionId ?: java.util.UUID.randomUUID().toString()
        switchToTarget(target.folderPath, sessionId, isNew = target.sessionId == null,
            thenConnect = sessionPickerConnectMode)
    }

    private fun sessionPickerCancel() {
        pickerPrimaryPending?.let(mainHandler::removeCallbacks)
        pickerPrimaryPending = null
        if (!sessionPicker.open || sessionPicker.deleteInFlight) return
        if (sessionPicker.deleteArmed) {
            sessionPicker.disarmDelete()
            sessionPickerSyncToView()
            return
        }
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

    // --- Part 3: command panel passthrough (rules/input.md) ---
    //
    // After sending a "/"-command Claude opens its own picker/menu; the app
    // switches to panel mode so up/down/confirm/cancel keys reach the PTY
    // instead of browsing local history. Exited explicitly via right knob /
    // Back (Back also sends ESC, canceling the picker).

    private var panelMode = false

    /**
     * AskUserQuestion sub-state (2026-08-10): true while the detected
     * AskUserQuestion panel drives the panel passthrough. [askPanelComposer]
     * is true while the composer is open as the panel's text-input
     * sub-state (cancelling returns to the panel, sending submits).
     */
    private var askPanelMode = false
    private var askPanelComposer = false
    private var askPanelDetectStreak = 0
    private var askPanelLostStreak = 0
    /** Multi-select AskUserQuestion ([ ] checkboxes) — single click toggles. */
    private var askPanelMultiSelect = false
    /** Type something.'s option number, captured at composer-open — the
     *  DIGIT (not Enter) switches Claude's picker into text-input mode on
     *  send (2026-08-10). */
    private var askPanelTypeNumber: Int? = null
    /** TAB form (help line "Tab/Arrow keys to navigate", zone bar with
     *  ☒多选/☐自由输入/✔Submit) — Enter selects, Tab switches zones,
     *  Submit commits (real capture 2026-08-10). */
    private var askPanelTab = false

    /** Fingerprint of the screen above the input line at panel entry —
     *  Claude's reply is detected as a change from this. */
    private var panelEntryFingerprint: String? = null
    /** Last moment the input line was NOT the bare prompt (the picker's
     *  focus marker occupies it while a picker is open). */
    private var lastNonBarePromptNanos = 0L

    /**
     * Panel auto-exit (user 2026-08-07): the panel exits when Claude's REPLY
     * has arrived — the input line is back to the bare "❯ " prompt (the
     * picker's focus marker is gone) AND the screen changed from the panel
     * entry (the reply rendered). While the picker is open the input line
     * holds the focus marker, so the panel is held with no timeouts.
     */
    private val panelExitRunnable = object : Runnable {
        override fun run() {
            if (panelMode) {
                val now = System.nanoTime()
                // Hold while a numbered picker is on screen: /usage's two
                // levels keep the numbered rows mid-screen with a BARE
                // prompt at the bottom, so the bare-prompt signal alone
                // misfired and auto-exited mid-picker (user 2026-08-07).
                val vertical = terminalView.pickerAxis() == TerminalView.PickerAxis.VERTICAL
                val bare = terminalView.inputLineText() == null
                if (vertical || !bare) {
                    lastNonBarePromptNanos = now
                } else {
                    val fingerprint = panelEntryFingerprint
                    if (fingerprint != null &&
                        terminalView.frameFingerprint() != fingerprint &&
                        now - lastNonBarePromptNanos > PANEL_EXIT_REPLY_NANOS
                    ) {
                        cancelPanelMode()
                    }
                }
            } else {
                // The per-frame detection can stall when the network goes
                // quiet right after the panel renders (no further frames
                // to accumulate the detect streak on) — poll as well so
                // AskUserQuestion mode enters automatically, without any
                // user key (user report 2026-08-10: TP single click opened
                // the composer while the rendered panel was not yet in
                // panel mode). A 1 s sighting is already stable.
                pollAskPanelDetection()
            }
            mainHandler.postDelayed(this, PANEL_EXIT_POLL_MS)
        }
    }

    private fun enterPanelMode() {
        if (mode != Mode.TERMINAL || panelMode) return
        panelMode = true
        terminalOutput.captureSuspended = true   // panel repaints stay out of history (2026-08-13)
        terminalView.setPanelActive(true)
        panelEntryFingerprint = terminalView.frameFingerprint()
        lastNonBarePromptNanos = System.nanoTime()
        android.util.Log.i("RokidTerminal", "mode -> PANEL (command panel passthrough)")
        updateHeader()
    }

    private fun cancelPanelMode() {
        if (!panelMode) return
        panelMode = false
        terminalOutput.captureSuspended = false
        askPanelMode = false
        askPanelComposer = false
        askPanelMultiSelect = false
        askPanelTab = false
        panelLeftKnobDoublePending?.let(mainHandler::removeCallbacks)
        panelLeftKnobDoublePending = null
        terminalView.setPanelActive(false)
        terminalView.setAskPanel(emptyList(), 0)
        panelAxisSticky = null
        panelAxisCommand = null
        android.util.Log.i("RokidTerminal", "mode -> TERMINAL (panel exited)")
        updateHeader()
    }

    /**
     * 1 s poll complement to the frame-driven detection: enters
     * askPanelMode directly when the panel is sighted (see the
     * panelExitRunnable comment, 2026-08-10).
     */
    private fun pollAskPanelDetection() {
        val active = !sessionPicker.open && !switchInFlight &&
            (mode == Mode.TERMINAL || askPanelComposer) && sshState == "CONNECTED"
        if (!active) return
        val snap = terminalView.askPanelSnapshot() ?: return
        if (!panelMode) enterAskPanelMode(snap)
    }

    /**
     * Frame-driven AskUserQuestion state machine (2026-08-10): detects the
     * panel via its signature rows (Type something. / Chat about this —
     * command pickers never show them), enters the panel passthrough,
     * mirrors the selection from the input-line echo each frame, and exits
     * when the panel disappears. The detect/lost streaks guard against
     * half-frames.
     */
    private fun updateAskPanelState() {
        val active = !sessionPicker.open && !switchInFlight &&
            (mode == Mode.TERMINAL || askPanelComposer) && sshState == "CONNECTED"
        val snap = if (active) terminalView.askPanelSnapshot() else null
        if (snap != null) {
            askPanelLostStreak = 0
            if (panelMode) {
                if (askPanelMode) {
                    askPanelMultiSelect = snap.multiSelect
                    askPanelTab = snap.tabPanel
                    terminalView.setAskPanel(snap.options, snap.selected)
                }
            } else if (++askPanelDetectStreak >= ASK_PANEL_DETECT_FRAMES) {
                enterAskPanelMode(snap)
            }
        } else {
            askPanelDetectStreak = 0
            if (askPanelMode && ++askPanelLostStreak >= ASK_PANEL_LOST_FRAMES) {
                cancelPanelMode()
            }
        }
    }

    private fun enterAskPanelMode(snap: AskPanelParser.Snapshot) {
        if (panelMode || mode != Mode.TERMINAL) return
        panelMode = true
        terminalOutput.captureSuspended = true   // panel repaints stay out of history (2026-08-13)
        askPanelMode = true
        askPanelMultiSelect = snap.multiSelect
        askPanelTab = snap.tabPanel
        terminalView.setPanelActive(true)
        panelEntryFingerprint = terminalView.frameFingerprint()
        lastNonBarePromptNanos = System.nanoTime()
        terminalView.setAskPanel(snap.options, snap.selected)
        android.util.Log.i("RokidTerminal", "mode -> ASK PANEL (AskUserQuestion)")
        updateHeader()
    }

    /**
     * Left knob in the panel/ask modes (user decision 2026-08-10):
     * SINGLE press = only the AskUserQuestion type-entry confirm (Enter +
     * composer — an intermediate step, not a final choice); on a regular
     * option a single press is a NO-OP (final selections need the
     * deliberate double). DOUBLE press = confirm (Enter), like TP/Ring
     * long press.
     */
    private var panelLeftKnobDoublePending: Runnable? = null

    private fun handlePanelLeftKnobPress() {
        val pending = panelLeftKnobDoublePending
        if (pending != null) {
            mainHandler.removeCallbacks(pending)
            panelLeftKnobDoublePending = null
            panelConfirm()
            return
        }
        if (askPanelMode) {
            val option = terminalView.askPanelSelectedOption()
            // Only Type something. opens the composer on single press —
            // Chat about this sends directly (Enter) like a regular option
            // (user 2026-08-10). Checked options block it (mutually
            // exclusive, user 2026-08-10). No key is sent on open — the
            // Type-something event travels with the text on send.
            if (option != null && option.typeSomething) {
                if (askPanelHasChecked()) {
                    android.widget.Toast.makeText(
                        this, "UNCHECK OPTIONS FIRST", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    return
                }
                openComposerFromAskPanel()
                return
            }
            // Multi-select: single click TOGGLES the checkbox (space) on
            // regular options, double click submits (user 2026-08-10).
            if (askPanelMultiSelect && !askPanelTab && option != null && !option.chatAbout) {
                ssh.sendCharacters(" ")
                val single = Runnable { panelLeftKnobDoublePending = null }
                panelLeftKnobDoublePending = single
                mainHandler.postDelayed(single, RIGHT_KNOB_DOUBLE_WINDOW_MS)
                return
            }
            // TAB form: single click SELECTS (Enter), double click submits.
            if (askPanelTab && option != null && !option.typeSomething && !option.chatAbout) {
                ssh.sendEnter()
                val single = Runnable { panelLeftKnobDoublePending = null }
                panelLeftKnobDoublePending = single
                mainHandler.postDelayed(single, RIGHT_KNOB_DOUBLE_WINDOW_MS)
                return
            }
        }
        val single = Runnable { panelLeftKnobDoublePending = null }
        panelLeftKnobDoublePending = single
        mainHandler.postDelayed(single, RIGHT_KNOB_DOUBLE_WINDOW_MS)
    }

    /**
     * Panel confirm. AskUserQuestion: confirming Type something. sends
     * Enter (Claude's picker enters its text-input mode) and opens the
     * composer as the panel's sub-state (2026-08-10); Chat about this and
     * every other option are plain Enters (Chat about this sends directly
     * and starts the next round — user 2026-08-10).
     *
     * MULTI-SELECT: Claude's picker moves the focus to the ✔ Submit entry
     * on the first Enter — a second Enter submits the checked options
     * (user report 2026-08-10: a single Enter "jumped to the submit panel"
     * and the interaction stalled).
     */
    private fun panelConfirm() {
        if (askPanelMode) {
            val option = terminalView.askPanelSelectedOption()
            if (option != null && option.typeSomething) {
                if (askPanelHasChecked()) {
                    // Type something. and checked options are mutually
                    // exclusive (user 2026-08-10): either submit the
                    // checked options or type a custom answer — never both.
                    android.widget.Toast.makeText(
                        this, "UNCHECK OPTIONS FIRST", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    return
                }
                // No key is sent while the composer is open: Claude's picker
                // stays in its option state, so cancelling the composer
                // returns to the choices cleanly. The Type-something event
                // and the text travel together on SEND (2026-08-10).
                openComposerFromAskPanel()
                return
            }
            if (askPanelMultiSelect) {
                ssh.sendEnter()
                mainHandler.postDelayed(
                    { if (panelMode && sshState == "CONNECTED") ssh.sendEnter() },
                    ASK_SUBMIT_ENTER_DELAY_MS,
                )
                return
            }
            // TAB form submit: Tab moves to the ✔ Submit zone, Enter
            // commits (help line: "Tab/Arrow keys to navigate").
            if (askPanelTab) {
                ssh.sendCharacters("\t")
                mainHandler.postDelayed(
                    { if (panelMode && sshState == "CONNECTED") ssh.sendEnter() },
                    ASK_SUBMIT_ENTER_DELAY_MS,
                )
                return
            }
        }
        ssh.sendEnter()
    }

    /** True while the multi-select panel shows at least one checked [x]. */
    private fun askPanelHasChecked(): Boolean {
        val snap = terminalView.askPanelSnapshot() ?: return false
        return snap.options.any { it.checked }
    }

    /**
     * Opens the composer as an AskUserQuestion sub-state: the panel stays
     * open underneath, cancelling the composer returns to the panel (ESC),
     * sending submits the text (2026-08-10).
     */
    private fun openComposerFromAskPanel() {
        android.util.Log.i("RokidTerminal", "mode -> COMPOSER (ask panel input)")
        askPanelComposer = true
        askPanelTypeNumber = terminalView.askPanelSelectedOption()?.number
        publishTerminalFrame(terminalOutput.returnToLive())
        speechDraft.reset()
        composer.clear()
        mode = Mode.COMPOSER
        refreshComposer("ASK INPUT / HOLD SEND")
    }

    /**
     * Panel keys (user contract 2026-08-06): navigate = arrows, confirm =
     * Enter, cancel = ESC + exit. Bindings: Rokid TP long = confirm, TP
     * double = cancel; keyboard left knob single = confirm, right knob
     * single = cancel; Ring touchpad long = confirm, GO single = cancel.
     * Everything else is blocked while the panel is open.
     */
    private fun handlePanelKey(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
        KeyEvent.KEYCODE_2 -> {
            if (event.repeatCount == 0) ssh.sendArrowUp()
            true
        }
        KeyEvent.KEYCODE_5 -> {
            if (event.repeatCount == 0) ssh.sendArrowDown()
            true
        }
        KeyEvent.KEYCODE_4 -> {
            if (event.repeatCount == 0) ssh.sendArrowLeft()
            true
        }
        KeyEvent.KEYCODE_6 -> {
            if (event.repeatCount == 0) ssh.sendArrowRight()
            true
        }
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
            // The glasses'/ring's single swipe gesture adapts to the
            // picker's detected axis (user decision 2026-08-06): vertical
            // lists (/model) — up/left = up, down/right = down; horizontal
            // pickers (/effort slider, /usage first level) — left/up =
            // left, right/down = right. Keyboard keeps full 2D control.
            if (event.repeatCount == 0) sendPanelSwipe(keyCode, event)
            true
        }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            // AskUserQuestion single click (TP + ring touchpad): on a
            // regular option — checkbox multi-select toggles (space), the
            // TAB form SELECTS (Enter); on Type something. it SUMMONS the
            // composer (cancellable, so no strong-auth long press needed —
            // user 2026-08-10); Chat about this is never touched (it
            // sends directly and needs the deliberate long press).
            if (event.repeatCount == 0 && askPanelMode) {
                val option = terminalView.askPanelSelectedOption()
                if (option != null && !option.chatAbout) {
                    if (option.typeSomething) {
                        if (askPanelHasChecked()) {
                            android.widget.Toast.makeText(
                                this, "UNCHECK OPTIONS FIRST", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            openComposerFromAskPanel()
                        }
                    } else if (askPanelMultiSelect && !askPanelTab) {
                        ssh.sendCharacters(" ")
                    } else {
                        ssh.sendEnter()
                    }
                }
            }
            true
        }
        KeyEvent.KEYCODE_8 -> {
            if (event.repeatCount == 0) handlePanelLeftKnobPress()
            true
        }
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MOVE_HOME -> {
            // Ring touchpad long press = confirm (Enter).
            if (event.repeatCount == 0) panelConfirm()
            true
        }
        KeyEvent.KEYCODE_D -> {
            // Right knob single: TAB on the AskUserQuestion TAB form (zone
            // switching: options ↔ free-input ↔ Submit — the panel's help
            // line says "Tab/Arrow keys to navigate"); plain ESC+cancel
            // on every other panel (AskUserQuestion panels are NOT
            // cancellable, 2026-08-10).
            if (event.repeatCount == 0) {
                if (askPanelMode) {
                    ssh.sendCharacters("\t")
                } else {
                    ssh.sendEscape()
                    cancelPanelMode()
                }
            }
            true
        }
        KeyEvent.KEYCODE_BACK -> {
            // Back = ESC to the PTY (cancel the picker) + leave panel mode.
            // Same AskUserQuestion guard as KEYCODE_D (2026-08-10).
            if (event.repeatCount == 0) {
                if (!askPanelMode) {
                    ssh.sendEscape()
                    cancelPanelMode()
                }
            }
            true
        }
        KeyEvent.KEYCODE_F8 -> {
            // GO button: route to the app-side arbitration so the panel
            // double-click cancel works — the strict-isolation else-branch
            // would otherwise swallow F8 before handleGoKey runs
            // (2026-08-06).
            handleGoKey(event)
            true
        }
        else -> true // strict isolation: nothing else acts while the panel is open
        }
    }

    private var lastPanelArrow: String? = null
    private var lastPanelArrowTime = 0L
    private var panelAxisSticky: TerminalView.PickerAxis? = null
    private var panelAxisCommand: String? = null
    private var lastSentCommand: String? = null

    /**
     * Maps a glasses/ring swipe to the picker's detected axis. Vertical
     * pickers: up/left = up, down/right = down. Horizontal pickers: left/up
     * = left, right/down = right. Ring swipe arrivals are inverted, so its
     * right-swipe (arrives as DPAD_LEFT) maps to "down" on a vertical picker
     * and "right" on a horizontal one. Fast swipes emit DPAD pairs within a
     * few ms — the same resulting arrow is deduped.
     *
     * The axis is detected ONCE per picker (keyed by the pending command on
     * the input line) and kept sticky: /model's picker re-renders without
     * the numbered rows once its effort slider is focused, which would flip
     * a per-frame detection to horizontal mid-interaction (2026-08-06).
     */
    private fun sendPanelSwipe(keyCode: Int, event: KeyEvent) {
        val ring = isRingEvent(event)
        // Key the sticky axis on the SENT command: while the picker is open
        // the input line is replaced by the focus marker, whose text changes
        // on every move (e.g. "5. deepseek…" → "2. deepseek…") — keying on
        // it would reset the axis mid-picker and flip to horizontal when the
        // effort slider is focused (2026-08-06).
        val command = lastSentCommand
        if (panelAxisCommand != command) {
            panelAxisSticky = terminalView.pickerAxis()
            panelAxisCommand = command
        }
        // AskUserQuestion panels are selection-only: the swipe is ALWAYS
        // vertical (up/down through the options) for every device — the
        // axis-adaptive logic would otherwise flip to horizontal when a
        // submit-style entry is on screen and the swipe starts changing the
        // wrong thing (user report 2026-08-10).
        if (askPanelMode) {
            val up = keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                (ring && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) ||
                (!ring && keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
            // Fast swipes emit DPAD pairs within a few ms — dedup the same
            // arrow or one swipe moves two options (user report 2026-08-10).
            val arrow = if (up) ARROW_UP else ARROW_DOWN
            val now = android.os.SystemClock.uptimeMillis()
            if (event.repeatCount == 0) {
                if (arrow == lastPanelArrow && now - lastPanelArrowTime < SWIPE_PAIR_DEDUP_MS) return
                lastPanelArrow = arrow
                lastPanelArrowTime = now
                if (up) ssh.sendArrowUp() else ssh.sendArrowDown()
            }
            return
        }
        val axis = panelAxisSticky ?: TerminalView.PickerAxis.HORIZONTAL
        if (axis == TerminalView.PickerAxis.VERTICAL) {
            // Keep the glasses inside the model list: bounce off the effort
            // slider (below) and the header (above).
            val bounce = terminalView.pickerBounceDirection()
            if (bounce != null) {
                if (bounce > 0) ssh.sendArrowDown() else ssh.sendArrowUp()
                return
            }
        }
        val arrow = when (axis) {
            TerminalView.PickerAxis.VERTICAL -> {
                val next = keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                    (ring && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) ||
                    (!ring && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                if (next) ARROW_DOWN else ARROW_UP
            }
            TerminalView.PickerAxis.HORIZONTAL -> {
                // Ring arrivals are inverted: right-swipe arrives as
                // DPAD_LEFT, left-swipe as DPAD_RIGHT. The plain
                // "keyCode == DPAD_RIGHT" clause must NOT apply to the ring
                // or both swipes map to "right" (2026-08-06).
                val right = (ring && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) ||
                    (!ring && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                        keyCode == KeyEvent.KEYCODE_DPAD_DOWN))
                if (right) ARROW_RIGHT else ARROW_LEFT
            }
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (arrow == lastPanelArrow && now - lastPanelArrowTime < SWIPE_PAIR_DEDUP_MS) return
        lastPanelArrow = arrow
        lastPanelArrowTime = now
        when (arrow) {
            ARROW_UP -> ssh.sendArrowUp()
            ARROW_DOWN -> ssh.sendArrowDown()
            ARROW_LEFT -> ssh.sendArrowLeft()
            else -> ssh.sendArrowRight()
        }
    }

    /**
     * Loads the command list: local defaults immediately, then a one-shot
     * server fetch (custom commands from the helper) merged in when it
     * returns. Cached per connection; failures keep the local list.
     */
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

    /** Compose the header status line from SSH + ASR channel state. */
    private fun updateHeader() {
        if (mode != Mode.TERMINAL) return
        val status = if (panelMode) {
            if (askPanelMode) "ASK PANEL / SELECT TYPE ESC"
            else "COMMAND PANEL / NAV CONFIRM CANCEL"
        } else if (asrStatus.isBlank()) {
            sshState
        } else {
            "$sshState / $asrStatus"
        }
        terminalView.setState(status)
    }

    /** Compact ASR channel state for the header (keep the 34-char budget). */
    private fun shortAsrStatus(value: String): String = when {
        value == "ASR CONNECTING" || value == "ASR MODEL LOADING" || value == "ASR READY" -> value
        value.startsWith("ASR ") -> "ASR FAIL"
        else -> ""
    }

    private fun clearPrimaryGesture() {
        mainHandler.removeCallbacks(primaryLongRunnable)
        pendingPrimarySingle?.let(mainHandler::removeCallbacks)
        pendingPrimarySingle = null
        panelLeftKnobDoublePending?.let(mainHandler::removeCallbacks)
        panelLeftKnobDoublePending = null
        pendingRightKnobSingle?.let(mainHandler::removeCallbacks)
        pendingRightKnobSingle = null
        leftKnobDoublePending?.let(mainHandler::removeCallbacks)
        leftKnobDoublePending = null
        terminalRightKnobDoublePending?.let(mainHandler::removeCallbacks)
        terminalRightKnobDoublePending = null
        pendingShutterSingle?.let(mainHandler::removeCallbacks)
        pendingShutterSingle = null
        composerShutterDoublePending?.let(mainHandler::removeCallbacks)
        composerShutterDoublePending = null
        goDoublePending?.let(mainHandler::removeCallbacks)
        goDoublePending = null
        activePrimaryKey = null
        primaryLongTriggered = false
    }

    /**
     * COIDEA keys 4/6 browse input history (contract): only while the
     * terminal history offset is 0 (live) — two history states must never
     * overlap. The browsed draft is shown as a preview line above the
     * footer and is loaded into the composer when it opens.
     */
    /** Moves the pointer onto Claude's suggestion slot and shows it dark
     *  (never sends). Callable repeatedly while the suggestion exists. */
    private fun fillSuggestion() {
        if (!inputHistory.jumpToSuggestion()) return
        val s = inputHistory.suggestion() ?: return
        historyPreview = s
        terminalView.setHistoryPreview(s)
    }

    private fun clearHistoryPreview() {
        if (historyPreview != null) {
            historyPreview = null
            inputHistory.resetCursor()
            terminalView.setHistoryPreview(null)
        }
    }

    private fun browseInputHistory(direction: Int) {
        if (terminalOutput.scrollOffset > 0) {
            android.util.Log.i("RokidTerminal", "history browse: skipped (offset>0)")
            return
        }
        // Diagnostics: entries size only — never draft text.
        android.util.Log.i("RokidTerminal", "history browse: dir=$direction entries=${inputHistory.size}")
        val text = inputHistory.peek(direction)
        if (text == null) {
            // Empty entry: no overlay — the remote light suggestion shows.
            clearHistoryPreview()
            return
        }
        historyPreview = text
        terminalView.setHistoryPreview(text)
    }

    private fun printableText(event: KeyEvent): String? {
        val unicode = event.unicodeChar
        if (unicode < 0x20 || unicode == 0x7f || !Character.isValidCodePoint(unicode)) return null
        return String(Character.toChars(unicode))
    }

    private fun isPrimaryKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
    }

    private fun showEndpointPicker() {
        clearPrimaryGesture()
        speech.cancel()
        speechDraft.discardActive()
        composer.clear()
        speechDraft.reset()
        mode = Mode.ENDPOINTS
        asrStatus = ""
        activeEndpoint = null
        endpoints = endpointStore.loadAll()
        val selectedId = endpointStore.selectedId()
        selectedIndex = endpoints.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
        terminalView.showEndpoints(endpoints, selectedIndex)
    }

    private fun moveSelection(delta: Int) {
        if (endpoints.isEmpty()) return
        selectedIndex = (selectedIndex + delta + endpoints.size) % endpoints.size
        endpointStore.select(endpoints[selectedIndex].id)
        terminalView.showEndpoints(endpoints, selectedIndex)
    }

    private fun connectSelected() {
        val endpoint = endpoints.getOrNull(selectedIndex) ?: return
        traceRecorder.reset()
        endpointStore.select(endpoint.id)
        activeEndpoint = endpoint
        lastBoundEndpoint = endpoint
        mode = Mode.TERMINAL
        panelMode = false
        terminalOutput.captureSuspended = false
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

    private fun openSessionPicker(connectMode: Boolean) {
        // Locked while a switch is in flight (no double-switch races).
        if (switchInFlight) return
        val endpoint = activeEndpoint ?: return
        sessionPickerConnectMode = connectMode
        clearPrimaryGesture()
        sessionPicker.open(rememberedFolder(endpoint.id), rememberedSession(endpoint.id))
        // Cache-first (user request 2026-08-07): the last fetched folder
        // list shows instantly (a fresh SSH fetch takes seconds on this
        // network), then refreshSessionFolders updates it in the background.
        // Stale entries are safe: the server switch verb re-validates the
        // target directory, so a deleted folder fails with an error, never
        // a hang. The cache is in-memory (per app run), not persisted.
        val cached = cachedFolders
        if (cached != null) {
            sessionPicker.setFolders(cached, failed = false)
            // Pre-select the remembered folder when it is still listed
            // (user decision 2026-08-07); otherwise the base dir stays.
            sessionPicker.selectFolder(sessionPicker.currentFolderPath)
        }
        sessionPickerSyncToView()
        refreshSessionFolders(endpoint)
    }

    /**
     * Background folder-list refresh. Applies live only while the picker is
     * still at the folder level and the user has not moved the selection
     * (applying mid-navigation would reset it); otherwise the fresh list is
     * cached for the next open.
     */
    private fun refreshSessionFolders(endpoint: EndpointProfile) {
        val fetcher = sessionFetcher ?: return
        val workspace = endpoint.workspace
        Thread {
            val folders = fetcher.listSessions(workspace)
            runOnUiThread {
                val fresh = if (folders.isNullOrEmpty()) {
                    // Helper unreachable or no folders: fall back to a single
                    // "new conversation" entry in the workspace.
                    listOf(RemoteFolder(workspace, ServerSessionFetcher.encodeDir(workspace), emptyList()))
                } else {
                    folders
                }
                // Cache ONLY successful fetches: a transient failure must
                // not poison the cache with the /srv-only fallback, or every
                // later open shows it instantly (user report 2026-08-07).
                if (folders != null && folders.isNotEmpty()) {
                    cachedFolders = folders
                }
                val busy = sessionPicker.deleteArmed || sessionPicker.deleteInFlight
                if (sessionPicker.open && sessionPicker.level == 0 && !busy) {
                    // Apply live ALWAYS at the folder level (a new folder
                    // must appear even if the user navigated — user report
                    // 2026-08-07), restoring the user's current position by
                    // path instead of yanking them to the top/remembered.
                    val selectedPath = sessionPicker.selectedFolder()?.path
                    sessionPicker.setFolders(fresh, failed = folders == null)
                    if (selectedPath == null || !sessionPicker.selectFolder(selectedPath)) {
                        sessionPicker.selectFolder(sessionPicker.currentFolderPath)
                    }
                    sessionPickerSyncToView()
                } else if (sessionPicker.open && sessionPicker.level == 1 && !busy) {
                    // Same at the conversation level: a new chat must appear
                    // even while the user is already browsing the list
                    // (bug 1 follow-up, 2026-08-07) — restore the folder by
                    // path and the selection by session id.
                    val folderPath = sessionPicker.selectedFolder()?.path
                    val selectedId = sessionPicker.selectedFolder()?.sessions
                        ?.getOrNull(sessionPicker.sessionIndex - 1)?.id
                    sessionPicker.setFolders(fresh, failed = folders == null)
                    if (folderPath != null && sessionPicker.selectFolder(folderPath)) {
                        sessionPicker.confirm() // descend into the same folder
                        if (selectedId != null) {
                            val index = sessionPicker.selectedFolder()?.sessions
                                ?.indexOfFirst { it.id == selectedId }
                            if (index != null && index >= 0) sessionPicker.selectSession(index + 1)
                        }
                    }
                    sessionPickerSyncToView()
                }
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
        switchInFlight = true
        lastSwitchNanos = System.nanoTime()
        // The resume replay genuinely scrolls the viewport; suppress scroll
        // capture during the switch window so the imported transcript is not
        // duplicated in the scrollback (2026-08-07).
        terminalOutput.suppressScrollCaptureFor(REPLAY_SUPPRESSION_MS)
        // The session we are switching AWAY from: until a new chat's first
        // message, the server's "newest session" is still this one, and
        // neither the discovery loop nor the watcher may "correct" back to
        // it (bug 1, 2026-08-07).
        val previousSessionId = scrollbackSessionId
        if (isNew) {
            // Fresh chat: hold the sync watcher off until the real session
            // id converges, so it can never import the PREVIOUS
            // conversation's scrollback into the new chat (2026-08-07).
            newSessionPending = true
            newSessionFolderPath = folderPath
        } else {
            newSessionPending = false
        }
        terminalView.setState(if (thenConnect) "CONNECTING / STARTING" else "SWITCHING…")
        val tmuxSession = endpoint.sessionName
        val workspace = endpoint.workspace
        Thread {
            val raw = fetcher.switchConversation(tmuxSession, workspace, folderPath, sessionId, isNew)
            val ok = raw != null && ServerSessionFetcher.parseSwitchResult(raw) != null
            runOnUiThread {
                switchInFlight = false
                if (ok) {
                    bindScrollback(folderPath, sessionId)
                    rememberTarget(folderPath, sessionId)
                    sessionPicker.markCurrent(folderPath, sessionId)
                    // A freshly created conversation appears in the cached
                    // list immediately — the server JSONL is only written on
                    // the first message, so without this the new chat would
                    // stay invisible in the picker (user 2026-08-07).
                    if (isNew) rememberNewSessionInCache(folderPath, sessionId)
                    // The app-generated session id may differ from the
                    // server's real file (--session-id can be ignored / the
                    // file appears only later) — discover and correct the
                    // binding so resumes work and rows don't duplicate
                    // (bug 1, 2026-08-07).
                    if (isNew) discoverNewSessionId(folderPath, sessionId)
                    // Resumed conversations get their FULL transcript pulled
                    // into the local scrollback (the server replay is a
                    // redraw, not a scroll, so nothing is captured locally —
                    // user report 2026-08-07).
                    if (!isNew) fetchConversationHistory(folderPath, sessionId)
                    // After the replay settles, trim the imported transcript
                    // to the turns ABOVE the live screen — the screen shows
                    // the tail, and browsing appends the screen below the
                    // scrollback (2026-08-07). For RESUMED conversations the
                    // trim is re-scheduled AFTER the server-export import
                    // completes (2026-08-13: a large transcript's export can
                    // land later than this fixed delay — trimming first and
                    // importing after left the tail duplicated).
                    if (isNew) {
                        mainHandler.postDelayed({
                            if (sshState == "CONNECTED") terminalOutput.trimScrollbackToScreen()
                        }, REPLAY_SUPPRESSION_MS + 1500L)
                    }
                    if (thenConnect) connectAfterSwitch(endpoint)
                    updateHeader()
                    android.widget.Toast.makeText(
                        this, "Session switched", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else if (thenConnect) {
                    // Helper unavailable (not yet deployed / server error):
                    // preserve the pre-change behavior via the legacy launch.
                    android.util.Log.w("RokidTerminal", "session switch failed; legacy launch")
                    bindScrollback(workspace, sessionId)
                    rememberTarget(workspace, sessionId)
                    connectAfterSwitch(endpoint, useLegacy = true)
                } else {
                    android.widget.Toast.makeText(
                        this, "Switch failed", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    // The resume target did not exist server-side (a stale
                    // local placeholder id): the server is still on ITS
                    // active conversation, so reconcile the local binding
                    // with `status` — otherwise drafts written now land in
                    // the wrong conversation's file and leak into it later
                    // (user report 2026-08-10: a previous conversation's
                    // message showed up in a new chat's history recall).
                    reconcileBindingFromStatus()
                    updateHeader()
                }
            }
        }.start()
    }

    /**
     * After a FAILED switch the server is still on ITS active conversation;
     * the local binding must follow (with the draft cache), or drafts
     * written now land in the wrong conversation's file (2026-08-10).
     * Same sync as the watcher rebind, run immediately instead of waiting
     * for the next 30 s poll.
     */
    private fun reconcileBindingFromStatus() {
        val fetcher = sessionFetcher ?: return
        val endpoint = activeEndpoint ?: return
        Thread {
            val status = fetcher.status(endpoint.sessionName)
            runOnUiThread {
                if (status == null || status.cwd == null || status.sessionId == null) return@runOnUiThread
                val newFolderKey = ServerSessionFetcher.encodeDir(status.cwd)
                if (newFolderKey == scrollbackFolderKey && status.sessionId == scrollbackSessionId) {
                    return@runOnUiThread
                }
                persistScrollback()
                scrollbackFolderKey = newFolderKey
                scrollbackSessionId = status.sessionId
                inputHistory = InputHistory(filesDir, "$scrollbackFolderKey/$scrollbackSessionId")
                val store = scrollbackStore
                if (store != null) {
                    terminalOutput.importScrollbackText(
                        store.read(store.file(endpoint.id, newFolderKey, scrollbackSessionId!!)),
                    )
                }
                sessionPicker.markCurrent(status.cwd, scrollbackSessionId)
                android.util.Log.i("RokidTerminal", "reconciled binding after failed switch")
            }
        }.start()
    }

    /** Deletes the transcript on the server + the local scrollback file. */
    private fun runDeleteConversation(folderPath: String, sessionId: String) {
        val endpoint = activeEndpoint ?: return
        val fetcher = sessionFetcher ?: return
        sessionPicker.setDeleteInFlight(true)
        sessionPickerSyncToView()
        Thread {
            val raw = fetcher.deleteConversation(endpoint.sessionName, endpoint.workspace, folderPath, sessionId)
            val ok = raw != null && ServerSessionFetcher.parseSwitchResult(raw) != null
            runOnUiThread {
                sessionPicker.setDeleteInFlight(false)
                if (ok) {
                    sessionPicker.removeSession(folderPath, sessionId)
                    val store = scrollbackStore
                    if (store != null) {
                        runCatching {
                            store.file(endpoint.id, ServerSessionFetcher.encodeDir(folderPath), sessionId).delete()
                        }
                    }
                    // The per-conversation draft file is orphaned otherwise
                    // (deleted sessions left input_history files behind —
                    // user report 2026-08-10).
                    InputHistory.deleteFile(
                        filesDir,
                        "${ServerSessionFetcher.encodeDir(folderPath)}/$sessionId",
                    )
                    sessionPickerSyncToView()
                    android.widget.Toast.makeText(this, "Session deleted", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    sessionPickerSyncToView()
                    android.widget.Toast.makeText(this, "Delete failed", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * After sending in a freshly created chat whose cached row is still the
     * "New chat" placeholder, refetch the folder list so the real
     * first-message title replaces it without exiting the terminal
     * (bug 1 follow-up, 2026-08-07). No-op otherwise.
     */
    private fun refreshNewChatTitleIfNeeded() {
        val folderKey = scrollbackFolderKey ?: return
        val sessionId = scrollbackSessionId ?: return
        val endpoint = activeEndpoint ?: return
        val isPlaceholder = cachedFolders?.any { folder ->
            folder.encodedDir == folderKey &&
                folder.sessions.any { it.id == sessionId && it.title == NEW_CHAT_TITLE }
        } == true
        if (isPlaceholder) refreshSessionFolders(endpoint)
    }

    /**
     * Adds a freshly created conversation to the cached folder list with a
     * "New chat" placeholder title, so the next picker open shows it
     * instantly. The background refresh replaces the placeholder with the
     * real first-message title once the server JSONL exists (2026-08-07).
     */
    private fun rememberNewSessionInCache(folderPath: String, sessionId: String) {
        val cached = cachedFolders ?: return
        val now = System.currentTimeMillis()
        cachedFolders = cached.map { folder ->
            if (folder.path == folderPath && folder.sessions.none { it.id == sessionId }) {
                folder.copy(sessions = listOf(RemoteSession(sessionId, NEW_CHAT_TITLE, now)) + folder.sessions)
            } else {
                folder
            }
        }
    }

    /**
     * Polls the folder LIST for a few seconds after a NEW conversation
     * switch and, when a real session file appears (the JSONL is written on
     * the first message), corrects the binding/remembered target/cache to
     * the REAL id and renames the server window via `adopt`. The
     * app-generated placeholder id may differ from the server's file
     * (--session-id can be ignored), which caused duplicate-looking rows,
     * failed resumes, and a wrong ▶ marker (bug 1, 2026-08-07). Re-list
     * polling (2026-08-11) is independent of the process's open-file state,
     * unlike `status`-based discovery. The FIRST poll snapshots the
     * pre-message session set; convergence happens only for ids that
     * APPEARED afterwards — never for arbitrary old conversations (bug
     * 2026-08-13: "newest other session" reused old ids/history). Session
     * ids are never logged.
     */
    private fun discoverNewSessionId(folderPath: String, tempSessionId: String) {
        val endpoint = activeEndpoint ?: return
        val fetcher = sessionFetcher ?: return
        val folderKey = ServerSessionFetcher.encodeDir(folderPath)
        Thread {
            var baseline: Set<String>? = null
            for (attempt in 0 until 6) {
                Thread.sleep(2000)
                val folders = fetcher.listSessions(endpoint.workspace) ?: continue
                val folder = folders.firstOrNull { it.encodedDir == folderKey } ?: continue
                val ids = folder.sessions.map { it.id }.toSet()
                if (baseline == null) {
                    baseline = ids
                    continue
                }
                val real = ServerSessionFetcher.firstNewSession(folder, baseline, tempSessionId) ?: continue
                val realId = real.id
                if (realId.isEmpty()) continue
                runOnUiThread {
                    if (scrollbackSessionId == tempSessionId) {
                        persistScrollback()
                        scrollbackSessionId = realId
                        rememberTarget(folderPath, realId)
                        sessionPicker.markCurrent(folderPath, realId)
                        // Drafts sent under the placeholder key must follow
                        // the conversation into the real-id file, or the
                        // recall keys read nothing (user 2026-08-07).
                        InputHistory.migrate(filesDir, "$folderKey/$tempSessionId", "$folderKey/$realId")
                        inputHistory = InputHistory(filesDir, "$folderKey/$realId")
                        // Real id converged: the sync watcher may resume
                        // (a non-converged NEW chat must keep it off, or it
                        // imports the previous conversation's scrollback).
                        newSessionPending = false
                        // Replace the placeholder id in the cached list.
                        cachedFolders = cachedFolders?.map { folder ->
                            if (folder.path == folderPath) {
                                val sessions = folder.sessions.map {
                                    if (it.id == tempSessionId) it.copy(id = realId) else it
                                }
                                folder.copy(sessions = sessions)
                            } else {
                                folder
                            }
                        }
                    }
                }
                // Best-effort (design 2026-08-11 §3.4): keep the server
                // window name in sync with the real id so later switches
                // find it by name even when the process is idle. Failure is
                // logged only — the next switch self-heals via
                // identification. Never logs the session id.
                val adoptEndpoint = endpoint
                Thread {
                    try {
                        fetcher.adoptConversation(adoptEndpoint.sessionName, folderPath, realId)
                    } catch (error: Exception) {
                        android.util.Log.w(
                            "RokidTerminal",
                            "adopt failed: ${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                }.start()
                return@Thread
            }
        }.start()
    }

    /**
     * Pulls a resumed conversation's transcript from the server into the
     * local scrollback (force import — works while Claude's alt screen is
     * active). The browse view shows the whole conversation, not just the
     * rows captured while watching live (2026-08-07).
     */
    private fun fetchConversationHistory(folderPath: String, sessionId: String) {
        val endpoint = activeEndpoint ?: return
        val fetcher = sessionFetcher ?: return
        Thread {
            val raw = fetcher.exportConversation(endpoint.workspace, folderPath, sessionId)
            runOnUiThread {
                if (raw != null && raw.isNotBlank() && !raw.startsWith("error\t")) {
                    // The live screen renders conversation rows with the
                    // TUI's leading indentation (frame evidence 2026-08-14:
                    // "  C975"); the exported transcript is plain text.
                    // Indent imported history the same way so it renders
                    // like in-app captured history (user 2026-08-14), and
                    // restore the user-message background (SGR 48;5;237 —
                    // the exact marker live-captured rows carry, verified
                    // from the persisted file 2026-08-14) on ❯ rows so
                    // imported history highlights like the live screen.
                    val rows = raw.lineSequence().toList().map { row ->
                        if (row.isBlank()) {
                            row
                        } else {
                            val indented = "  $row"
                            if (row.trimStart().startsWith("❯")) {
                                "[48;5;237m$indented[49m"
                            } else {
                                indented
                            }
                        }
                    }
                    terminalOutput.importScrollbackTextForce(rows)
                    // The trim must run only after the ATTACH REPAINT has
                    // settled — the live screen then shows the conversation
                    // tail, which is what the imported scrollback tail
                    // duplicates in the browse view (bug 2026-08-13: a fixed
                    // 1.5 s delay raced the repaint data stream; the screen
                    // had not shown the tail yet, so the anchor was not
                    // found and the tail stayed duplicated).
                    scheduleTrimAfterScreenSettles()
                }
            }
        }.start()
    }

    /**
     * Polls the rendered frame until it has been STABLE for ~1.5 s, then
     * trims the imported scrollback tail against the live screen. The
     * attach repaint can take seconds to stream in; trimming against a
     * half-repainted screen finds no anchor and leaves the duplicate.
     */
    private fun scheduleTrimAfterScreenSettles() {
        val startedAt = android.os.SystemClock.uptimeMillis()
        var lastFingerprint = ""
        var stableStreak = 0
        val poll = object : Runnable {
            override fun run() {
                if (sshState != "CONNECTED") return
                val fp = terminalView.frameFingerprint()
                if (fp == lastFingerprint) {
                    stableStreak++
                } else {
                    stableStreak = 0
                    lastFingerprint = fp
                }
                val minElapsed = android.os.SystemClock.uptimeMillis() - startedAt >= 2000L
                if (stableStreak >= 3 && minElapsed) {
                    terminalOutput.trimScrollbackToScreen()
                } else {
                    mainHandler.postDelayed(this, 500L)
                }
            }
        }
        mainHandler.postDelayed(poll, 500L)
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
        // Reset FIRST: importScrollbackText is a no-op while the alternate
        // screen is active, and after a respawn the screen still carries the
        // PREVIOUS conversation's alt state — without a reset the resumed
        // conversation's persisted history could not be imported and
        // browsing was empty (user report 2026-08-07). reset() clears the
        // scrollback AND the alt flag; the new Claude redraws the screen.
        publishTerminalFrame(terminalOutput.reset())
        terminalOutput.importScrollbackText(rows)
        // Input history is per-conversation too (user 2026-08-07): each
        // conversation owns its drafts; switching rebinds the cache.
        historyPreview = null
        terminalView.setHistoryPreview(null)
        inputHistory = InputHistory(filesDir, "$scrollbackFolderKey/$scrollbackSessionId")
        // Diagnostics (never logs the session id or draft text):
        // confirms the per-conversation history binding after each switch.
        android.util.Log.i(
            "RokidTerminal",
            "bind history: folder=$scrollbackFolderKey entries=${inputHistory.size}",
        )
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

    private fun pollSessionSync() {
        val fetcher = sessionFetcher ?: return
        val endpoint = activeEndpoint ?: return
        if (sshState != "CONNECTED" || sessionPicker.open || switchInFlight) return
        Thread {
            val status = fetcher.status(endpoint.sessionName)
            runOnUiThread {
                if (status == null || status.cwd == null) return@runOnUiThread
                // Fresh-switch grace: a new conversation's JSONL only appears
                // on its first message, so until then the server's "newest
                // session" is still the PREVIOUS conversation — rebinding
                // would clobber the fresh binding, point ▶ at the old chat,
                // and import the old history into the new one (bug 1,
                // 2026-08-07). discoverNewSessionId handles the real-id
                // correction during this window.
                if (System.nanoTime() - lastSwitchNanos < SWITCH_GRACE_NANOS) return@runOnUiThread
                // A freshly created chat's real id has not converged yet:
                // the server's "newest session" is still the PREVIOUS
                // conversation, and rebinding would import ITS scrollback
                // into the new chat (user report 2026-08-07). The grace
                // window alone is not enough — the JSONL only appears on the
                // first message, which may be minutes later.
                if (newSessionPending) return@runOnUiThread
                val folderKey = scrollbackFolderKey ?: return@runOnUiThread
                val sessionId = scrollbackSessionId ?: return@runOnUiThread
                val newFolderKey = ServerSessionFetcher.encodeDir(status.cwd)
                val folderChanged = newFolderKey != folderKey
                val sessionChanged = status.sessionId != null && status.sessionId != sessionId
                if (!folderChanged && !sessionChanged) return@runOnUiThread
                // The server's active conversation moved (manual /resume or
                // /cd): persist under the old key, rebind to the new one.
                persistScrollback()
                scrollbackFolderKey = newFolderKey
                scrollbackSessionId = status.sessionId ?: sessionId
                // Rebinding must include the per-conversation draft cache —
                // otherwise drafts written after the rebind land in the OLD
                // conversation's file and leak into it later (user report
                // 2026-08-10: a previous conversation's message appeared in
                // a new chat's history recall).
                inputHistory = InputHistory(filesDir, "$scrollbackFolderKey/$scrollbackSessionId")
                val store = scrollbackStore
                if (store != null) {
                    terminalOutput.importScrollbackText(
                        store.read(store.file(endpoint.id, newFolderKey, scrollbackSessionId!!)),
                    )
                }
                sessionPicker.markCurrent(status.cwd, scrollbackSessionId)
                android.widget.Toast.makeText(this, "Session switched", android.widget.Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** Runs the idle sweep (design 2026-08-11 §3.6). Diagnostic only: the
     *  count is logged; the server decides what is idle. Never logs ids. */
    private fun runSweep() {
        val fetcher = sessionFetcher ?: return
        val endpoint = activeEndpoint ?: return
        if (sshState != "CONNECTED" || sessionPicker.open || switchInFlight || sweepInFlight) return
        sweepInFlight = true
        Thread {
            try {
                val raw = fetcher.sweepIdle(endpoint.sessionName, endpoint.workspace)
                // Parse the count — the raw helper output contains a tab
                // that splits the log line (cosmetic bug 2026-08-13).
                val count = raw?.let(ServerSessionFetcher::parseSweepResult) ?: -1
                android.util.Log.i("RokidTerminal", "idle sweep: $count ended")
            } catch (error: Exception) {
                android.util.Log.w(
                    "RokidTerminal",
                    "idle sweep failed: ${error.message ?: error.javaClass.simpleName}",
                )
            } finally {
                sweepInFlight = false
            }
        }.start()
    }

    private fun reconnectActiveEndpoint() {
        val endpoint = activeEndpoint ?: return
        traceRecorder.reset()
        val identity = try {
            DeviceKeyStore(this, endpoint.id).getOrCreate()
        } catch (error: Exception) {
            terminalView.setState("KEY ERROR: ${error.message}")
            return
        }
        // Legacy command: recreates a missing tmux session WITH the Claude
        // launcher (pre-feature reconnect semantics); a surviving session
        // still short-circuits to a plain attach. The sync watcher
        // reconciles the conversation binding afterward.
        ssh.connect(endpoint, identity, legacy = true)
    }

    /**
     * App-private per-conversation scrollback persistence (design
     * 2026-08-07): history is captured in memory during a session and saved
     * on disconnect/exit under the bound conversation's key, then restored
     * when that conversation is bound again. Files live in filesDir (never
     * shared storage); bounded at ScrollbackStore.MAX_ROWS rows per file and
     * MAX_FILES per endpoint (LRU). The binding is set by bindScrollback.
     * Safe no-op while no conversation is bound (e.g. the connect-picker
     * phase).
     */
    private fun persistScrollback() {
        // activeEndpoint is cleared when the endpoint picker opens (Back out
        // of the terminal), but the bound conversation's scrollback must
        // still persist on exit — fall back to the last bound endpoint
        // (bug 2026-08-14: the file stayed at yesterday's timestamp because
        // every exit-time persist saw endpoint=false).
        val endpoint = activeEndpoint ?: lastBoundEndpoint
        val folderKey = scrollbackFolderKey
        val sessionId = scrollbackSessionId
        if (endpoint == null || folderKey == null || sessionId == null) {
            return
        }
        val store = scrollbackStore ?: return
        // Settle-trim first: streaming repaints copy the current turn into
        // the scrollback (and the screen), and the FILE must not carry the
        // duplicate (bug 2026-08-14: the persisted tail duplicated the
        // screen, and after reconnect the current turn rendered twice).
        terminalOutput.trimSettledScrollback()
        val rows = terminalOutput.exportScrollbackText()
        store.write(store.file(endpoint.id, folderKey, sessionId), rows)
        store.prune(endpoint.id)
        InputHistory.prune(filesDir)
        android.util.Log.i("RokidTerminal", "persisted ${rows.size} rows")
    }

    private fun loadScrollback(): List<String> {
        val endpoint = activeEndpoint ?: return emptyList()
        val folderKey = scrollbackFolderKey ?: return emptyList()
        val sessionId = scrollbackSessionId ?: return emptyList()
        val store = scrollbackStore ?: return emptyList()
        return store.read(store.file(endpoint.id, folderKey, sessionId))
    }

    private fun importPendingProfile() {
        try {
            endpointStore.importPendingProfile()?.let { profile ->
                DeviceKeyStore(this, profile.id).getOrCreate()
            }
        } catch (error: Exception) {
            android.util.Log.e("RokidTerminal", "Provisioning rejected: ${error.message}")
        }
    }

    private fun startSpeech() {
        if (mode != Mode.COMPOSER) return
        cancelKnobPicker()
        android.util.Log.i("RokidTerminal", "startSpeech: mode=COMPOSER")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w("RokidTerminal", "startSpeech: MIC PERMISSION DENIED")
            refreshComposer("MIC PERMISSION REQUIRED")
            ensureAudioPermission()
            return
        }
        android.util.Log.i("RokidTerminal", "startSpeech: mic permission ok, asrListening=${asr.isListening}")
        // Prefer the server ASR channel (asr-fwd SSH + HTTP transcribe). The
        // firmware has no usable Android SpeechRecognizer provider, so this is
        // the primary path when the ASR connection is up.
        if (asr.isListening) {
            android.util.Log.i("RokidTerminal", "startSpeech: stopping transcribe")
            asr.stopAndTranscribe()
            return
        }
        if (asr.startRecording()) {
            android.util.Log.i("RokidTerminal", "startSpeech: recording started")
            refreshComposer("RECORDING / CLICK TO STOP")
            return
        }
        android.util.Log.w("RokidTerminal", "startSpeech: recorder start FAILED")
        // Fall back to the local Android SpeechRecognizer (rarely available).
        if (speech.isAvailable) {
            speech.toggle()
        } else {
            refreshComposer(speech.unavailableStatus())
        }
    }

    private fun ensureAudioPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != AUDIO_PERMISSION || mode != Mode.COMPOSER) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            speech.start()
        } else {
            refreshComposer("MIC DENIED / USE KEYBOARD")
        }
    }

    companion object {
        /** Single/double-press arbitration window for the COIDEA right knob (contract). */
        const val RIGHT_KNOB_DOUBLE_WINDOW_MS = 500L

        /** Sync-watcher poll interval (design 2026-08-07 §3.3); see [sessionSyncRunnable]. */
        const val SESSION_SYNC_MS = 30_000L
        /** Incremental scrollback persistence threshold (2026-08-14). */
        private const val PERSIST_INCREMENT_ROWS = 500
        /** Knob picker stop-to-confirm window (user design 2026-08-14). */
        private const val KNOB_CONFIRM_MS = 1_000L
        /** Turn-end persist quiet window (2026-08-14): no output for this
         *  long = the turn is over, write the file. */
        private const val TURN_SETTLE_MS = 3_000L
        /** Idle-conversation sweep cadence (design 2026-08-11 §3.6). */
        const val SWEEP_INTERVAL_MS = 5 * 60_000L

        /** Placeholder title for freshly created conversations (until the
         *  server's first-message title is fetched). */
        const val NEW_CHAT_TITLE = "New chat"

        /** Scroll capture suppression window after a switch (resume replay). */
        private const val REPLAY_SUPPRESSION_MS = 10_000L

        /**
         * After a conversation switch the watcher suppresses rebinds for this
         * long: a new conversation's JSONL appears only on its first message,
         * so before that the server's newest session is still the previous
         * conversation (bug 1, 2026-08-07). discoverNewSessionId corrects the
         * real id during this window.
         */
        private const val SWITCH_GRACE_NANOS = 90L * 1_000_000_000L
        /** Prefs file for the remembered conversation target (see [rememberTarget]). */
        private const val SESSION_PREFS = "session_picker"

        /**
         * Local fallback command list (contract: never claim completeness;
         * the server helper adds custom commands when reachable).
         */
        /**
         * Full known built-in command set (server `claude` list + commands
         * verified in real use, 2026-08-06). The display list is built via
         * CommandPaletteState.displayList (bare "/" and the session-picker
         * action lead). `/resume` and `/continue` were removed 2026-08-07:
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

        /** Fast swipes emit DPAD pairs within a few ms; dedup the same arrow. */
        private const val SWIPE_PAIR_DEDUP_MS = 120L

        /** Double-ctrl+c exit suppression window (Claude Code exits on two). */
        private const val CTRL_C_DEDUP_NANOS = 2_000L * 1_000_000L

        /** Panel auto-exit timings (reply-signal design, 2026-08-07). */
        private const val PANEL_EXIT_POLL_MS = 1_000L
        private const val PANEL_EXIT_REPLY_NANOS = 2_000L * 1_000_000L

        /** AskUserQuestion enter/exit streaks (frame-driven, 2026-08-10). */
        private const val ASK_PANEL_DETECT_FRAMES = 2
        private const val ASK_PANEL_LOST_FRAMES = 2

        /** Multi-select submit: gap between the Enter that moves the focus
         *  to ✔ Submit and the Enter that submits (2026-08-10). */
        private const val ASK_SUBMIT_ENTER_DELAY_MS = 250L

        /** Type-something send: picker→text-input mode settle (chroxy). */
        private const val ASK_TYPE_SWITCH_DELAY_MS = 150L

        /** Type-something send: text→submit-Enter gap — the paste-burst
         *  window must close or a long draft's trailing \r is swallowed
         *  and the draft stays on the input line (user 2026-08-10). */
        private const val ASK_TEXT_SUBMIT_DELAY_MS = 800L

        /**
         * Drafts at or above this length go text-first, Enter separately —
         * Claude Code's paste-burst detector swallows a trailing \r that
         * arrives in the same read (2026-08-07).
         */
        private const val LONG_SEND_CHARS = 80
        // 800 ms, not 300: the paste-burst window grows with the draft
        // length — a 300 ms \r was still inside the window for longer
        // drafts and they stayed on the input line unsubmitted (user
        // report 2026-08-10, normal composer sends).
        private const val SEND_ENTER_DELAY_MS = 800L

        /** Verify-and-retry submit (design 2026-08-13): first check after
         *  the initial Enter; a stuck draft (paste-burst swallow) gets a
         *  bare retry Enter — fired only after the observed failure, so it
         *  is provably outside any burst window — then, after
         *  [VERIFY_MAX_RETRIES], a Ctrl+U clears the line so the stuck
         *  text cannot merge into the user's next message. */
        private const val VERIFY_FIRST_MS = 1500L
        private const val VERIFY_RECHECK_MS = 1500L
        private const val VERIFY_MAX_RETRIES = 2
        // 20 chars stays within the ~64-column viewport even for CJK (20
        // CJK = 40 columns) and can never coincide with Claude's
        // next-input suggestion.
        private const val VERIFY_TAIL_CHARS = 20

        private const val ARROW_UP = "up"
        private const val ARROW_DOWN = "down"
        private const val ARROW_LEFT = "left"
        private const val ARROW_RIGHT = "right"

        /** android.hardware.input.action.INPUT_DEVICE_CHANGED (not in this SDK's android.jar). */
        const val ACTION_INPUT_DEVICE_CHANGED = "android.hardware.input.action.INPUT_DEVICE_CHANGED"

        /** Keyboard-indicator polling interval; see [keyboardPoll]. */
        private const val KEYBOARD_POLL_MS = 1000L

        /** GO-button hold threshold for long press (Ring4). */
        const val GO_LONG_PRESS_MS = 800L
        private const val AUDIO_PERMISSION = 1001

        const val ACTION_LONG_PRESS = 1
        const val ACTION_SHUTTER = 2
        const val ACTION_AI_START = "com.android.action.ACTION_AI_START"
        const val ACTION_SPRITE_BUTTON_UP = "com.android.action.ACTION_SPRITE_BUTTON_UP"
    }
}
