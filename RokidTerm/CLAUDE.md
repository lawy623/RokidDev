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
- A first local input composer is implemented with Unicode-grapheme editing, local cursor movement, keyboard insertion, Backspace deletion, explicit send/cancel gestures, and a live-terminal overlay. Native Android `SpeechRecognizer` is not available on this firmware, so functional voice dictation is still not implemented. The standalone-glasses overlay is compact (about five wrapped draft lines at 480x640), shows a proportional vertical scrollbar for longer drafts, and scrolls only as needed to keep the grapheme-aware cursor visible.
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
  placeholder Toast; palette not implemented; first press still deletes
  immediately).
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
- Scrollback persistence (2026-08-06): history is saved per endpoint to
  app-private `filesDir/scrollback_<endpointId>.txt` (text rows with inline
  SGR `48;5;Nm`/`49m` markers so user-message background fills survive the
  round trip; older plain-text files still import) on
  Back/exit, SSH disconnect, and `onStop`/`onDestroy`; restored on
  connect via `importScrollbackText` (primary screen only; ignored while
  the alternate screen is active). Storage is bounded: the file keeps
  only the most recent `PERSISTED_SCROLLBACK_ROWS` (1000) rows (~50-150
  exchanges, ~55 KB/endpoint) and is overwritten each session — it never
  accumulates. In-memory browsing keeps the full 5000-row cap. Live
  screen content still comes from the tmux attach redraw — only the
  scrollback is persisted. Files are keyed per endpoint today; when
  session-resume lands, key them per Claude session/conversation instead.
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

### Open / pending

- **Session resume support** — pick and resume a previous Claude Code
  conversation (server-side `claude --resume` session list, browsable and
  selectable from the glasses; user request 2026-08-06). When this lands,
  scrollback persistence must be keyed per conversation/session, not per
  endpoint (user requirement 2026-08-06: different conversations get
  different history files).
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
- Panel-mode picker axis: heuristic detection (numbered rows = vertical)
  with sticky axis + bounce; if future Claude picker layouts break the
  heuristic, add per-command axis overrides instead of extending it
  (user note 2026-08-06; contract in `rules/input.md` Part 3).
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
- Windows use `FLAG_KEEP_SCREEN_ON`. At the user’s explicit request during active development, `FLAG_SECURE` is currently disabled for every build so ADB screenshots and terminal-stream debugging remain available. Re-enabling screenshot protection before production release is a deliberate future hardening task.
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
- During active development, verify `KEEP_SCREEN_ON` remains enabled and `FLAG_SECURE` remains deliberately disabled so ADB screenshots and app-private terminal traces are available. Re-enable screenshot protection as a separate production-hardening step.
- Wi-Fi has a validated default network and the server SSH port is reachable.
- Host-key rejection, public-key authentication, reconnect, tmux resume, Back behavior, focus, and the default 480x640 -> 46x30 redraw all behave correctly.
- No API keys, private keys, Claude output, or source code are printed to logcat.
- A release build disables debugging and removes `run-as` provisioning/debug inspection paths before long-term use.
