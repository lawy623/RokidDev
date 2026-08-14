# RokidTerminal Project Guidance

RokidTerminal is an Android APK for Rokid Glass that connects to remote
development servers over SSH, attaches to a named `tmux` session, and runs
Claude Code through a server-side credential wrapper.

Keep this file short. Load only the focused rule files needed for the current
task.

## Rule File Index

- `rules/architecture.md`
  - Network/session model (direct connection, tmux sessions, reconnect rules)
  - Two-user SSH architecture: terminal (`rokid`) vs ASR (`asr-fwd`)
  - ASR app-side integration (`AsrProfile`, `ServerAsrClient`, `AsrController`)
- `rules/input.md`
  - Verified hardware input facts (including the long-press/shutter broadcast
    interception — `.docs/ROKID_INPUT_INTERACTIONS.md` §8)
  - Unified input architecture (`PhysicalInputMapper` / `TerminalAction`)
  - Input design contract: semantic action model, mappings, event
    diagnostics, context matrix, key-phase rules, hardware test matrix,
    acceptance criteria
  - Current interaction behavior by mode
- `rules/composer.md`
  - Composer interaction design (lifecycle, draft/cursor rules, ASR
    insertion, delete constraint)
  - Slash commands / command palette and busy-session queue verification
  - Local input composer implementation status
- `rules/rendering.md`
  - Terminal data pipeline (SSH → VT state machine → Canvas)
  - Rendering constraints (480x640, 54x36, UTF-8, VT subset, scrollback)
- `rules/voice.md`
  - Voice status and next direction (SpeechRecognizer, server ASR)
  - Verified server ASR path and privacy rules (no disk, no transcript logs)

The input design contract (semantic action model, mappings, diagnostics,
context matrix, key-phase rules, hardware test matrix, acceptance criteria)
lives in `rules/input.md`; the composer interaction design, slash-command
alternatives, and busy-session queue verification plan live in
`rules/composer.md`. Keep the rules synchronized when input behavior changes.

## Current status

Verified on the current glasses and Tencent Cloud server:

- SSH public-key login to the restricted `rokid` user works over public Wi-Fi.
- The server ED25519 host key is pinned and verified with Bouncy Castle on Android.
- `cloud-claude` survives SSH disconnects through `tmux`.
- The remote launcher is `/home/rokid/bin/rokid-claude`; it uses the `rokid` user's independent DeepSeek credential. New Claude processes intentionally start with `--dangerously-skip-permissions` at the user's request.
- New Claude processes start with `--effort max`. The app creates a missing tmux session in detached mode, but an existing session is attached without relaunching Claude and therefore keeps its current effort until `/effort max` is run or the session is restarted. The wrapper must pass through CLI arguments.
- The display uses a dynamically sized VT screen grid rather than an appended log tail. The current 480x640 view resolves to 54 columns x 36 rows (grid derived from the actual View size; verified on device via the frame trace, 2026-08-06).
- A first local input composer is implemented with Unicode-grapheme editing, local cursor movement, keyboard insertion, Backspace deletion, explicit send/cancel gestures, and a live-terminal overlay. Native Android `SpeechRecognizer` is not available on this firmware — voice dictation goes through the SERVER-side SenseVoice path instead (record → transcribe via `asr-fwd` → draft → send, hardware-verified 2026-08-05; see `rules/voice.md`). The standalone-glasses overlay is compact (about five wrapped draft lines at 480x640), shows a proportional vertical scrollbar for longer drafts, and scrolls only as needed to keep the grapheme-aware cursor visible.
- Idle terminal directions now browse bounded local scrollback: left/up = older, right/down = newer. They no longer emit PTY arrow sequences. Hardware-verified 2026-08-05: TP swipes emit DPAD pairs (`LEFT+UP`/`RIGHT+DOWN`); the app's LEFT/UP→older, RIGHT/DOWN→newer mapping keeps direction correct (scroll amount varies with stray keys).
- Speech input is verified end-to-end on hardware (2026-08-05): record →
  transcribe (server SenseVoice via the `asr-fwd` channel) → draft → send.
  Composer send is currently simulated (`simulateSend = true` in MainActivity)
  so development testing never touches the real Claude session; set false for
  real sends.
- TP long-press (send in composer) and Shutter (delete in composer) work via
  the intercepted system broadcasts (see `rules/input.md`).
- The ASR lifecycle is verified: login starts the service, exit stops it ~60 s
  later (memory freed); reconnecting within the window keeps it warm.

### Implemented 2026-08-05 (afternoon, hardware-verified)

- COIDEA KM keyboard fully profiled and mapped (two knobs ×5 actions =
  keys 7/8/9/A/B and C/D/E/F/G, six keys = 1-6; contract in
  `rules/input.md`).
- COIDEA semantic dispatch implemented: terminal mode (1 = return to
  bottom, 2/5 = terminal history, 3 = ctrl+c, 4/6 = input history,
  left-knob press = open composer, right-knob press = Back) and composer
  mode (2/4/5/6 = up/left/down/right incl. visual-line moves, 3 = delete,
  left-knob press = recording toggle, right-knob press = send /
  double-press 500 ms = cancel).
- Input history: local app-private cache (50 entries, list model with
  empty entry at the newest end, key 4 = older / 6 = newer, no wrap),
  preview rendered INTO the Claude Code `❯` input line (row located by
  scanning for `❯`; never the last row — the "bypass permissions" banner
  and tmux status line sit below), loaded into the composer on TP click /
  left-knob press.
- Blinking `_` input cursor (replaces the static frame cursor on the
  input line; 500 ms; small gap after `❯`). Pixel-width truncation for
  CJK previews.
- Keyboard-connected indicator (bottom-right glyph; 1 s poll because
  `INPUT_DEVICE_CHANGED` is not delivered on this firmware).
- Bluetooth keyboard connect/disconnect changes Android Configuration
  (keyboard/navigation) and relaunches the Activity unless
  `configChanges` covers `navigation` — fixed in the manifest.

### Implemented 2026-08-06

- INMO Ring4 (`INMO Ring 0762`, vendor 05ac/0220) profiled and bound:
  touchpad single=ENTER / double=BACKSPACE (firmware-resolved) / swipe
  LEFT-RIGHT (firmware reports inverted keycodes — corrected in the mapper,
  verified in real use) / **long-press=MOVE_HOME (122), NOT HOME (3) — the
  app accepts both**; GO button = KEY_F8 with app-side arbitration (hold
  >800 ms = long press, second press within 500 ms = double, else single).
  GO arbitration needed the `onKeyUp` hook — key UP was previously
  unhandled for ring keys. The GO button's trigger is position-sensitive:
  presses off the sweet spot produce zero events (hardware, not code).
- Ring glyph indicator (tilted band + prongs, no gem) next to the keyboard
  glyph; device detection via 1 s InputDevice poll.
- COIDEA knobs reassigned to a confirm/cancel axis (user decision): left
  knob = confirm (terminal: press opens composer; composer: single =
  recording, double = send), right knob = cancel (terminal: press = Back;
  composer: single = cancel).
- Composer-mode Shutter double press = command palette trigger (bound as a
  placeholder Toast first, then the real palette the same day; first press
  still deletes immediately).
- Endpoint list masks IPv4 middle octets and hides the port for
  screen-recording safety (`user@43.xx.xx.209`).

### Verified 2026-08-06 (real Claude-Code interaction)

- Real send verified end-to-end: composer (speech/typed) → SSH → Claude
  Code session; `simulateSend` is now `false`. Two messages sent during a
  busy session — send path and follow-up input work.
- ctrl+c interrupt verified (COIDEA key 3 / right-knob single / GO long
  press / Shutter double — see contract).
- `NEW OUTPUT` popup banner verified while browsing history with live
  output arriving.
- Input-history browse direction fixed (key 4 from the empty entry recalls
  the NEWEST draft; sequence [oldest…newest] → empty → suggestion).
- Suggestion mechanism (Claude's `❯`-line light text) integrated: right
  key fills it dark, TP/ring long-press fills it, empty entry shows the
  remote light text; never stored in history.
- **Streaming-output capture (2026-08-13/14, hardware-verified)**: Claude
  Code repaints streaming output by OVERWRITING rows in place (cursor
  addressing + erase-to-EOL — trace evidence: sweeps writing at column 5),
  so the shift-matching below misses every frame of fast output (a
  `seq 1 2000` run left only ~30 rows). The emulator now snapshots a
  SETTLED row's old content right before the first overwrite of that row
  in a chunk (`TerminalScreen.maybeCaptureRowBeforeOverwrite`): stability
  50 ms (blocks TCP-split partial repaints, passes ~100 ms streaming
  ticks), no column gate, bottom 7 rows excluded (input/divider/status —
  raised from 3 when the "Cooking for" spinner line started flooding the
  history). Captures are PROVISIONAL for one chunk (a shift capture
  discards them — displaced rows stay on screen; a quiet settle or the
  150-row cap flushes them as genuine scrolled-off content, filtered
  against rows still on screen). Verified: `seq 1 2000` is fully
  browsable live.
- **Capture noise rules (2026-08-14, `ClaudeStatusRows`)**: Claude's TUI
  paints transient chrome that must never enter history, matched by
  CONTENT (positions shift between versions — "Cooking for" at row 29,
  "Combobulating…" at row 28):
  - thinking/tool status rows: spinner glyph (✻✶✽✢✹) + ellipsis verb,
    ticking timer (`for 3m 59s`, `(30s ·`), `(thinking…)` state;
  - pipe-form markdown tables (`| 方式 | 行为 | 现状 |`): streaming
    INTERMEDIATES — the final repaint renders box-drawing tables, and the
    pipe copy (misaligned) must not become history;
  - tool-execution blocks (`⎿ $ bash …`, `⎿ Output: …`, `⎿ Tip: …`): tool
    chrome, not conversation content;
  - **repaint-fragment rule**: Claude repaints the response area
    non-sequentially, so an overwritten row is often an intermediate
    fragment whose FINAL form is still on screen — skip a capture whose
    text is contained in any other on-screen row (≥6 chars; `❯` user rows
    exempt). One general rule that prevents the jumbled multi-generation
    fragments, duplicated box tables and repeated lines.
  `purgeStatusRows()` (status + pipe-table + tool rows) cleans rows
  captured before the signatures existed; it runs on settle and on every
  persist, so files are rewritten clean.
- **Persistence hardening (2026-08-14)**: scrollback now persists
  INCREMENTALLY — every 500 new rows AND at every conversation-turn end
  (3 s of output quiet, while the user is reading/typing — an invisible
  write point; `persistOnSettle`), so an abnormal exit loses at most the
  last few dozen rows instead of the whole session. `persistScrollback`
  falls back to `lastBoundEndpoint` when Backing out of the terminal
  cleared `activeEndpoint` (bug: the file stayed at yesterday's
  timestamp). Every persist first runs `trimSettledScrollback()` (suffix
  trim + noise purge) so the FILE never carries the current turn's
  repaint copies or noise rows.
- **Import wrapping (2026-08-14)**: the server transcript export emits
  LOGICAL lines (cap raised 100 → 2000 chars — long user prompts were
  truncated before the app could wrap them). The import now WRAPS lines
  at the 54-column grid instead of truncating (`TerminalScreen.textRows`,
  SGR backgrounds carry across wrapped rows), so restored history wraps
  and completes exactly like the live screen.
- **Reconnect dedup + style unification (2026-08-14)**: after a resume,
  the imported export tail equals the attached screen's leading rows — the
  browse view (scrollback + live screen) showed the tail twice. Fixed at
  the RENDER level: `snapshot()` computes `screenOverlap()` (scrollback
  tail rows matching the screen's leading rows, whitespace-insensitive)
  and skips them — the screen supplies them once. No timing, always
  correct. 2026-08-14 second anchor: streaming repaint copies put the
  current turn's rows in the scrollback AND on screen; when the forward
  anchor misses (leading rows never captured), an UPWARD anchor (the
  scrollback's last row matched against the screen, walked upward) covers
  it. Imported history is styled like the live screen: rows get the
  TUI's 2-space indent and `❯` user rows get the `ESC[48;5;237m`/`49m`
  background marker (the exact marker live-captured rows carry) so
  browsing imported history looks like in-app live history.
- Scrollback capture fixed (2026-08-06, root-caused from the device
  trace): `snapshotRows()` returned live row arrays shared with the
  screen, so `consume()` mutated the "before" state and shift detection
  compared the new screen against itself — it never fired. Replaced with
  baseline-based detection in `TerminalOutputProcessor`: the processor
  snapshots the last settled alternate screen (re-baselined on entry and
  after ~500 ms quiet), and on each frame finds the smallest shift k where
  ≥60% of rows (spanning ≥60%) match the baseline k rows below, with the
  first matched row in the top 3 rows (blank-tail re-renders like the
  tmux clock must not fabricate history). Captured rows are the baseline
  rows that vanished from the shifted region (region-aware, not top-k),
  so a pinned header is never stored as history. Claude Code's redraws
  arrive split across TCP reads; the baseline survives partial frames and
  captures once the full render lands. The baseline is taken only after
  ~500 ms of quiet — never mid-burst — and real scrolls are NOT captured
  at all (both were attach-burst artifacts duplicating the live screen;
  fixed 2026-08-06 after the "only the last round browsable" report,
  verified by replaying the real attach byte stream: burst now yields 0
  rows). Hardware-verified 2026-08-06: single-session history grows long.
  Regression suite: `ScrollCaptureRegressionTest` (7 tests, byte streams
  mirror the real 54x36 tmux/Claude redraw).
- Scrollback persistence (2026-08-06, incremental 2026-08-14): history is
  saved per endpoint to app-private `filesDir/scrollback_<endpointId>.txt`
  (text rows with inline SGR `48;5;Nm`/`49m` markers so user-message
  background fills survive the round trip; older plain-text files still
  import) on Back/exit, SSH disconnect, `onStop`/`onDestroy`, every 500
  new rows, and at every conversation-turn end (3 s quiet — the user is
  idle then); restored on connect via `importScrollbackText` (primary
  screen only; ignored while the alternate screen is active). Storage is
  bounded: the file keeps only the most recent `PERSISTED_SCROLLBACK_ROWS`
  (1000) rows (~50-150 exchanges, ~55 KB/endpoint) and is overwritten
  each session — it never accumulates. In-memory browsing keeps the full
  5000-row cap. Live screen content still comes from the tmux attach
  redraw — only the scrollback is persisted. Files are now keyed per
  conversation (2026-08-07); see Open/pending.
- SGR background colors parsed and rendered as light fills (Claude Code
  user-message blocks separated from output).
- Terminal-mode Shutter swapped (2026-08-06): single = return to bottom
  (immediate, high-frequency), double = ctrl+c (low-frequency).
- COIDEA terminal-mode right knob: single = ctrl+c, double = exit
  terminal (disconnect to endpoints).
- Endpoint list masks IPv4 middle octets and hides the port.
- Composer vertical cursor moves (COIDEA keys 2/5) are pixel-aligned with
  the renderer's wrapping (`TerminalView.moveCursorVertical` uses the same
  `buildComposerLines` + measureText, with textSize pinned to the composer's
  16f) — logical-column wrapping mismatched CJK text.
- Claude also prefixes CONVERSATION user messages with `❯`, so
  `findInputRow` prefers the bottom five rows before falling back to any
  `❯` row. While browsing history the input line sits at
  `cursor.row + offset` in the viewport; once it scrolls out of view
  `findInputRow` returns null (every remaining ❯ row is a conversation
  message). Final rendering split (user decision 2026-08-06): the INPUT
  LINE keeps its original look — raw `❯` glyph, blinking `_` cursor, no
  dark fill; CONVERSATION user messages (live AND history) render as a
  small green box plus the dark block fill — imported rows that lost
  their SGR background get the fill inferred from the `❯` prefix, and
  persisted rows carry inline SGR `48;5;Nm` markers so the fill survives
  the round trip.
- Background fills verified in history after the persistence/render
  fixes (2026-08-06); the persisted-file MOTD junk from the pre-fix
  build was truncated on-device (current builds never create it — real
  scrolls are not captured).
- Suggestion model (final, 2026-08-06): browse sequence is
  [oldest…newest] → empty (remote light suggestion visible) → suggestion
  (dark); pointer starts at the empty entry; key 6 walks to the dark
  suggestion repeatedly; the suggestion is never stored in history.
- Command palette (2026-08-06): local modal list in the composer overlay
  (selected item highlighted, /skills-style), insert-on-confirm into the
  draft, Escape-like literal-slash restore on cancel; dynamic command list
  fetched once per connection from the server helper (file enumeration of
  Claude command/skill dirs), local built-in defaults as fallback.

### Implemented 2026-08-07

- Conversation picker at connect time and in-session via the palette's
  `[Switch Chat]` action (two-level: folders → conversations; contract in
  `rules/input.md` Part 4).
- Server `rokid-sessions` helper (`list`/`status`/`switch` verbs) for
  session discovery and switching.
- Scrollback persistence keyed per conversation
  (`scrollback_<endpointId>_<folderKey>_<sessionId>.txt`; 1000 rows/file,
  30 files/endpoint LRU).
- Sync watcher (30 s poll via `rokid-sessions status`) re-binds local
  history on out-of-band session changes (manual `/resume`, `/cd`).
- Conversation deletion via the armed selector (long-press arm, two-option
  `Cancel | Delete` bar, confirm-on-delete removes server transcript + local
  scrollback file).
- TP double-tap cancel in the conversation picker (not Back).
- Folder-list cache-first with background refresh (per app run; a fresh SSH
  fetch takes seconds on this network — the picker shows the last fetched
  list instantly; stale folders are safe because the server `switch` verb
  re-validates the target dir).
- Input history is per-conversation (2026-08-07): each conversation owns its
  own draft cache file `input_history_<folderKey>_<sessionId>.txt` (50
  entries); the legacy global `input_history.txt` was DISCARDED (user
  decision — test drafts only, no migration).
- Picker fast-swipe pair dedup: the TP fast swipe emits DPAD PAIRS
  (LEFT+UP / RIGHT+DOWN) within a few ms; the picker dedups the same
  direction within 120 ms (same rule as panel mode), otherwise one swipe
  moves two list items (user report 2026-08-07).
- Picker overlay is full-bleed OPAQUE below the top info bar. Hardware
  lesson: `drawRingIcon`/`drawKeyboardIcon` leave `paint.style = STROKE`,
  so a later fill rect drawn without an explicit `Paint.Style.FILL` renders
  as a hollow outline — the "transparent picker" bug (2026-08-07).

### Hardware-verified 2026-08-07 (evening round — conversation lifecycle)

- Session-id convergence: the app-generated UUID (via `--session-id`) may
  not match the server's real session file (the JSONL appears only on the
  first message). `discoverNewSessionId` polls `status` after a new-chat
  switch and rebinds to the REAL id (persist/remember/▶/cache/input-history
  key), never reverting to the pre-switch session; the 30 s sync watcher
  suppresses rebinds during a 90 s fresh-switch grace.
- New chats appear instantly via a `New chat` cache placeholder; after the
  first send, `refreshNewChatTitleIfNeeded` refetches so the real title
  replaces it without exiting the terminal.
- The folder-list refresh applies live at BOTH levels (new folders/new chats
  appear while browsing, position preserved by path/session id).
- The in-session switcher starts its cursor on the current (▶) conversation;
  the connect flow keeps the `+ New Chat` default.
- Scrollback: `bindScrollback` resets the screen state first (stale
  alt-screen flag blocked imports); resumed conversations pull the full
  transcript via the helper's `export` verb and force-import it (assistant
  messages included — rewritten transcripts keep them under
  `message.content`).
- Input locks: delete (`DELETING…`/`PLEASE WAIT`, picker locked, ▶ current
  never armed) and switch (`switchInFlight` consumes ALL input: keyDown/
  keyUp incl. GO arbitration, key-multiple, long-press/Shutter broadcasts,
  composer/picker opens, Back).
- Storage bounds: per-conversation input history (50 entries, 30-file LRU
  prune), scrollback (1000 rows/file, 30 files/endpoint), voice never on
  disk, debug traces ring-bounded 256 KB.
- Double-ctrl+c exit protection: `sendCtrlC()` drops a second ctrl+c within
  2 s, so Claude Code's double-ctrl+c session exit can never fire from the
  glasses (user decision 2026-08-07; the desktop habit is not wanted here).
  Applies to all four ctrl+c paths (COIDEA key 3, right-knob single, Shutter
  double, GO long).
- Command-panel auto-exit on reply (2026-08-07): the panel exits ~2 s after
  Claude's reply renders — input line back to the bare `❯ ` prompt AND the
  screen content above the input line changed from the panel-entry
  fingerprint. Held while a numbered (vertical) picker is on screen:
  `/usage`'s two-level pickers keep numbered rows with a bare prompt at the
  bottom, which alone misfired the old signal (auto-exited mid-picker,
  leaving the picker rendered).
- Full implementation notes: `docs/superpowers/specs/2026-08-07-multi-
  conversation-design.md` §8.

### Hardware-verified 2026-08-07 (real server + glasses)

- The JSch exec channel on this firmware does NOT deliver EOF: the remote
  command exits 0 and its output arrives via `available()`/`read()`, but
  EOF never comes within the timeout. Reads must return on a quiet period
  (~750 ms), never wait for EOF or discard on timeout (the latter silently
  dropped every helper response).
- `java.io.ByteArrayOutputStream.toString(Charset)` is missing on this
  firmware (API 33+) and throws `NoSuchMethodError` — an `Error`, NOT an
  `Exception`, so `catch (Exception)` does not catch it and the fetch
  thread crashes. Use `bytes.toString("UTF-8")` (ServerCommandFetcher had
  the same latent issue).
- The server helper command must NOT embed `|| true` before appended
  arguments: `$HELPER list ...` with `HELPER="... || true"` swallows the
  verb (shell runs the helper with no args and `true` wins) — every verb
  invocation failed silently and the picker only ever showed the /srv
  fallback.

### Hardware-verified 2026-08-07 (evening round 2 — long input + cross-chat leaks)

- **Long drafts never submitted (paste-burst)**: Claude Code's paste-burst
  detector treats a trailing `\r` arriving in the SAME read as long text as
  a newline — the text renders in the input line but is never submitted.
  100-500 char drafts died on the input line; short drafts worked because
  the burst window closed before the `\r` arrived. Fix (2026-08-07/10):
  `sendTextWithEnter` — drafts ≥80 chars go text-first, Enter alone after
  800 ms (the burst window grows with the draft length; 300 ms was not
  enough). **Final hardening 2026-08-13 — verify-and-retry submit
  (deterministic, no window guessing)**: a fixed delay is a guess that
  network jitter can break (the window is measured from the remote's first
  received byte). `scheduleSubmitVerify` now watches the rendered input
  line after every send: if the draft's tail is still there once the
  stream has settled, a bare retry Enter is sent — fired only after the
  OBSERVED failure, seconds after the burst, so it is provably outside any
  burst window — and after 2 failed retries the stuck line is cleared with
  Ctrl+U (Ink TextInput) so it cannot merge into the user's next message.
  Verified on-device 2026-08-13: 470- and 750-char drafts submit
  correctly. Covers the normal composer AND the AskUserQuestion
  Type-something path.
- **New-chat history leak (sync watcher clobber)**: a new conversation's
  server JSONL only appears on the FIRST message, so `status` reports the
  PREVIOUS conversation as newest until then. The 90 s grace alone was not
  enough — after it expired (e.g. during a long dictation), the sync
  watcher rebound to the previous conversation and imported ITS scrollback
  into the new chat. Fix: `newSessionPending` — the watcher skips rebinds
  while a fresh chat's real id has not converged; cleared by discovery
  convergence or the next switch, and the send path restarts the discovery
  loop after the first message.
- **New-chat input-history recall empty**: drafts sent under the
  placeholder session id were orphaned when discovery converged to the
  real id (the history was rebound to a new key). Fix:
  `InputHistory.migrate` — the placeholder-key file is renamed to the
  real-id key at convergence (never clobbers an existing target).

### Implemented 2026-08-10 (AskUserQuestion interactive panel)

- Auto-detects Claude Code's AskUserQuestion picker (signature rows
  `Type something` / `Chat about this` — command pickers never show them;
  the multi-select form renders "Type something" WITHOUT the period) and
  enters a panel sub-state (`askPanelMode`). The panel is EMBEDDED in the
  terminal like the command panels — no overlay is drawn (user decision
  2026-08-10; only the composer pops on Type-something confirm).
  Selection is MIRRORED every frame from the input-line echo
  (`❯ 1. 标题`). Auto-enter needs NO user key: the per-frame 2-frame
  streak is complemented by a 1 s poll (the frame path can stall when the
  network goes quiet right after the panel renders — user report
  2026-08-10). Exits when the panel disappears or the reply renders.
- **Two panel forms**: single-select (no checkboxes; confirm = Enter) and
  multi-select (`[ ]`/`[x]` checkboxes — single click TOGGLES on regular
  options via space, long press / left-knob double submits via Enter
  (Enter+Enter: the first moves onto the ✔ Submit zone, the second
  commits)). Type something. renders with a `[ ]` in multi-select and can
  be selected as a REGULAR option submitted through Submit; as the
  free-text entry (single click summons the composer) it is excluded from
  toggling and blocks on checked options (mutually exclusive). Ask-panel
  swipes are ALWAYS vertical with fast-swipe pair dedup (one swipe = one
  option). Single select, multi select and long-draft sends are all
  HARDWARE-VERIFIED (2026-08-10).
- `Type something.` — a SINGLE click (TP / ring touchpad / left-knob
  single) summons the composer; NO key is sent at open (the panel stays
  in its option state, so cancelling the composer is a pure local return
  to the choices). On SEND the picker is switched into text-input mode by
  the option's DIGIT (an initial Enter would submit an EMPTY answer =
  "User Declined to answer question", verified 2026-08-10), then the
  text, then a delayed \r submits. `Chat about this` sends DIRECTLY
  (Enter) and starts the next round — never opens the composer (user
  2026-08-10). Cancel keys are NO-OPs while the panel is open (must be
  answered). Header: `ASK PANEL / SELECT TYPE ESC`.
- Pure-logic layer `AskPanelParser` (detect/parse/mirror/checkbox, no
  Android deps) with unit tests against the real device frames (8 tests).
- `/effort`-style command panels are untouched: the `Enter to select`
  help line alone does NOT trigger detection (AskUserQuestion rows are
  the anchor). Contract: `rules/input.md` Part 5. Future unknown panel
  forms are extended per real capture (user note 2026-08-10).

### Open / pending

- **Session resume support** — implemented 2026-08-07 (conversation picker,
  `rokid-sessions` helper, per-conversation scrollback keying, sync
  watcher); design doc: `.superpowers/sdd/2026-08-07-multi-conversation/`,
  plan docs in the same directory.
- ~~**Claude interactive panels with input fields**~~ — **implemented and
  hardware-verified 2026-08-10** (AskUserQuestion: single-select,
  checkbox multi-select, and the TAB zone form; contract
  `rules/input.md` Part 5, spec
  `docs/superpowers/specs/2026-08-10-interactive-panel-design.md`).
  Verified on-device: single select, multi select (incl. consecutive
  rounds), Type-something free text (digit protocol), long drafts.
  Future AskUserQuestion layout variants are handled per real case
  (contract notes in `rules/input.md` Part 5).
- **Concurrent sessions (option B)** — implemented 2026-08-11, hardened
  and hardware-verified 2026-08-13: one tmux window per conversation
  (`rokid-<id>`) in the shared session; `switch` is attach semantics (never
  restarts a live process), `status` reports the active window and
  self-heals stale window names, `delete` kills the conversation's window,
  `adopt` renames on new-chat id convergence, and `sweep` ends idle
  background conversations (default 3 h, triple-signal guard: JSONL age +
  child presence + CPU sampling; the active window is never swept). All
  window targeting is by INDEX — tmux window names are not unique, and a
  duplicate name breaks name-based targets ("can't find window"); the
  sweep self-heals duplicates by renaming them to their identified
  conversation. New-chat discover converges ONLY to sessions that appeared
  after the switch (never an arbitrary old conversation — the "new chat
  reusing old id/history" bug). Deleting a never-messaged new chat
  succeeds (its window IS the conversation). Verified on-device:
  background tasks survive switches/app-exit, two conversations run
  concurrently, delete cleans windows/processes/files, idle sweep fires
  every 5 min while connected, dead processes respawn via `--resume`.
  Spec: `docs/superpowers/specs/2026-08-11-concurrent-sessions-design.md`.
  **The server needs the UPDATED helper** — deploy with
  `bash server/deploy.sh <user>@<host>` (env overrides
  `ROKID_SESSIONS_PROJECTS_DIR` / `ROKID_SESSIONS_LAUNCHER` for other
  servers). The app's local isolation (per-conversation scrollback + input
  history) is unchanged.
- **HUD: clock + battery (2026-08-12/13)** — top-right on the title line:
  12-hour AM/PM clock ("3:45PM", no space, 30 s ticker so the minute flips
  while idle) + battery glyph with percent and a ⚡ bolt right of it while
  charging. Battery is pushed via the sticky `ACTION_BATTERY_CHANGED`
  broadcast (fires immediately on register, then on change — zero polling).
- **ASR emoji strip (2026-08-13)** — the server ASR model appends a
  trailing emoji to results; `stripTrailingEmoji` removes a trailing emoji
  run (Unicode block ranges + ZWJ/skin-tone/keycap/variation-selector
  sequences, including ©️/™️-style text-presentation emoji) before the text
  lands in the composer draft. Pure function, 13 JVM tests.
- ~~Local terminal history too short / not persistent~~ — **fixed
  2026-08-06**: root cause was the shared-array bug above; baseline-based
  detection deployed and hardware-verified (single-session history grows
  long), per-endpoint scrollback persistence deployed
  (`scrollback rows:` diagnostic log remains in place).
- ~~Command palette~~ — **implemented 2026-08-06, only a few `/` commands
  tested** (see `rules/composer.md`): local modal list, triggers COIDEA
  key 1 / Shutter double / Ring long-press / `/` at prefix position;
  server-assisted list via `ServerCommandFetcher` + `server/rokid-commands`
  helper (custom commands by file enumeration), local defaults as
  fallback. Future interaction issues are possible with untested
  pickers — handle per-command (user note 2026-08-06).
- ~~Second ring device~~ — dropped 2026-08-06 (the other INMO ring does
  not power on reliably; supported external input is INMO Ring4 + COIDEA
  KM keyboard only).
- ~~Ring4 panel gestures~~ — verified 2026-08-06: touchpad long = MOVE_HOME
  (fixed), GO double works in the panel (F8 routed to the GO arbitration
  past the strict isolation), horizontal-panel swipes invert-corrected.
- ~~Busy-session queue semantics~~ — **verified 2026-08-06** against the
  real tmux/Claude session: type-ahead while Claude is busy works like a
  desktop terminal (messages queued by Claude Code, processed in order
  after the running task; running-task output stays visible). Contract in
  `rules/composer.md`.

## Security invariants

- Never embed or log a server password, private key, API key, token, `.env` value, or Claude credential.
- One physical device and one endpoint use a dedicated SSH identity. Private keys stay in app-private storage, encrypted with Android Keystore AES-GCM.
- Decrypted private-key bytes must be cleared after JSch imports the identity.
- Keep `android:allowBackup="false"` and do not place credentials on `/sdcard` or other shared storage.
- Require strict host authentication. Endpoint profiles contain a complete, independently verified ED25519 `known_hosts` line. Do not implement trust-on-first-use or an "accept any host" fallback.
- The pinned repository compares the endpoint host, ED25519 algorithm, and raw key bytes. Keep `server_host_key=ssh-ed25519`.
- Bouncy Castle is required because the Rokid Android image does not provide an Ed25519 signature implementation usable by JSch.
- Imported profiles must use an unprivileged server account. Reject `root`, `ubuntu`, `admin`, `administrator`, and `ec2-user`.
- The server-side `rokid` user must not have `sudo` or membership in `docker`, `lxd`, `disk`, `adm`, or similar privileged groups.
- Recommended `authorized_keys` restrictions are `no-agent-forwarding,no-port-forwarding,no-X11-forwarding,no-user-rc`. Do not add `no-pty`; Claude Code and tmux require a PTY.
- The remote Claude launcher is fixed in the APK as `/home/rokid/bin/rokid-claude`; endpoint profiles must not supply arbitrary commands.
- Permission prompts are intentionally bypassed with `--dangerously-skip-permissions` for this dedicated remote-terminal workflow. Keep the SSH account unprivileged, and do not extend this choice into `sudo`, Docker, root access, or arbitrary endpoint-supplied commands.
- Windows use `FLAG_KEEP_SCREEN_ON`. At the user’s explicit request, `FLAG_SECURE` (screenshot protection) is disabled during development so ADB screenshots and terminal-stream debugging work; it will be ENABLED once the README screenshots are captured (2026-08-14 — open-source hardening).
- The exported launcher must not accept endpoint configuration through Intent extras. Debug provisioning uses an app-private one-shot JSON file copied with ADB `run-as`, then deletes it after import.
- Do not add arbitrary voice-to-shell execution. Future speech text must be shown as a draft and explicitly confirmed before it is sent to Claude.
- Never log draft text, partial/final speech recognition text, terminal body text, or source code to logcat.

## Build and verification

```bash
./dev.sh build
./dev.sh run
./dev.sh log
```

For terminal parser changes, run:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest assembleDebug
```

Before a hardware handoff, verify:

- `adb install -r` preserves the endpoint profile and encrypted identity.
- Verify `KEEP_SCREEN_ON` remains enabled and `FLAG_SECURE` stays disabled during development so ADB screenshots and app-private terminal traces are available; enable it after the README screenshots are captured (2026-08-14).
- Wi-Fi has a validated default network and the server SSH port is reachable.
- Host-key rejection, public-key authentication, reconnect, tmux resume, Back behavior, focus, and the default 480x640 -> 46x30 redraw all behave correctly.
- No API keys, private keys, Claude output, or source code are printed to logcat.
- A release build disables debugging and removes `run-as` provisioning/debug inspection paths before long-term use.
