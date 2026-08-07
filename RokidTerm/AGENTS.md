# RokidTerminal Project Guidance

## Current status

RokidTerminal is an Android APK for Rokid Glass that connects to remote development servers over SSH, attaches to a named `tmux` session, and runs Claude Code through a server-side credential wrapper.

Verified on the current glasses and Tencent Cloud server:

- SSH public-key login to the restricted `rokid` user works over public Wi-Fi.
- The server ED25519 host key is pinned and verified with Bouncy Castle on Android.
- `cloud-claude` survives SSH disconnects through `tmux`.
- The remote launcher is `/home/rokid/bin/rokid-claude`; it uses the `rokid` user's independent DeepSeek credential. New Claude processes intentionally start with `--dangerously-skip-permissions` at the user's request.
- New Claude processes start with `--effort max`. The app creates a missing tmux session in detached mode, but an existing session is attached without relaunching Claude and therefore keeps its current effort until `/effort max` is run or the session is restarted. The wrapper must pass through CLI arguments.
- The display uses a dynamically sized VT screen grid rather than an appended log tail. The current 480x640 view resolves to 46 columns x 30 rows.
- A first local input composer is implemented with Unicode-grapheme editing, local cursor movement, keyboard insertion, Backspace deletion, explicit send/cancel gestures, and a live-terminal overlay. Native Android `SpeechRecognizer` is not available on this firmware — voice dictation goes through the SERVER-side SenseVoice path instead (record → transcribe via `asr-fwd` → draft → send, hardware-verified 2026-08-05; see `rules/voice.md`). The standalone-glasses overlay is compact (about five wrapped draft lines at 480x640), shows a proportional vertical scrollbar for longer drafts, and scrolls only as needed to keep the grapheme-aware cursor visible.
- Idle terminal directions now browse bounded local scrollback: left/up = older, right/down = newer. They no longer emit PTY arrow sequences. This path is software-tested but not yet touch-pad-verified on hardware.

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

## Network and session model

- The glasses connect directly to the public server. They do not depend on the Mac after provisioning.
- Do not introduce a cloud-to-Mac tunnel, VPN, reverse SSH route, or Mac endpoint without an explicit architecture change from the user.
- Wi-Fi connectivity is managed by Rokid system components, not this app. The app has no `CHANGE_WIFI_STATE` permission.
- `DREAMS_CAST` may temporarily lose its Android default route while the companion/CXR service rebuilds Wi-Fi. Treat `ENETUNREACH` as a network state, not an SSH key failure.
- Display `CONNECTING`, `CONNECTED`, `DISCONNECTED`, and sanitized `ERROR` states. Error-state center/confirm should retry the active endpoint.
- Each endpoint owns a fixed tmux session name. The remote command first creates a missing session in detached mode with the fixed Claude launcher and startup flags, then applies a 46-column-friendly compact status configuration and attaches:

```bash
(tmux has-session -t <session> 2>/dev/null || tmux new-session -d -s <session> -c <workspace> /home/rokid/bin/rokid-claude --effort max --dangerously-skip-permissions) && tmux set-option ... && exec tmux attach-session -t <session>
```

- Reconnecting attaches to the existing session without restarting Claude. Status options are refreshed on every connection so inherited server defaults cannot truncate the session name or emit tmux scroll markers such as `<lau>`. An APK reinstall disconnects SSH but must not kill the server tmux session.
- tmux sessions belong to the `rokid` Linux user. Inspect them from an admin shell with `sudo -iu rokid tmux ls`, not plain `tmux ls` as `ubuntu`.

## Hardware input facts

Do not rely only on the generic Rokid key table. This firmware has produced these events in real logs:

- TP confirm/single action: `KEYCODE_ENTER` (66).
- A separate touch action has produced `KEYCODE_NOTIFICATION` (83) and was unhandled.
- Back: `KEYCODE_BACK` (4).
- ADB-injected keys are intermittent unless the display is awake and the Activity is actually resumed.

Current behavior:

- Endpoint list: swipe/direction chooses, Enter/center connects, Back exits.
- Terminal: Back cancels a draft; with no draft it disconnects and returns to endpoints.
- Error/disconnected terminal: center/confirm retries.
- With no composer open, left/up directions browse three rows toward older local terminal history and right/down browse toward newer history. They are clamped locally and do not emit PTY arrow sequences. Opening the composer returns to the live bottom.
- Explicit remote arrow forwarding remains a future raw-terminal/full-keyboard context; do not restore it as the default idle behavior. Enter forwarding and future speech confirmation still need an interaction redesign because the physical confirm action is also `KEYCODE_ENTER`.
- Do not claim that speech works until the full record/transcribe/draft/confirm path is verified on hardware.

## Unified input architecture requirement

The next interaction phase must separate speech/audio input from physical control input. Speech may use the glasses microphone or a verified external microphone such as DJI Mic 2. Physical controls may come simultaneously from the glasses, a Bluetooth ring, and an optional Bluetooth mini-keyboard through one semantic input layer. The expected keyboard has approximately Up, Down, Left, Right, Delete/Backspace, `/`, and possibly a recording key; the ring and keyboard mappings are requirements to profile, not verified Android keycodes.

- Normalize raw Rokid gestures/buttons, Bluetooth-ring events, and keyboard `KeyEvent`s through `PhysicalInputMapper` into source-independent `TerminalAction`s before UI-state handling or PTY encoding.
- Do not add global “glasses,” “ring,” or “keyboard” modes and do not select an exclusive active controller. All three may stay connected and alternate within one session.
- Keep microphone/audio-source selection independent from the physical controller producing actions.
- Preserve diagnostic metadata (`deviceId`, descriptor/name, vendor/product, source, keycode, scan code, modifiers, repeat count, Unicode/characters, and event phase) in development builds so each real control can be profiled and per-device mappings can be verified.
- Scope repeat/deduplication state to physical device identity plus key/gesture identity. Never drop a ring action because a glasses or keyboard action occurred nearby; controller disconnects must not clear the draft or disable remaining controllers.
- Treat local terminal-history older/newer/live actions, `Confirm`, `TerminalEnter`, composer open/send/discard, `Cancel`, `TerminalEscape`, Backspace, forward delete, text insertion, command-palette access, and recording control as distinct semantic actions.
- Prefer Unicode/character decoding for `/`; distinguish `KEYCODE_DEL` from `KEYCODE_FORWARD_DEL`. The desired standalone delete control is Shutter/Capture: standard `KEYCODE_CAMERA` is provisionally supported, but vendor keycodes, Function, microphone, and DJI Mic 2 controls must not be assigned until their real events are captured.
- Interpret actions according to interaction state. Only a connected state that explicitly permits remote input may produce PTY bytes.
- Move device-specific mapping, gesture timing, draft ownership, and state decisions out of `MainActivity` as the redesign proceeds.

### Planned local input composer

The standalone-glasses path uses a local overlay independent from the remote terminal editor:

- A verified single click opens the composer and starts the selected voice-listening path when available.
- Left/right gestures move a grapheme-aware local cursor. The right-side Shutter/Capture button is the desired standalone delete control; standard `KEYCODE_CAMERA` provisionally removes the previous grapheme cluster, while Bluetooth `KEYCODE_DEL` remains the fallback. The physical Shutter event is still unverified.
- ASR partial hypotheses replace one active span instead of being repeatedly appended. Later utterances insert at the current cursor.
- Remote `TerminalFrame` updates continue behind the overlay. Closing it reveals the latest frame.
- Show compact listening/editing hints below the draft.
- Long press sends a non-empty finalized draft once. Double-click or Back discards only the current unsent draft. Long/double presses must suppress the underlying single click.
- The first Android `SpeechRecognizer` adapter is implemented, including Android 11+ `RecognitionService` package visibility and explicit-provider fallback. A previous build reported zero services; the new APK still requires device verification before declaring this firmware supported. AIUI/LocalSkill arbitrary dictation or authenticated server-side ASR remains the fallback if the provider probe is still empty.

### Slash commands and busy-session follow-ups

- Prefer a local `OpenCommandPalette` for glasses. Selecting an entry inserts `/command` into the composer, so cancellation never mutates the remote PTY. A small configurable list may later use structured server-assisted discovery; do not scrape terminal pixels for commands.
- In command-prefix context, the mini-keyboard `/` may open the palette; elsewhere it must remain insertable as a literal character. Bind a glasses-only palette gesture only after real input profiling.
- Keep the composer usable while Claude is processing. Continue remote rendering and allow a finalized follow-up draft to be written to the same PTY.
- Treat Claude's type-ahead/queue behavior as version-dependent until verified against the actual server Claude installation. Do not claim a queue count or guaranteed ordering from visual inference.
- Double-click can retract only an unsent local draft. Once text plus Enter has reached the PTY, cancellation/interrupt is a separate explicit action.
- Do not build an app FIFO that flushes by matching prompts or spinners. A future retractable/reorderable local queue requires an explicit structured server busy/ready/accepted signal.

The complete design contract (semantic action model, mappings, diagnostics, context matrix, hardware test matrix) is in `rules/input.md`; the composer interaction design, slash-command alternatives, and busy-session queue verification plan are in `rules/composer.md`. Keep the rules synchronized when input behavior changes.

## Terminal rendering

### Terminal data pipeline

Keep remote-output processing and Canvas rendering as separate layers:

```text
SSH PTY bytes
-> continuous UTF-8 decoding in SshTerminalSession
-> TerminalOutputProcessor
-> TerminalScreen VT/ANSI state machine
-> immutable TerminalFrame
-> TerminalView Canvas rendering
```

- `SshTerminalSession` owns transport and decoding only; it must not parse terminal semantics or touch Android Views.
- `TerminalOutputProcessor` owns the mutable terminal emulator and publishes immutable frame snapshots. It has no Android dependency and should be covered by JVM tests.
- `TerminalView` is a renderer only. It must not own or mutate `TerminalScreen`.
- Keep PTY/grid/display geometry in `TerminalSpec`/`TerminalViewport` so transport and rendering cannot silently diverge. The character grid is derived from the actual Android View size, not a fixed Canvas scale.
- The processor-to-view boundary uses latest-frame coalescing: remote reads may generate frames quickly, but the UI queue must contain at most one pending drain runnable and retain the highest revision. Further dirty-row optimization belongs at this same boundary.


- Target is 480x640 portrait, green monochrome on black.
- Keep all drawing inside the 480x640 Canvas. Keep `defaultFocusHighlightEnabled=false`, while retaining focus for hardware keys.
- SSH PTY and local screen must always use the same `TerminalViewport`. At 480x640 the result is 46 columns x 30 rows, but other View sizes produce a different grid automatically.
- Use continuous UTF-8 decoding across network reads. Never decode each SSH byte packet independently; packets may split a Chinese character.
- Chinese and other wide characters consume two terminal cells.
- View resize order is: Android View size -> `TerminalViewport` -> local `TerminalOutputProcessor.resize` -> `ChannelShell.setPtySize` -> remote SIGWINCH -> tmux/Claude redraw. Do not implement client-side reflow for a full-screen TUI; preserve the rectangular grid until the remote application redraws.
- Unicode rendering must account for supplementary code points, combining marks, ZWJ sequences, and wide-cell continuation cleanup after edits or clipping.
- `TerminalScreen` implements the subset of VT/ANSI used by tmux and Claude: cursor movement, save/restore, erase display/line/characters, insert/delete characters and lines, scrolling, and delayed autowrap.
- Treat recurring stray or missing characters as missing terminal semantics, not as font corruption. Add a focused regression test before extending the parser.
- Ordinary printable characters such as `<`, `>`, and `B` must never be filtered as a display workaround. Preserve the cross-network-chunk `ESC(B` regression behavior in the VT parser.
- `TerminalView` must render ordinary printable characters such as `<` and `>` unchanged on every row, including the tmux status row. Fix malformed status text at the tmux configuration or VT parsing layer rather than filtering glyphs in the renderer.
- The monochrome renderer ignores RGB color selection but preserves meaningful text attributes: bold, dim, underline, and inverse.
- The idle footer is one compact line. At live bottom it advertises history/input actions; in history it shows the row offset and adds `NEW OUTPUT` when remote output arrives. The local composer temporarily overlays the terminal and replaces the footer hint while it is open.
- Local scrollback is initially bounded to 1000 rows and captures only full-screen scrolling on the primary buffer. Partial scroll regions and alternate-screen scrolling are excluded; tmux/Claude alternate-screen redraws may therefore yield incomplete or no local history. Reset, resize, and CSI `3J` clear history.
- Historical frames hide the cursor. New output must not snap a historical viewport live; preserve the viewed position as full-screen rows arrive, including across bounded-history eviction. Returning to live clears the `NEW OUTPUT` indicator.

## Local input composer status

- `MainActivity` currently owns the incremental `TERMINAL`/`COMPOSER` state and gesture timing. This is acceptable for the first slice, but future device-specific mapping and interaction complexity should move into the `PhysicalInputMapper`/`InteractionController` architecture described in `rules/input.md`.
- A connected-terminal primary single click returns local history to the live bottom and opens the composer after the double-click window. In the composer, primary single toggles speech if available and never deletes, primary long sends, and primary double discards. `KEYCODE_TV` is accepted as a legacy long-press send event. Shutter deletion is immediate and does not share the primary click/double-click arbitration window.
- **Back, Shutter, and keyboard Backspace are distinct.** Back means Android `KEYCODE_BACK` (4), not the right-side photo button; it discards the entire unsent composer draft and closes the overlay. Standard Shutter `KEYCODE_CAMERA` provisionally deletes the grapheme before the cursor, while Bluetooth `KEYCODE_DEL` remains Backspace. Keep `KEYCODE_FORWARD_DEL` distinct when implemented.
- With the composer closed, left/up scrolls local history older and right/down scrolls newer; none of these sends PTY arrows. Left/right in the composer moves by grapheme. Up/down is consumed and reserved for the future command palette so it cannot leak into the PTY. Left/right never directly pans the composer viewport; wrapped-draft scrolling is an automatic minimal adjustment only when the cursor would leave the compact visible range.
- Printable Bluetooth keyboard input inserts at the local cursor. When the composer is closed, printable input continues to go directly to the connected PTY.
- Sending uses `SshTerminalSession.sendText`, which appends carriage return. Empty drafts and drafts while disconnected are never sent; disconnected drafts remain visible for retry/editing.
- Remote terminal frames continue to update while the composer overlay is open. `TerminalView` remains renderer-only; `InputComposerState` owns the local unsent buffer and cursor.
- The app provisionally accepts standard `KEYCODE_CAMERA` as Shutter delete and consumes `KEYCODE_FOCUS` without deletion. Do not claim the physical Rokid Shutter mapping is verified, and do not assign `KEYCODE_NOTIFICATION`, Function, microphone, or other vendor controls until their actual events are profiled.
- Single/long/double gesture behavior is implemented but not yet hardware-verified. Do not describe it as hardware-confirmed until install-and-observe testing is complete.
- Never log draft text, partial/final speech recognition text, terminal body text, or source code to logcat.

## Voice status and next direction

- The first local Android `SpeechRecognizer` path is implemented. It declares Android 11+ visibility for `android.speech.RecognitionService`, prefers the configured secure-settings provider, falls back to the first exported provider, and requests `zh-CN` free-form partial results.
- `SpeechDraftState` anchors each recognition round at the current grapheme-aware cursor. Partial results replace one active span, final results commit it as ordinary editable draft text, and neither partial nor final text reaches the PTY until the user explicitly long-presses Send.
- Manual cursor movement, deletion, or keyboard insertion cancels the current recognition round and keeps the latest visible hypothesis as editable text. This conservative first slice prevents late callbacks from duplicating or corrupting the draft; single-click starts another round.
- A previous APK reported zero `RecognitionService` providers, but that probe occurred before the manifest package-visibility query and explicit-provider fallback were added. The new APK must be installed and probed on the glasses before claiming Android ASR support.
- If the new provider probe remains empty or the vendor service rejects arbitrary dictation, evaluate:
  - Native `AudioRecord` short capture, transfer over the authenticated channel, server-side ASR, draft confirmation.
  - Rokid AIUI/LocalSkill integration for system ASR, bridged to the SSH session.
- LocalSkill/Instruct SDKs are oriented toward wake-word skills and fixed commands; verify whether they can return arbitrary dictation before adopting them.
- Never send microphone audio to a new service without documenting transport, retention, credentials, and user confirmation. Never log partial/final transcript text.

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
