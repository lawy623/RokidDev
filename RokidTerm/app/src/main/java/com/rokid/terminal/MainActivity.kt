package com.rokid.terminal

import android.Manifest
import android.content.pm.ApplicationInfo
import android.app.Activity
import android.content.pm.PackageManager
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
    private val speechDraft = SpeechDraftState(composer)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var endpoints: List<EndpointProfile> = emptyList()
    private var selectedIndex = 0
    private var activeEndpoint: EndpointProfile? = null
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
    /** True once the user moved the folder selection (live refresh skips). */
    private var folderSelectionMoved = false

    /** Swipe pair-dedup for the picker (fast swipes emit DPAD pairs). */
    private var lastPickerSwipe: String? = null
    private var lastPickerSwipeTime = 0L

    private var lastScrollbackCount = -1
    private var scrollbackStore: ScrollbackStore? = null
    private var scrollbackFolderKey: String? = null
    private var scrollbackSessionId: String? = null

    // Lazy: field initializers run before attachBaseContext, so a direct
    // getSharedPreferences here NPEs at activity instantiation (crash fixed
    // 2026-08-08, caught on device).
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

    private val drainTerminalFrame = Runnable {
        pendingTerminalFrame.getAndSet(null)?.let(terminalView::setTerminalFrame)
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
        // (Panel mode has NO auto-exit: the input-line signal proved
        // unreliable with two-level pickers like /usage, where the line
        // clears transiently during row switches — it exited panel mode
        // mid-interaction. Panel mode ends only on explicit cancel.
        // 2026-08-06.)
        terminalFrameScheduled.set(false)
        if (pendingTerminalFrame.get() != null) scheduleTerminalFrame()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        endpointStore = EndpointStore(this)
        traceRecorder = TerminalTraceRecorder(filesDir)
        importPendingProfile()

        terminalView = TerminalView(this)
        inputHistory = InputHistory(this)

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
                    // Part 3: TP long press = confirm (Enter).
                    if (sshState == "CONNECTED") ssh.sendEnter()
                } else {
                    // Terminal mode: send ESC — the glasses' cancel/escape
                    // gesture (closes Claude's own pickers/menus; this
                    // firmware delivers the long press as a broadcast, so
                    // KEYCODE_TV never fires). The suggestion fill moved to
                    // the ring long press (2026-08-06).
                    if (sshState == "CONNECTED") ssh.sendEscape()
                }
            }
            ACTION_SHUTTER -> {
                if (mode == Mode.COMPOSER) {
                    // Single press = immediate grapheme delete (never part of
                    // an arbitration window, contract); a second press within
                    // 500 ms = command palette (placeholder, not implemented).
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
        if (mode != Mode.ENDPOINTS && isPrimaryKey(keyCode)) {
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
        // double press can cancel the picker (2026-08-08).
        if (sessionPicker.open) {
            if (isRingKey(event) && keyCode == KeyEvent.KEYCODE_F8) handleGoKey(event)
            return true
        }
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
        // the conversation picker is open.
        if (sessionPicker.open) return true
        event.characters?.takeIf { it.isNotEmpty() }?.let { characters ->
            when (mode) {
                Mode.COMPOSER -> {
                    prepareManualComposerEdit()
                    composer.insertText(characters)
                    refreshComposer("EDITING / CLICK TO LISTEN")
                    return true
                }
                Mode.TERMINAL -> if (sshState == "CONNECTED") {
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
                        ssh.sendCharacters("")
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
                if (event.repeatCount == 0) ssh.sendCharacters("")
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
                if (sshState == "CONNECTED") ssh.sendCharacters("")
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
                publishTerminalFrame(terminalOutput.scrollOlder())
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                publishTerminalFrame(terminalOutput.scrollNewer())
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                persistScrollback()
                ssh.disconnect()
                asr.disconnect()
                showEndpointPicker()
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
            if (sshState == "CONNECTED") ssh.sendCharacters("")
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
                if (panelMode) {
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
        android.util.Log.i("RokidTerminal", "mode -> COMPOSER (openComposer)")
        panelMode = false
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
        // Drop any in-flight recording: its audio must never be transcribed
        // into the draft after the text has already been sent.
        asr.cancelRecording()
        speechDraft.commitActive()
        val text = composer.text
        speech.cancel()
        if (simulateSend) {
            android.widget.Toast.makeText(this, "TEST SEND OK: $text", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            ssh.sendText(text)
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
        updateHeader()
        clearPrimaryGesture()
    }

    private fun prepareManualComposerEdit() {
        // Stop the current recognition round before moving or editing its active
        // hypothesis. This prevents a late partial/final callback from anchoring
        // another span and duplicating text after a manual edit.
        speech.cancel()
        speechDraft.commitActive()
    }

    private fun refreshComposer(status: String? = null) {
        if (mode != Mode.COMPOSER) return
        if (status != null) composerStatus = status
        terminalView.showComposer(composer.text, composer.cursor, composerStatus)
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
        // isolation until the result lands, 2026-08-08).
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
                // swipe moves two items (2026-08-08).
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
        folderSelectionMoved = true
        sessionPicker.move(delta)
        sessionPickerSyncToView()
    }

    private fun sessionPickerArmDelete() {
        if (sessionPicker.armDelete()) sessionPickerSyncToView()
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
        sessionPickerSyncToView()
        if (target == null) return // descended to the conversation level
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

    private fun enterPanelMode() {
        if (mode != Mode.TERMINAL || panelMode) return
        panelMode = true
        android.util.Log.i("RokidTerminal", "mode -> PANEL (command panel passthrough)")
        updateHeader()
    }

    private fun cancelPanelMode() {
        if (!panelMode) return
        panelMode = false
        panelAxisSticky = null
        panelAxisCommand = null
        android.util.Log.i("RokidTerminal", "mode -> TERMINAL (panel exited)")
        updateHeader()
    }

    /**
     * Panel keys (user contract 2026-08-06): navigate = arrows, confirm =
     * Enter, cancel = ESC + exit. Bindings: Rokid TP long = confirm, TP
     * double = cancel; keyboard left knob single = confirm, right knob
     * single = cancel; Ring touchpad long = confirm, GO single = cancel.
     * Everything else is blocked while the panel is open.
     */
    private fun handlePanelKey(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
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
        KeyEvent.KEYCODE_8 -> {
            // Left knob single = confirm (Enter).
            if (event.repeatCount == 0) ssh.sendEnter()
            true
        }
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MOVE_HOME -> {
            // Ring touchpad long press = confirm (Enter).
            if (event.repeatCount == 0) ssh.sendEnter()
            true
        }
        KeyEvent.KEYCODE_D -> {
            // Right knob single = cancel & return (ESC + exit).
            if (event.repeatCount == 0) {
                ssh.sendEscape()
                cancelPanelMode()
            }
            true
        }
        KeyEvent.KEYCODE_BACK -> {
            // Back = ESC to the PTY (cancel the picker) + leave panel mode.
            if (event.repeatCount == 0) {
                ssh.sendEscape()
                cancelPanelMode()
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
            "COMMAND PANEL / NAV CONFIRM CANCEL"
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
        if (terminalOutput.scrollOffset > 0) return
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

    private fun openSessionPicker(connectMode: Boolean) {
        val endpoint = activeEndpoint ?: return
        sessionPickerConnectMode = connectMode
        clearPrimaryGesture()
        folderSelectionMoved = false
        sessionPicker.open(rememberedFolder(endpoint.id), rememberedSession(endpoint.id))
        // Cache-first (user request 2026-08-08): the last fetched folder
        // list shows instantly (a fresh SSH fetch takes seconds on this
        // network), then refreshSessionFolders updates it in the background.
        // Stale entries are safe: the server switch verb re-validates the
        // target directory, so a deleted folder fails with an error, never
        // a hang. The cache is in-memory (per app run), not persisted.
        val cached = cachedFolders
        if (cached != null) {
            sessionPicker.setFolders(cached, failed = false)
            // Pre-select the remembered folder when it is still listed
            // (user decision 2026-08-08); otherwise the base dir stays.
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
                cachedFolders = fresh
                if (sessionPicker.open && sessionPicker.level == 0 && !folderSelectionMoved) {
                    sessionPicker.setFolders(fresh, failed = folders == null)
                    sessionPicker.selectFolder(sessionPicker.currentFolderPath)
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
                    updateHeader()
                }
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
                    sessionPickerSyncToView()
                    android.widget.Toast.makeText(this, "Session deleted", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    sessionPickerSyncToView()
                    android.widget.Toast.makeText(this, "Delete failed", android.widget.Toast.LENGTH_SHORT).show()
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
                android.widget.Toast.makeText(this, "Session switched", android.widget.Toast.LENGTH_SHORT).show()
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

        /** Fast swipes emit DPAD pairs within a few ms; dedup the same arrow. */
        private const val SWIPE_PAIR_DEDUP_MS = 120L
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
