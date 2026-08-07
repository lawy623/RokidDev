# RokidTerm rules: Composer

Loaded on demand from `CLAUDE.md`. The local input composer: interaction
design, slash commands, busy-session queue strategy, and implementation
status. The unified input architecture and semantic action model live in
`input.md`; speech paths in `voice.md`.

## Composer interaction design

### Lifecycle

When a terminal is connected and no local composer is open:

1. A verified single-click/confirm gesture first returns any historical
   viewport to the live bottom, then opens a dedicated input composer
   overlay.
2. Single click in the composer starts recording (`RECORDING`); a second
   single click stops and transcribes (`TRANSCRIBING`). Recording does not
   auto-start on open, so background noise is never captured by accident.
3. Recognized speech is inserted into a local editable draft, never directly
   into the PTY.
4. Remote `TerminalFrame` updates continue while the composer is open. The
   overlay must not pause SSH reads or terminal parsing.
5. Closing the composer reveals the latest terminal frame, not the frame
   that existed when the composer opened.

While the composer is open:

- Left/right touch-pad navigation moves the local draft cursor rather than
  sending VT arrow sequences. Up/down is consumed and reserved for the
  future command palette so it cannot leak into the PTY.
- The right-side Shutter/Capture button is the desired standalone delete
  control and removes the grapheme immediately before the cursor. On the
  current firmware it arrives as the intercepted `ACTION_SPRITE_BUTTON_UP`
  broadcast (see `input.md`); standard `KEYCODE_CAMERA` remains the
  provisional fallback.
- TP single-click toggles recording; it never deletes text, avoiding
  ambiguity with TP double-click cancellation.
- Voice listening remains available, so a later utterance can insert text at
  the current cursor.
- A compact hint area below the draft shows the currently valid
  interactions, for example:
  `单击录音 左右移动 快门删字 长按发送 双击/Back取消`.
- A visible listening indicator distinguishes listening, transcribing,
  paused, and ASR-error states.
- Long press sends a non-empty finalized draft to Claude. On the current
  firmware it arrives as the intercepted `ACTION_AI_START` broadcast.
- Double-click cancels the composer, stops/discards any active ASR
  hypothesis, discards the entire unsent draft, and returns to the
  terminal-only view.
- Android Back (`KEYCODE_BACK`) is a reliable fallback cancellation path
  separate from Shutter/Capture; it discards the whole unsent draft and
  closes the overlay.

Long press, double-click, and single-click must be recognized as mutually
exclusive semantic gestures. A long press or double-click must not also
trigger the single-click action. Sending or discarding also discards any
in-flight recording.

### Draft text and cursor rules

The composer is a text editor, not a terminal-screen cursor:

- Store cursor positions at Unicode grapheme-cluster boundaries, not
  arbitrary UTF-16 indices.
- Deletion removes the previous grapheme cluster so Chinese characters,
  emoji, combining marks, variation selectors, and ZWJ sequences are not
  split.
- Rendering may wrap the draft visually, but the logical text and cursor
  remain independent from terminal rows and columns. Standalone-glasses
  left/right gestures move only the grapheme-aware cursor and must not
  directly pan the draft viewport. The compact viewport may scroll
  minimally and automatically only when needed to keep the cursor-visible
  wrapped line on screen.
- Empty-draft send is a no-op with a local hint; it must not send a bare
  Enter unless the UI exposes a separate explicit terminal-Enter action.
- The composer must have a bounded maximum length and show
  truncation/refusal locally rather than silently dropping text.
- Draft text remains app-local until `SendComposerDraft` succeeds.
- JVM tests cover Chinese insertion and deletion/movement across surrogate
  emoji, combining sequences, emoji modifiers, flags, and ZWJ emoji.

### Continuous ASR insertion

ASR partial results must be handled as a replaceable hypothesis span to
avoid duplicated text:

1. The first partial result creates an active span anchored at the current
   cursor.
2. Later partial hypotheses replace that same span rather than appending
   every hypothesis.
3. A final result commits the span and moves the cursor to its end.
4. If the user moves the cursor or deletes while a partial phrase exists,
   commit the latest usable hypothesis or explicitly cancel it before
   applying the edit. The next utterance starts at the new cursor.
5. ASR failure must retain already committed draft text and expose
   retry/pause state.

The selected ASR route is now the verified server-side path (see
`voice.md`); the ordinary Android `SpeechRecognizer` service is unavailable
on this firmware. Connecting a DJI Mic 2 changes the audio input route but
does not by itself provide application-visible button events. Its
microphone audio route and every physical control/HID event must be
profiled separately.

### Delete-control constraint

The right-side Shutter/Capture button is the selected standalone-glasses
`DeletePreviousGrapheme` control. On the current firmware the physical
shutter arrives as the intercepted `ACTION_SPRITE_BUTTON_UP` broadcast and
deletes immediately; `KEYCODE_CAMERA` remains provisionally supported and
`KEYCODE_FOCUS` is consumed without deletion because some two-stage controls
emit Focus before Camera. This provisional support does not establish the
actual Rokid hardware mapping on other firmware.

Hardware verification must still:

- Capture the button's real down/up/long/repeat behavior on the exact device
  and firmware.
- Verify whether it emits `KEYCODE_CAMERA`, a vendor keycode/scan code,
  multiple events, or no app-visible event on other firmware.
- Verify whether the system camera or another system component consumes it
  before the foreground Activity.
- Preserve Bluetooth `KEYCODE_DEL` as a fallback Backspace path. Do not
  assign `KEYCODE_NOTIFICATION` without captured evidence.

Shutter deletion is a distinct one-shot action on the first key-down
(`repeatCount == 0`) and must not wait for TP single/double-click
arbitration.

## Slash commands and busy-session follow-ups

### Implemented: local command palette (2026-08-06)

`OpenCommandPalette` is a local semantic action (state in
`CommandPaletteState`, rendering in `TerminalView.drawCommandPaletteList`):

1. Opens a compact local list of frequently used Claude commands.
2. Navigate the list using verified directional gestures or keyboard arrows.
3. Selecting an item inserts `/command` into the local composer.
4. The user may continue editing or dictating arguments.
5. Long press sends the completed command through the normal composer send
   path.
6. Cancelling the palette changes no remote PTY state.

Triggers (all composer mode): COIDEA key 1, Rokid Shutter double press,
Ring long-press (HOME), and typed `/` at command-prefix position (blank
before the cursor). `/` elsewhere is a literal slash. Cancelling a
`/`-opened palette restores the literal slash into the draft (Escape-like).

Navigation while open: COIDEA keys 2/5, TP up/down swipes, Ring left/right
swipes (right = next). Confirm: left knob single, TP single click, Ring
touchpad single. Cancel: right knob single, Back, key 1 again.

List source: local defaults (the FULL known built-in set, ~42 commands
sorted alphabetically with a bare `/` as the first item — confirming it
inserts only `/` so voice input can continue the command name; merged with
server customs in sorted order), `COMMAND_PALETTE_DEFAULTS`
plus a one-shot server fetch — `ServerCommandFetcher` runs the helper
`/home/rokid/bin/rokid-commands` (see `server/rokid-commands` in the repo)
over a short-lived SSH exec channel; the helper lists CUSTOM commands by
file enumeration of the Claude command/skill directories (structured data,
never scraped pixels). Fetched once per connection with the local list as
fallback; the UI never claims completeness.

- A local action item `[Switch Chat]` sits directly after the bare `/`
  (selecting it opens the conversation picker instead of inserting text;
  contract in `input.md` Part 4). `/resume` and `/continue` were removed
  from the defaults 2026-08-08 — the local picker supersedes them; typed or
  speech `/resume` still passes through, and the sync watcher re-binds local
  history when the session changes.

The `OpenCommandPalette` entry rule from 2026-08-05 is confirmed; the
server-assisted list source is now implemented. The optional live remote
slash mode below remains future work.

### Recommended first implementation: local command palette (original spec)

Add `OpenCommandPalette` as a local semantic action:

1. Open a compact local list of frequently used Claude commands.
2. Navigate the list using verified directional gestures or keyboard arrows.
3. Selecting an item inserts `/command` into the local composer.
4. The user may continue editing or dictating arguments.
5. Long press sends the completed command through the normal composer send
   path.
6. Cancelling the palette changes no remote PTY state.

For the mini-keyboard, pressing `/` at command-prefix position (for example,
the draft is empty or only whitespace precedes the cursor) may open this
palette. In other positions it inserts a literal `/`. The UI must provide a
deliberate way to insert a literal leading slash when needed.

Decision 2026-08-05: the `/`-prefix trigger is confirmed as the entry rule
(`/` at command-prefix position opens the palette; elsewhere literal). The
palette's command-list source is deferred — local static list first, a
server-side structured `--commands`-style listing later; never scrape
terminal pixels. Note the COIDEA KM keyboard has no `/` key: on it the
palette is bound directly to a physical action (e.g. left-knob
press+rotate-left, `KEY_F`) instead of a typed `/`.

For glasses-only use, bind `OpenCommandPalette` only after profiling a
distinct available gesture. Fast swipe up/down, a verified Function key, or
another configurable gesture are candidates; do not overload long-press
send or double-click cancel. Palette navigation must also be possible
without sacrificing cursor left/right editing.

Start with a small configurable list of frequent commands. Because Claude
versions, plugins, and project commands can change, do not claim that a
static list is complete. A later server-assisted discovery mechanism may
refresh the list, but it must use structured data or an explicit helper
rather than scraping arbitrary terminal pixels.

### Optional advanced implementation: live remote slash mode

A future mode may send `/` immediately to Claude's PTY and render Claude's
native menu while forwarding arrows and Enter. It always reflects Claude's
current commands, but it is not the default glasses design because:

- The remote PTY, not the local composer, then owns the pending text.
- Local cancel cannot reliably undo remote input without Escape/Ctrl-C
  cleanup.
- Speech insertion and cursor ownership become ambiguous.
- Reconnects and queued input are harder to reason about.

Direct slash passthrough may still be useful for a full Bluetooth keyboard
when the local composer is closed and the user explicitly chooses
raw-terminal mode.

### Input while Claude is busy

The local composer must remain available while Claude is processing and
streaming output. "Claude busy" is a display/status condition, not a reason
to disable draft editing.

For the first implementation:

1. Keep receiving, decoding, and rendering remote output behind the
   composer.
2. Let the user open, edit, and send another draft while Claude is busy.
3. On long press, write the finalized text plus Enter to the existing Claude
   PTY exactly once.
4. Show a local acknowledgement such as `已发送，等待 Claude 处理` without
   claiming the app can retract it.
5. Allow another blank composer to be opened immediately for additional
   instructions.

Type-ahead verified against the real tmux/Claude session on 2026-08-06:
a second (and third) message sent while Claude was busy (running `sleep
30`) behaved exactly like desktop terminal input — the sends landed on
the PTY immediately, Claude processed them in order after the task
completed, and the running task's own output stayed visible on the
glasses (the previous shell/PTY view continued). The local
acknowledgement `已发送，等待 Claude 处理` is accurate: bytes + Enter are
queued by Claude Code itself, not dropped. Do not claim app-side retract
or queue-management; interruption stays an explicit ctrl+c action.

Cancellation semantics must be explicit:

- Double-click cancels only the current **unsent local draft**.
- After bytes and Enter have been written to the PTY, RokidTerm cannot
  safely promise to retract that instruction.
- Interrupting the active Claude task is a separate explicit terminal
  action, not composer cancellation.

Do not implement an app-side FIFO that flushes by searching rendered text
for prompts, spinners, or status words. Claude/tmux output, viewport width,
themes, versions, and localization make visual readiness detection brittle.
If native Claude queuing is not reliable, add an app-owned FIFO only after
the server exposes an explicit structured busy/ready/accepted signal. Such
a protocol may later be provided by the launcher/wrapper or another
authenticated side channel.

### Required queue verification

Test the actual server session before finalizing behavior:

1. Start a long-running harmless Claude task.
2. While output/spinner activity continues, send a second harmless prompt
   through the same PTY.
3. Correlate semantic action, PTY-write category/timestamp, app-private
   terminal trace, and resulting frames.
4. Confirm whether Claude visibly acknowledges the queued input and
   processes it automatically after the current task.
5. Repeat with multiple prompts, empty input, Escape/interrupt,
   disconnect/reconnect, and tmux resume.
6. Record the Claude Code version and launcher arguments with the test
   result.

## Local input composer status

- `MainActivity` currently owns the incremental `TERMINAL`/`COMPOSER` state
  and gesture timing. This is acceptable for the first slice, but future
  device-specific mapping and interaction complexity should move into the
  `PhysicalInputMapper`/`InteractionController` architecture described in
  `input.md`.
- Verified on hardware (2026-08-05): single click opens the composer and
  toggles recording; long-press (intercepted `ACTION_AI_START`) sends;
  Shutter (intercepted `ACTION_SPRITE_BUTTON_UP`) deletes the previous
  grapheme immediately; Back cancels the draft. Double-click discard
  arbitration and TP swipe-direction ergonomics still require explicit
  firmware testing.
- `KEYCODE_TV` is accepted as a legacy long-press send event for firmware
  that still delivers it; on the current firmware the long press arrives via
  the intercepted broadcast and routes to the same send action. Shutter
  deletion does not share the primary click/double-click arbitration
  window.
- **Back, Shutter, and keyboard Backspace are distinct.** Back means
  Android `KEYCODE_BACK` (4), not the right-side photo button; it discards
  the entire unsent composer draft and closes the overlay. Standard Shutter
  `KEYCODE_CAMERA` provisionally deletes the grapheme before the cursor,
  while Bluetooth `KEYCODE_DEL` remains Backspace. Keep
  `KEYCODE_FORWARD_DEL` distinct when implemented.
- With the composer closed, left/up scrolls local history older and
  right/down scrolls newer; none of these sends PTY arrows. Left/right in
  the composer moves by grapheme. Up/down is consumed and reserved for the
  future command palette so it cannot leak into the PTY. Left/right never
  directly pans the composer viewport; wrapped-draft scrolling is an
  automatic minimal adjustment only when the cursor would leave the compact
  visible range.
- Printable Bluetooth keyboard input inserts at the local cursor. When the
  composer is closed, printable input continues to go directly to the
  connected PTY.
- Sending uses `SshTerminalSession.sendText`, which appends carriage return.
  Empty drafts and drafts while disconnected are never sent; disconnected
  drafts remain visible for retry/editing.
- Remote terminal frames continue to update while the composer overlay is
  open. `TerminalView` remains renderer-only; `InputComposerState` owns the
  local unsent buffer and cursor.
- The app provisionally accepts standard `KEYCODE_CAMERA` as Shutter delete
  and consumes `KEYCODE_FOCUS` without deletion. Do not claim the physical
  Rokid Shutter mapping is verified on other firmware, and do not assign
  `KEYCODE_NOTIFICATION`, Function, microphone, or other vendor controls
  until their actual events are profiled.
- Never log draft text, partial/final speech recognition text, terminal body
  text, or source code to logcat.
