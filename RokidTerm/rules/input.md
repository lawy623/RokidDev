# RokidTerm rules: Input

Loaded on demand from `CLAUDE.md`. Verified hardware input facts, the unified
input architecture, and the interaction design contract: semantic actions,
mappings, event diagnostics, context matrix, key-phase rules, and the
hardware test matrix. Composer interaction design and slash-command behavior
live in `composer.md`; speech status and ASR privacy in `voice.md`.

## Status and scope

Design contract: only items explicitly marked implemented or
hardware-verified are available; everything else is a requirement or future
work.

- Implemented and verified on hardware (2026-08-05): composer single-click
  open/toggle, long-press send and Shutter delete via the intercepted system
  broadcasts, speech record → transcribe → draft → send, ASR lifecycle (see
  `CLAUDE.md` → Current status).
- Software/JVM-verified but not yet hardware-verified: TP single/long/double
  timing arbitration on the current firmware, Bluetooth ring and
  mini-keyboard HID mappings, overlay ergonomics.
- Future work: `PhysicalInputMapper`/`InteractionController` refactor, slash
  palette, busy-session queue verification, verified ring/keyboard mappings.
- New device profiling results (ring, mini-keyboard, DJI Mic 2, Function key)
  are recorded here as they are captured; see "Event capture and diagnostics"
  and "Hardware test matrix".

## Hardware input facts

Do not rely only on the generic Rokid key table. This firmware has produced
these events in real logs:

- TP confirm/single action: `KEYCODE_ENTER` (66).
- A separate touch action has produced `KEYCODE_NOTIFICATION` (83) and was
  unhandled.
- Back: `KEYCODE_BACK` (4).
- ADB-injected keys are intermittent unless the display is awake and the
  Activity is actually resumed.
- **TP long-press and the Shutter/Capture button never arrive as KeyEvents.**
  The firmware's `PhoneWindowManager` consumes them before app dispatch and
  emits ordered broadcasts: long-press → `com.android.action.ACTION_AI_START`
  (system AI assistant), shutter →
  `com.android.action.ACTION_SPRITE_BUTTON_UP` (photo/video per
  `settings_interaction_shortPressFun`, default `picture`). The broadcasts
  carry `FLAG_RECEIVER_REGISTERED_ONLY` and the system-only
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, so **manifest receivers never
  fire**; only a foreground dynamic receiver with `priority = 1000` can
  intercept them (same pattern as RokidMusic; details and raw event codes in
  `.docs/ROKID_INPUT_INTERACTIONS.md` §8). Implemented as
  `MainActivity.systemKeyReceiver` (registered in `onStart`, unregistered in
  `onStop`): it aborts the broadcast and forwards the semantic action —
  composer long-press = send, shutter = delete previous grapheme. Verified
  2026-08-05: logcat `system key intercepted: long-press/shutter`, system
  assistant and camera never launch while the app is foreground; both work
  normally again outside the app.

Current behavior:

- Endpoint list: swipe/direction chooses, Enter/center connects, Back exits.
- Terminal: Back cancels a draft; with no draft it disconnects and returns to
  endpoints.
- Error/disconnected terminal: center/confirm retries.
- With no composer open, left/up directions browse three rows toward older
  local terminal history and right/down browse toward newer history. They
  are clamped locally and do not emit PTY arrow sequences. Opening the
  composer returns to the live bottom. The software mapping is JVM-tested;
  the exact physical TP direction, repeated-event rate, and slow/fast-swipe
  ergonomics still require firmware testing.
- Explicit remote arrow forwarding remains a future
  raw-terminal/full-keyboard context; do not restore it as the default idle
  behavior. Enter forwarding and future speech confirmation still need an
  interaction redesign because the physical confirm action is also
  `KEYCODE_ENTER`.
- The full speech path (record → transcribe → draft → confirm → send) is now
  verified on hardware (2026-08-05); details in `rules/voice.md`.

## Design goals

- Give the user a complete basic interaction path without carrying another
  device.
- Make the Bluetooth ring and mini-keyboard faster optional control
  surfaces, not separate product paths.
- Allow glasses, ring, and keyboard controls to coexist without selecting an
  exclusive active controller.
- Keep microphone/audio-source selection independent from the physical
  controller currently producing actions.
- Keep physical Android key details outside terminal and UI-state behavior.
- Preserve normal terminal semantics, including Enter, arrows, Backspace,
  Escape, text insertion, modifiers, and key repeat.
- Provide a dedicated local composer so speech and edits are safe before
  transmission.
- Allow the user to prepare another instruction while Claude is still
  working.
- Make Claude slash commands usable without coupling the local draft to
  Claude's live PTY menu.
- Make unknown firmware and Bluetooth HID mappings observable before
  assigning behavior.
- Allow replayable tests to correlate a physical input event with the bytes
  sent to the remote PTY and the resulting terminal frame.
- Let a user browse recent terminal output locally without injecting arrow
  keys into Claude or another remote full-screen application.

## Required architecture

Speech/audio input and physical control input are two independent dimensions.
Speech may come from the glasses microphone or a verified external
microphone such as DJI Mic 2. Physical controls may come simultaneously from
the glasses, a Bluetooth ring, and an optional Bluetooth mini-keyboard
through one semantic input layer. The expected keyboard has approximately
Up, Down, Left, Right, Delete/Backspace, `/`, and possibly a recording key;
the ring and keyboard mappings are requirements to profile, not verified
Android keycodes.

Physical input must be normalized into semantic actions before it is
interpreted by the current UI state or encoded for SSH:

```text
Rokid control / ring HID event / keyboard HID event / future control
                         |
                  Android input event
                         |
              RawInputEvent diagnostics
                         |
                PhysicalInputMapper
                         |
                  TerminalAction
                         |
               InteractionController
                  /               \
       local UI/state change     PTY encoder
                                      |
                                SSH/tmux/Claude
```

Suggested responsibilities:

- **`RawInputEvent`**: immutable diagnostic representation of the Android
  event and source metadata, including enough device identity to distinguish
  glasses, ring, keyboard, and future controllers.
- **`PhysicalInputMapper`**: maps verified physical events and printable
  characters into semantic actions. It must not decide what a selection
  screen, composer, or terminal should do.
- **`TerminalAction`**: source-independent intent such as navigation, draft
  editing, terminal input, confirmation, cancellation, command-palette
  access, or recording control.
- **`InteractionController`**: interprets actions according to the current
  interaction state.
- **`InputComposerState`**: owns the unsent text buffer, grapheme-aware
  cursor, active ASR hypothesis span, listening state, hint text, and any
  local send status.
- **PTY encoder/session**: converts terminal actions into characters or
  control sequences and sends them only when a terminal is connected and
  focused.

`MainActivity` may dispatch events and render state, but it should not remain
the permanent owner of device-specific key mapping, draft text, gesture
timing, or the full interaction state machine.

Do not add global "glasses," "ring," or "keyboard" modes and do not select an
exclusive active controller. All three may stay connected and alternate
within one session. Controller disconnects must not clear the draft or
disable remaining controllers.

## Semantic action model

The exact Kotlin names may change, but the unified layer must be able to
represent at least:

```text
NavigateUp / NavigateDown / NavigateLeft / NavigateRight
ScrollTerminalHistoryOlder / ScrollTerminalHistoryNewer / ReturnTerminalToLive
Confirm / Cancel
OpenComposer / SendComposerDraft / DiscardComposerDraft / DeletePreviousGrapheme
TerminalEnter / TerminalBackspace / TerminalForwardDelete / TerminalEscape
InsertText(text)
StartOrStopRecording
OpenCommandPalette
Reconnect
OpenTargetPicker
```

Important distinctions:

- **Back, Shutter, and keyboard Backspace are not the same control.**
  `KEYCODE_BACK` cancels/leaves the local interaction state and is not the
  right-side photo button. Shutter/Capture is the intended
  standalone-glasses `DeletePreviousGrapheme` control. Standard
  `KEYCODE_CAMERA` is supported provisionally until the physical event is
  profiled. `KEYCODE_DEL` remains Bluetooth Backspace, and
  `KEYCODE_FORWARD_DEL`, if emitted, is a separate future forward-delete
  action.
- `Confirm` is not automatically the same action as `TerminalEnter`.
- `Cancel` is not automatically the same action as `TerminalEscape` or
  terminal Backspace.
- `SendComposerDraft` and `DiscardComposerDraft` are local composer
  operations, not raw terminal keys.
- Backspace and forward delete must remain distinguishable.
- `/` remains ordinary printable text in general. In composer
  command-prefix context it may invoke `OpenCommandPalette`; it must still be
  possible to insert a literal `/` in paths or prose.
- A recording key should map to `StartOrStopRecording` only after its real
  event has been captured and verified.
- Printable Bluetooth keyboard input inserts at the local cursor; when the
  composer is closed it goes directly to the connected PTY.

## Known and unknown physical mappings

### Verified or previously observed on the current glasses

- Primary TP confirmation has produced `KEYCODE_ENTER` (66), not necessarily
  the older documented `KEYCODE_DPAD_CENTER` (23).
- Another touch action has produced `KEYCODE_NOTIFICATION` (83), but its
  meaning is not yet assigned.
- Android Back produces `KEYCODE_BACK` (4): cancel/navigation, not
  Shutter/Capture.
- Long-press and Shutter arrive as intercepted ordered broadcasts, not
  KeyEvents (see "Hardware input facts").
- Firmware behavior varies; these observations must not become assumptions
  for every Rokid device.

### COIDEA KM verified mapping (2026-08-05)

Full-keyboard profiling completed on the current glasses over BLE HID. All
events arrive as standard HID keyboard reports on the keyboard channel
(`/dev/input/event4`), routed by Android under the `COIDEA KM Consumer
Control` device name (both input devices share descriptor
`78b1f500026fb0ca732a3b3a8b3fbd8afd16518d`, and the Consumer Control
EventHub mapping includes the keyboard node). `meta=0x200000`
(`META_FUNCTION_ON`) is set on every event.

| Physical control | Kernel EV_KEY | HID usage | App keyCode / scan |
|---|---|---|---|
| Six bottom keys 1-6 | `KEY_1`-`KEY_6` | 0x1e-0x23 | `KEYCODE_1`-`KEYCODE_6` (scan 2-7) |
| Left knob rotate left / press / rotate right (per detent) | `KEY_7` / `KEY_8` / `KEY_9` | 0x24/0x25/0x26 | `KEYCODE_7`/`8`/`9` (scan 8-10) |
| Left knob press+rotate left / press+rotate right (per detent) | `KEY_A` / `KEY_B` | 0x04/0x05 | `KEYCODE_A`/`B` (scan 30/48) |
| Right knob rotate left / press / rotate right (per detent) | `KEY_C` / `KEY_D` / `KEY_E` | 0x06/0x07/0x08 | `KEYCODE_C`/`D`/`E` (scan 46/32/18) |
| Right knob press+rotate left / press+rotate right (per detent) | `KEY_F` / `KEY_G` | 0x09/0x0a | `KEYCODE_F`/`G` (scan 33/34) |

Design regularity: each knob occupies five consecutive keys
(7/8/9/A/B and C/D/E/F/G), identical order
(left / press / right / press+left / press+right), one event per detent.
`getevent` on this firmware takes only one device argument.

The keyboard has no touchpad — only the two knobs, six bottom keys, and a
power switch (left=on, right=off). The `COIDEA KM Mouse` input device
(event3) exists in the HID registration but the hardware never reports on
it; ignore it. The power switch is not mappable and needs no key handling —
device availability is judged by the Bluetooth connection state (see the
firmware constraints below).

### Device interaction contract (2026-08-05, user-approved)

Same physical control may map to different semantic actions per mode
(context-aware interpretation, per the matrix in "Context-aware
interpretation"). Entries marked ⚠️ are unverified and must be tested before
implementation.

**Part 1 — terminal mode (composer closed):**

| # | Requirement | Rokid control | COIDEA KM control | INMO Ring4 control |
|---|---|---|---|---|
| 1 | Browse terminal history (up=older/down=newer) | TP left/right swipe (3 rows per event, verified) | Keys 2 / 5 | Touchpad left/right swipe (firmware reports inverted keycodes; corrected in the mapper so left swipe = older — verified 2026-08-06) |
| 2 | Browse input history (prev/next draft, shown in the input bar) | — (not offered on the glasses; re-typing is acceptable — user decision 2026-08-05) | Keys 4 / 6, **only while terminal-history offset is 0 (live)** — disabled while browsing terminal history so two history states never overlap (user decision 2026-08-05). Sequence (final 2026-08-06): [oldest…newest] → empty (remote light suggestion) → dark suggestion; pointer starts at empty; 4 from empty = newest; 6 to dark suggestion repeatedly; suggestion never stored | — (not offered) |
| 3 | Open composer (auto-loads the previewed draft if any) | TP single click (verified) | Press left knob (`KEY_8`) | Touchpad single click |
| 4 | ctrl+c interrupt Claude | **Shutter double press** (500 ms window; swapped 2026-08-06 so the high-frequency single press is not misread as ctrl+c) | Key 3 / right knob single press (`KEY_D`, 500 ms) | GO long press (`KEY_F8`, hold >800 ms) |
| 5 | `NEW OUTPUT` indicator | Purely visual | — | — |
| 6 | Return to live / bottom (offset → 0, one key for both: live-with-output and quiet-bottom are the same offset-0 state) | **Shutter single press** (immediate, no arbitration delay; high-frequency action) | Key 1 | Touchpad double click (`KEY_BACKSPACE`) |
| 7 | Back / disconnect to endpoints | TP Back (verified) | **Right knob double press** (`KEY_D` ×2, 500 ms; single = ctrl+c) | GO double click (`KEY_F8` ×2, 500 ms window) |

**Part 2 — composer mode:**

| # | Requirement | Rokid control | COIDEA KM control | INMO Ring4 control |
|---|---|---|---|---|
| 1 | Open composer (confirm) | TP single click (verified) | Press left knob (`KEY_8`) — same key as Part 1 #3 | Touchpad single click |
| 2 | Recording toggle (start/stop + transcribe) | TP single click (verified) | Left knob single press (`KEY_8`) while composer open (500 ms window vs double=send) — same toggle logic as TP single click | Touchpad single click (same as TP) |
| 3 | Cursor movement (grapheme-level) | TP left/right swipe (verified) | Keys 2/4/5/6 = up/left/down/right | Touchpad left/right swipe |
| 4 | Delete previous grapheme | Shutter (verified broadcast) | Key 3 | Touchpad double click (`KEY_BACKSPACE` — natural match) |
| 5 | Send (non-empty draft, discards in-flight recording) | TP long-press (verified broadcast) | **Left knob double press** (`KEY_8` ×2, 500 ms window; single = recording) | Touchpad long press (`KEY_HOME`) — swapped with GO single 2026-08-06 so TP long = send matches the Rokid TP |
| 6 | Cancel/discard whole draft | TP double-click / Back (verified) | **Right knob single press** (`KEY_D`; double press is a harmless second cancel) | GO double click (`KEY_F8` ×2, 500 ms window) |
| 7 | Command palette (trigger, implemented 2026-08-06) | **Shutter double press** (second press within 500 ms; first press still deletes immediately) | Key 1 | GO single click (`KEY_F8` short) — swapped with touchpad long 2026-08-06 |
| 8 | Command palette (navigate / confirm / cancel) | TP up/down swipe = move, TP single click = confirm (inserts `/command` into the draft) | Keys 2/5 = move, left knob single = confirm, right knob single / Back = cancel | Touchpad left/right swipe = move (right = next), touchpad single = confirm, touchpad double = cancel |

**Part 3 — command panel mode (Claude's own picker/menu open, 2026-08-06):**

After sending a `/`-prefixed command (palette-selected or typed), Claude
opens its own picker (e.g. `/model`); the app enters panel passthrough so
navigation keys reach the PTY instead of browsing local history. Entered
automatically on `/`-send; exited by cancel (ESC + exit) or auto-exit
when the picker finishes.

| # | Requirement | Rokid control | COIDEA KM control | INMO Ring4 control |
|---|---|---|---|---|
| 1 | Up / down (navigate picker) | TP up/down swipe (PTY arrows) | Keys 2 / 5 | Touchpad left/right swipe (right = down) |
| 2 | Left / right | TP left/right swipe (PTY arrows) | Keys 4 / 6 | Touchpad left/right swipe (ring gestures corrected — right-swipe sends arrow-right) |
| 3 | Confirm (Enter) | **TP long press** | Left knob single (`KEY_8`) | Touchpad long press (`KEY_HOME`) |
| 4 | Cancel picker (ESC) + exit | **TP double click** | Right knob single (`KEY_D`) | GO double click (`KEY_F8` ×2, 500 ms window — consistent with the Back/cancel double in the other modes) |
| 5 | Back (ESC + exit, same as cancel) | TP Back | Back | — |

Bindings per user decision 2026-08-06 (Rokid: TP long = confirm / TP
double = cancel-return; keyboard: left knob = confirm / right knob =
cancel-return; Ring: touchpad long = confirm / GO single = cancel-return).
STRICT ISOLATION: while the panel is open ONLY nav/confirm/cancel act —
all other keys (history, ctrl+c, shutter, TP single, composer, input
history) are blocked until panel mode exits.

Panel mode shows `COMMAND PANEL / NAV CONFIRM CANCEL` in the header.
NO AUTO-EXIT (removed 2026-08-06 — the input-line signal proved unreliable
with two-level pickers like `/usage`, exiting mid-interaction): panel mode
ends only on explicit cancel (TP double / right knob / GO double / Back,
all ESC + exit). Opening the composer or reconnecting also exits panel
mode.

AXIS-ADAPTIVE SWIPE (user decision 2026-08-06): the glasses/ring have a
single swipe gesture which adapts to the picker's detected axis
(`TerminalView.pickerAxis()` — numbered option rows above the input line
mean VERTICAL, e.g. `/model`; otherwise HORIZONTAL, e.g. `/effort`'s
"←/→ to adjust" slider or `/usage`'s first-level tabs). Vertical: up/left
= up, down/right = down. Horizontal: left/up = left, right/down = right
(ring arrivals inverted-corrected; fast-swipe DPAD pairs deduped). The
keyboard keeps full 2D control (keys 2/5/4/6); level switching in 2D
pickers stays keyboard-only.

AXIS DETECTION (`TerminalView.pickerAxis()`): find the input row (during
a picker it is replaced by the ❯ focus marker), scan the ~12 rows above,
count rows matching a numbered-item pattern (`\d\.`) — ≥2 numbered rows
mean VERTICAL (e.g. `/model`'s "1. … 2. … 3. …" list); anything else is
HORIZONTAL (e.g. `/usage`'s first-level tabs, `/effort`'s "←/→ to adjust"
slider). This is a heuristic over Claude Code's current rendering
conventions. FUTURE: if other picker layouts appear, give the axis
detection per-command overrides (a per-picker configuration map) instead
of extending the heuristic (user note 2026-08-06).

STICKY AXIS + BOUNCE (2026-08-06): the axis is detected ONCE per picker
(keyed by the pending command on the input line) and kept sticky — /model's
picker re-renders without its numbered rows once the effort slider is
focused, which would flip a per-frame detection to horizontal
mid-interaction. In a vertical picker the glasses also bounce off the
slider/header (`pickerBounceDirection()`): when the focus (❯ marker) is
outside the numbered list, the swipe sends the arrow back toward the list,
so the glasses never adjust the effort slider — the keyboard can.

STALE-PICKER RECOVERY: if the app restarts/reconnects while Claude has a
picker open, panel mode is off and the picker cannot be navigated — TP
long press now sends ESC in terminal mode (the glasses' cancel gesture;
this firmware delivers long press as a broadcast, so KEYCODE_TV never
fires) — ctrl+c (key 3 / Shutter double / GO long) also works. The
suggestion fill moved to Ring long press. Re-send the command normally
after closing the picker.

Ring4 notes (updated 2026-08-06): composer GO single = command palette,
composer touchpad long = send (swapped); panel cancel = GO double; GO long
(ctrl+c) has no composer use — confirmed unnecessary, stays unused there.
touchpad double-click arrives as `KEY_BACKSPACE` directly
(firmware resolves the double-click — no app-side arbitration needed for
delete/return-bottom); all GO actions share `KEY_F8` and need app-side
arbitration (hold >800 ms = long press; second press within 500 ms =
double; else single). Ring swipe directions ARE corrected in the mapper
(left gesture → older/left semantics) — real use showed the raw inverted
keycodes were wrong (verified 2026-08-06).

Mode-dependent reuse (same control, different meaning by mode): Shutter =
interrupt single / return-to-live double (terminal, 500 ms arbitration) /
delete single (composer, immediate — delete does NOT share an arbitration
window); TP long-press = send (composer only, no terminal meaning); Key 1 =
return-to-live (terminal) / command palette (composer); Key 3 = ctrl+c
(terminal) / delete (composer); Keys 2/5/4/6 = history (terminal) / arrows
(composer) / picker nav (panel). COIDEA knobs follow a confirm/cancel axis (user decision
2026-08-06): LEFT knob = confirm (open composer in terminal; recording
single / send double in composer), RIGHT knob = cancel (Back in terminal;
cancel single in composer).

Knob rotation detents (`KEY_7`/`KEY_9` on the left knob, `KEY_C`/`KEY_E`
on the right knob) and press+rotate combos (`KEY_A`/`KEY_B`/`KEY_F`/`KEY_G`)
are hardware-verified but **not assigned** in this contract — reserved for
future use. Terminology: "press left knob / press right knob" means the
physical click; rotation is always stated as rotate-left/rotate-right.

Unverified mechanics to test before implementation:

- Knob press is a fixed ~20 ms down/up pair regardless of hold duration —
  hold duration is NOT measurable (firmware reports REP_DELAY/REP_PERIOD
  both 0 and never emits repeats). Any hold-based action is impossible;
  use click/double-click/detent-count instead. Recording toggle is therefore
  knob-L single click (`KEY_8`), same toggle logic as TP single click
  (tested 2026-08-05).
- **Double-press right knob** (`KEY_D` twice) as cancel — measured gap
  118 ms on hardware (2026-08-05). Arbitration window is **500 ms**
  (user-set 2026-08-05): misreading double-press as single-press would send
  a draft the user meant to discard (worst failure), so the window favors
  double-press detection; the cost is a 500 ms send delay on right-knob
  press in composer mode. No other action uses `KEY_D`, so no misfire
  path.
- **All TP swipes (single- and two-finger alike) emit `KEYCODE_NOTIFICATION`
  (83, kernel `KEY_DASHBOARD`)** — the 83 event is a generic "swipe"
  marker and CANNOT distinguish one finger from two. Two-finger input
  history browsing is therefore not implementable on the TP; input-history
  browsing is keyboard-only (Keys 4/6, user decision 2026-08-05).
  Directional info comes from accompanying DPAD keys, which are
  direction-correct in practice (left swipe = `LEFT`-dominant, right swipe
  = `RIGHT`-dominant) but always arrive as pairs (`LEFT+UP` /
  `RIGHT+DOWN`) with stray extras (`ENTER`/`BACK`/`F13` seen); the app's
  LEFT/UP→older, RIGHT/DOWN→newer mapping absorbs this safely (both keys in
  a pair map to the same direction, so direction is never wrong, only the
  scroll amount varies with the number of stray keys).

Platform integration notes (2026-08-05):

- **A Bluetooth keyboard connect/disconnect changes Android's
  `Configuration`** (keyboard/navigation) and relaunches the Activity
  unless `configChanges` covers it. The manifest must declare
  `keyboard|keyboardHidden|navigation|orientation|screenSize`; missing
  `navigation` caused a relaunch (SSH dropped, state lost) on this firmware.
- The `INPUT_DEVICE_CHANGED` broadcast is not delivered on this firmware;
  the keyboard indicator falls back to a 1 s poll of the InputDevice list
  (plus key-event-driven checks).

Firmware constraints discovered during profiling (2026-08-05):

- **The glasses connect to only one Bluetooth peripheral at a time.**
  Pairing a second device unbonds the first (bond removal + rebond);
  "simultaneous glasses + ring + keyboard" coexistence in the design
  contract is therefore not achievable on this firmware with one glasses
  unit. Controller switching means re-pairing, which costs the current
  session state only at the OS level, not app state.
- After the HID link is lost, the connection manager may keep retrying
  while `HidHostService.mTargetDevice` stays null and no HID reports flow;
  a Bluetooth service restart (`svc bluetooth disable/enable`) plus
  power-cycling the peripheral re-establishes the data channel. A
  `connectState=true` in the Rokid CXR peripheral list only means the BLE
  link is up — it does **not** mean HID reports are flowing.
- The first key press after a period of inactivity only wakes the
  peripheral; the second press delivers data.

### INMO Ring 0762 (Ring4) verified mapping (2026-08-05)

Profiled on the current glasses over BLE HID (`/dev/input/event2`,
vendor 05ac, product 0220). Two input surfaces: a touchpad (click /
double-click / left-right swipe / long-press) and a GO button (click /
double-click / long-press).

| Physical action | Kernel EV_KEY | Notes |
|---|---|---|
| Touchpad single click | `KEY_ENTER` (0x28) | Same as TP primary |
| Touchpad double click | `KEY_BACKSPACE` (0x2A) | |
| Touchpad swipe left | `KEY_RIGHT` (0x4F) | **Direction inverted** (left swipe reports RIGHT) |
| Touchpad swipe right | `KEY_LEFT` (0x50) | Direction inverted |
| Touchpad long press | `KEY_HOME` (0x4A) | |
| GO single click | `KEY_F8` (0x41) | Short press |
| GO double click | `KEY_F8` ×2 | ~90 ms gap measured |
| GO long press | `KEY_F8` | **Hold duration IS reported** (DOWN→UP ~2.4-3.8 s measured) — unlike the COIDEA knob's fixed short press, so hold detection works; use a >800 ms hold threshold and the same 500 ms double-press window |

All GO actions share `KEY_F8`; distinguish by down-to-up duration and
press interval. Direction-inverted swipes must be normalized in the mapper
(left gesture → semantic left).

### Bluetooth mini-keyboard requirements

The expected physical labels are Up, Down, Left, Right, Delete/Backspace,
`/`, and possibly Record/Microphone. Their Android events are **unverified
until the actual keyboard is paired and profiled**.

- Arrow labels will probably emit DPAD or keyboard arrow keycodes, but the
  mapper must use observed events rather than assumptions.
- Delete must be tested for both `KEYCODE_DEL` (Backspace) and
  `KEYCODE_FORWARD_DEL`.
- `/` should be derived from `event.unicodeChar` or `event.characters` where
  possible so keyboard layout and modifiers remain correct. Do not
  hard-code only one physical scan code for `/`.
- The possible recording key may emit a media key, headset-hook key,
  voice-assist key, programmable HID key, vendor-specific scan code, or no
  application-visible event at all. Capture it before assigning behavior.
- Different keyboards may emit different keycodes for the same printed
  label. Source-specific mappings may exist inside `PhysicalInputMapper`,
  while their outputs remain common semantic actions.

### Provisional mini-keyboard navigation model (not implemented)

The future mini-keyboard deliberately gets a different directional layout
from the standalone glasses touch pad; physical directions must be mapped
using the input-device source, not only the Android keycode:

- **Standalone glasses, terminal idle:** touch-pad left/right swipe
  continues to browse older/newer terminal output (the glasses expose no
  four directional buttons).
- **Mini-keyboard Up/Down:** browse older/newer terminal output — the
  keyboard's primary scrollback control.
- **Mini-keyboard Left/Right:** browse previous/next local composer input
  history rather than scrolling terminal output. "Input history" means
  previously submitted local composer drafts; the exact
  empty-draft/boundary behavior must be designed before implementation.
- Cursor movement must remain available to the standalone-glasses composer.
  Whether the mini-keyboard needs an alternate cursor-movement chord or
  context-sensitive fallback will be decided after the real keyboard is
  profiled.
- A proposed **double-Left** shortcut could leave the current terminal and
  open session selection, but it is explicitly deferred. A single-session
  workflow is acceptable for the first keyboard version.

This is a source-aware semantic mapping, not a global remapping of
`KEYCODE_DPAD_*`. It must not change the currently implemented pure-glasses
left/right history browsing. The keyboard does not exist in the test setup
yet, so all HID codes, repeat behavior, double-press timing, and ergonomics
remain unverified.

## Event capture and diagnostics

Before mapping a new control, the debug build must capture enough
information to identify it without logging terminal content or secrets:

- event phase: key down, key up, or key multiple
- event time and down time
- `deviceId`
- input source bitmask
- device name, descriptor, vendor ID, and product ID when Android exposes
  them
- keycode and symbolic key name
- scan code
- repeat count
- meta/modifier state
- Unicode code point and `event.characters`
- flags
- current interaction state
- whether the event was mapped, ignored, or consumed
- resulting semantic action, if any

Diagnostics must remain bounded and development-only. They must not log SSH
credentials, private keys, API tokens, arbitrary terminal body text, speech
transcripts, or source code. A small explicit printable-key probe may display
its decoded character locally during hardware profiling, but persistent logs
should avoid capturing normal typed content.

## Context-aware interpretation

At minimum, design and test these states:

- target picker
- connecting
- connected terminal idle
- connected terminal busy/output streaming
- input composer listening/editing
- command palette
- connection error/disconnected

Illustrative behavior matrix:

| Semantic action | Terminal idle/busy | Input composer | Command palette | Error/disconnected |
|---|---|---|---|---|
| Navigate Left/Right | Left = older local history; Right = newer local history. Send VT arrows only in a future explicit raw-terminal context | Move local cursor | Change selection only if designed | No PTY send |
| Navigate Up/Down | Up = older local history; Down = newer local history. Send VT arrows only in a future explicit raw-terminal context | Local edit/shortcut behavior only if assigned | Move palette selection | No PTY send |
| Confirm/single click | Open composer | Start/toggle listening or reserved; never delete and never accidentally send | Select command | Reconnect |
| Long press send | No-op or open composer only if explicitly designed | Finalize and send non-empty draft | No accidental send | No PTY send |
| Double-click/cancel | No-op or local navigation | Discard unsent draft | Close palette, retain draft | Return to picker |
| Shutter/Delete previous | Terminal Backspace only in raw-terminal context | Shutter `KEYCODE_CAMERA` or keyboard `KEYCODE_DEL` deletes previous grapheme | Optional/no-op | No PTY send |
| Insert text | Raw PTY only in explicit raw-terminal context | Insert into local draft | Search only if designed | No PTY send |
| Start/stop recording | Open composer/start listening | Toggle/pause listening | No-op | No PTY send |
| Open command palette | Open composer at command prefix | Open local palette | No-op | No PTY send |
| Terminal Escape/interrupt | Explicit separate action | Must not bypass local cancellation policy | Close palette first | No PTY send |

The final mapping may refine this table after hardware profiling, but local
composer and palette actions must never leak into the PTY.

## Key phase, repeat, and duplicate handling

- Define one canonical phase for one-shot actions, normally the first
  key-down event where `repeatCount == 0`.
- Do not trigger the same action on both key down and key up.
- Directional navigation, terminal-history scrolling, and cursor movement
  may support repeat, but repeat delay/rate and UI behavior must be tested
  deliberately.
- Long press must not silently become many confirmation, recording,
  reconnect, or send operations.
- Long-press/double-click recognition must suppress the underlying
  single-click action.
- `ACTION_MULTIPLE`/`event.characters` must remain available for Bluetooth
  keyboards and input methods that deliver text in batches.
- If the glasses firmware or a Bluetooth controller emits more than one
  keycode for one gesture, add a deduplication rule based on captured timing
  evidence rather than globally discarding nearby events.
- Deduplication and repeat tracking must be scoped by physical device
  identity plus key/gesture identity. Never discard an action from the ring
  merely because the glasses or keyboard produced a similar action nearby.
- Events from simultaneously connected controllers are processed in Android
  delivery order through the same interaction controller. A device
  disconnect must not reset the composer or disable the remaining
  controllers.
- If the ring and keyboard emit the same Android keycode but require
  different meanings, `PhysicalInputMapper` must select a verified per-device
  profile using descriptor/vendor/product/source metadata rather than a
  global keycode guess.
- Modifier handling must preserve expected terminal shortcuts when later
  supported; do not drop Ctrl, Alt, Shift, or Meta information at the
  raw-event boundary.

## Terminal and input safety

- Keyboard text and control sequences may reach Claude's PTY only while the
  terminal is connected and the chosen interaction state permits remote
  input.
- Local target-selection, composer, palette, or reconnect input must never
  accidentally leak into the PTY.
- `TerminalEnter` must remain independently available even if glasses
  confirmation is also used to open the composer.
- Speech-specific safety rules live in `rules/voice.md`; the always-loaded
  invariants are in `CLAUDE.md`.

## Hardware test matrix

Profile and test the real glasses, Bluetooth ring, and paired mini-keyboard,
first independently, then in every pair, and finally with all three control
surfaces connected in the same session. Test the selected glasses/external
microphone separately from controller coexistence.

### Per-control capture

For every glasses gesture/button, ring control, and keyboard key:

1. Capture key down and key up.
2. Capture short press, long press, double press, and repeat behavior.
3. Record keycode, scan code, Unicode result, modifiers, source, and device
   identity.
4. Verify whether Android delivers the event to the foreground Activity or a
   system component consumes it.
5. Confirm the resulting semantic action in terminal, composer, and palette
   states.
6. Confirm whether PTY bytes were sent, and exactly which bytes/control
   sequence class was selected.
7. Confirm the remote terminal redraw is decoded and rendered correctly.

### Controls to profile

- TP single click, double click, long press, slow swipe, and fast swipe
- Back
- Shutter/Capture and Function keys, if present
- Ring buttons, swipes/gestures, click/double-click/hold behavior, and any
  mode switch it exposes
- Keyboard Up, Down, Left, Right
- Keyboard Delete/Backspace
- Keyboard `/` unshifted and with relevant modifiers
- Recording/microphone key, if present
- DJI Mic 2 buttons and audio-route behavior, if connected
- Any additional physical key discovered on the device

### Composer and coexistence scenarios

- Open, dictate, move cursor, dictate again, delete, and send a
  Chinese/English mixed draft.
- Edit around emoji, combining marks, and ZWJ sequences without corrupting
  text.
- Double-click cancel while listening and while an ASR partial result is
  visible.
- Verify long press and double-click never also trigger single-click.
- Browse older/newer terminal history with the composer closed; verify
  clamping, hidden historical cursor, and that no PTY arrow bytes are sent.
- While viewing history, receive enough remote output to scroll; verify the
  viewed content remains stable where retained and the footer shows
  `NEW OUTPUT` without snapping live.
- Open the composer from history and verify the viewport returns to the live
  bottom before the overlay appears.
- Exercise primary-buffer, partial-scroll-region, and alternate-screen
  output and confirm the documented 1000-row capture boundary and
  exclusions.
- Continue rendering remote output while the composer is open.
- Open and edit a new composer while Claude is busy.
- Send one and multiple follow-up prompts during a long Claude task.
- Open/cancel the local slash palette without changing remote PTY text.
- Insert a literal `/` in a path and invoke `/command` at command-prefix
  position.
- Navigate/select with glasses, then edit/send with the ring or keyboard,
  and exercise every reverse direction.
- Interleave glasses, ring, and keyboard actions in one draft and verify one
  shared cursor/buffer with deterministic action ordering.
- Press equivalent controls on two different devices close together and
  verify that cross-device deduplication does not drop either intentional
  action.
- Disconnect/reconnect one controller while a local draft exists; retain the
  draft and keep the remaining controllers operational.
- Re-pair/reconnect the ring and keyboard independently during an active
  SSH/tmux session.
- Switch between the glasses microphone and a verified external microphone
  without changing any physical-control mappings or controller state.
- Verify that removing the ring or keyboard does not reduce the standalone
  glasses path.

## Terminal trace correlation

Input tests should be correlated across these boundaries without exposing
terminal body content in logcat:

```text
raw physical event
-> semantic action
-> local state transition or PTY command type
-> SSH write timestamp/length/control-sequence category
-> app-private terminal trace
-> TerminalFrame revision
-> screenshot or direct glasses observation
```

This correlation is required because a defect can originate in physical
mapping, gesture timing, ASR hypothesis replacement, grapheme editing,
duplicate dispatch, PTY encoding, Claude queue behavior, remote TUI
behavior, UTF-8 decoding, VT parsing, viewport sizing, or Canvas rendering.

## Implementation sequence

1. Add a bounded debug input-event inspector and profile all glasses
   controls plus the real Bluetooth ring and mini-keyboard.
2. Introduce `RawInputEvent`, `TerminalAction`, and `PhysicalInputMapper`
   with JVM tests.
3. Move state-dependent behavior into an `InteractionController` and keep
   `MainActivity` as an adapter.
4. Implement `InputComposerState`, grapheme-aware editing, overlay
   rendering, and gesture arbitration without ASR first.
5. Add a dedicated PTY input encoder and tests for arrows, Enter, Backspace,
   forward delete, Escape, printable Unicode, and modifiers.
6. Verify sending follow-up input during a real busy Claude session before
   labeling it queued.
7. Implement the local slash-command palette with a small configurable
   command list.
8. Add the verified Bluetooth-ring and mini-keyboard mappings without
   introducing separate controller modes.
9. Integrate the chosen recording/ASR path, including replaceable partial
   hypotheses and visible listening state, independently from controller
   selection.
10. Profile DJI Mic 2 audio routing/controls if it becomes part of the
    supported setup.
11. Run the coexistence, queue, ASR-editing, and terminal-trace test
    matrices on hardware.

## Acceptance criteria

This requirement is satisfied when:

- The app remains fully operable through the designed standalone glasses
  controls.
- TP single-click opens a local composer and never deletes; long press
  sends; double-click/Android Back safely discards an unsent draft; the
  verified physical Shutter/Capture event deletes the previous grapheme.
- Voice results can be inserted, revised, cursor-positioned, and deleted
  without corrupting Unicode text.
- The Bluetooth ring and mini-keyboard can be paired and used in the same
  session without switching modes.
- Glasses, ring, and keyboard can all remain connected; actions from one do
  not disable, reset, or globally suppress actions from the others.
- All control sources produce the same semantic actions where their intent
  is equivalent, while verified per-device mappings handle real HID
  differences.
- The selected glasses/external microphone remains independent from which
  physical controller is used.
- Terminal Enter, confirmation, speech activation, deletion, `/`, and
  cancellation are unambiguous in every supported state.
- The command palette can be cancelled without mutating remote PTY input.
- Remote output continues while a local draft is edited.
- With no composer open, left/up and right/down browse bounded local
  terminal history without sending PTY arrows; new output is indicated
  without forcing the viewport live.
- Busy-session follow-up input behavior is verified against the actual
  Claude version before being described as queued.
- Unknown or vendor-specific keys can be diagnosed without changing
  terminal-rendering code.
- No local composer or palette event is accidentally sent to the remote PTY.
- Speech text cannot be transmitted without explicit send confirmation.
- Raw-event, action, PTY-write, terminal-frame, and visual results can be
  correlated during development testing.
