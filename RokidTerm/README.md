# RokidTerminal

RokidTerminal is a remote Claude Code client for Rokid Glass. It stores one or more remote development server targets and keeps an SSH shell attached to a named `tmux` session on the selected server. SSH, tmux resume, Claude Code through DeepSeek, and the compact terminal display are working on hardware. A first local input-composer implementation is now present; native voice recognition is still unavailable on the current firmware.

## Verified hardware status

- Public-key SSH login to the Tencent Cloud server works as the unprivileged `rokid` user.
- The app pins the independently verified server ED25519 host key.
- Reconnecting resumes the `cloud-claude` tmux session.
- Claude starts through `/home/rokid/bin/rokid-claude`, using an independent `rokid` API credential.
- New Claude processes start with `--effort max --dangerously-skip-permissions`. This is an explicit choice for the dedicated, unprivileged `rokid` account; reconnecting to an existing tmux session does not relaunch Claude or reapply startup arguments.
- The terminal is rendered as a 46-column x 30-row VT-style screen with wide-character handling.
- `FLAG_KEEP_SCREEN_ON` keeps the display awake while the app is foregrounded. `FLAG_SECURE` is intentionally disabled during active development so screenshots and terminal-stream debugging work in every build; re-enable it before production release.
- Android `SpeechRecognizer` is unavailable on this Rokid firmware (`android.speech.RecognitionService` returns no services), so the current speech UI is not functional.

## Current first-stage interaction

| Input/state | Behavior |
|---|---|
| TP swipe on target list | Select a saved development server |
| TP click on target list | Connect to the selected target |
| TP click in a connected terminal | Return to the live bottom and open the local composer after the double-click arbitration window |
| TP left/up direction while the composer is closed | Browse three rows toward older local terminal history |
| TP right/down direction while the composer is closed | Browse three rows toward newer history, clamped at the live bottom |
| TP click while composer is open | Start/toggle speech when a recognizer exists; it never deletes text. Current firmware shows `VOICE UNAVAILABLE / USE KEYBOARD` |
| TP left/right while composer is open | Move the local cursor by a Unicode grapheme |
| Right-side Shutter/Capture while composer is open | Delete the grapheme before the local cursor. Standard Android `KEYCODE_CAMERA` is supported provisionally; the physical button event still requires device verification |
| Bluetooth printable key while composer is open | Insert text at the local cursor |
| Bluetooth `KEYCODE_DEL` while composer is open | Delete the grapheme before the local cursor |
| Primary long press, or legacy `KEYCODE_TV`, in composer | Send the non-empty draft to Claude with Enter |
| Primary double click in composer | Discard the unsent draft and close the composer |
| Android Back (`KEYCODE_BACK`) in composer | Fallback: discard the entire unsent draft and close the composer; this is not the Shutter/Capture button |
| Back in terminal | Disconnect and return to the target list |
| Click while an error is shown | Retry the active endpoint |

**Back, Shutter, and keyboard Backspace are three distinct inputs.** In this project, Back means Android `KEYCODE_BACK` (4), not the right-side photo/Shutter button; while composing it is only a fallback that cancels the whole unsent draft. The desired glasses-only delete control is the right-side Shutter/Capture button. The app now handles the standard Android full-shutter event `KEYCODE_CAMERA` as immediate grapheme-aware deletion and consumes `KEYCODE_FOCUS` without deleting twice, but the exact event emitted by this glasses model and firmware is not yet hardware-verified. Bluetooth Backspace remains `KEYCODE_DEL`. TP single-click starts/toggles listening or remains reserved and never deletes, so it does not compete with TP double-click cancellation. `KEYCODE_NOTIFICATION` remains unassigned.

The local composer is an opaque overlay above the still-live terminal. Opening it first returns the terminal viewport to the live bottom. Remote frames continue to be decoded and rendered while the draft is edited. Its editor handles Chinese text, surrogate-pair emoji, combining marks, emoji modifiers, regional-indicator flags, and ZWJ sequences without placing the cursor inside or deleting half of a grapheme. Draft text is sent only after an explicit long-press send action. If SSH is disconnected, the draft remains local and is not sent.

When the composer is closed, left/up and right/down directional events browse local terminal history instead of emitting remote PTY arrow sequences. The viewport is clamped between the oldest retained row and the live bottom. New remote output does not force a historical viewport back to live; the footer adds `NEW OUTPUT`, and the historical cursor remains hidden until the user reaches live again or opens the composer. The initial history limit is 1000 rows captured only from full-screen scrolling of the primary terminal buffer. Reset, viewport resize, and CSI `3J` clear it. Partial scroll regions and alternate-screen output are deliberately excluded, so tmux/Claude alternate-screen redraws may provide incomplete or no local history. The exact touch-pad direction and fast-swipe ergonomics still require verification on the glasses.

The TP single/long/double gesture mapping is implemented with Android timing thresholds but has not yet been fully hardware-verified on the current firmware. In particular, the device may represent long press as a held `KEYCODE_ENTER` or as `KEYCODE_TV`; both paths are accepted in the composer. Shutter deletion does not use the TP click/double-click arbitration window: a received `KEYCODE_CAMERA` deletes immediately on its first key-down. The real Bluetooth ring, mini-keyboard, and physical Shutter/Capture, Function, recording, and DJI Mic 2 controls still require raw-event profiling.

While RokidTerminal is in the foreground, its window requests that the display remain awake. Leaving or closing the app returns screen timeout control to the Rokid system. The display uses a compact 46x30 terminal grid for the 480x640 panel. It parses the common cursor, erase, insert/delete, scroll, and delayed-wrap sequences used by tmux and Claude Code. Colors are reduced for the monochrome display while meaningful text attributes remain represented. Remote PTY arrow forwarding remains available in the session layer for a future explicit raw-terminal interaction context, but idle directional input currently belongs to local history browsing.

## Remaining unified input work

The current composer is the first implementation slice. The completed design separates speech/audio input from physical control input. Speech may use the glasses microphone or a verified external microphone such as DJI Mic 2. Controls may come from the glasses, a Bluetooth ring, or an optional Bluetooth mini-keyboard. All three control surfaces may remain connected and alternate within the same session without switching modes or selecting an exclusive active controller. The keyboard is expected to have approximately Up, Down, Left, Right, Delete/Backspace, `/`, and possibly a recording key; the ring's actual controls are still unknown. Their Android keycodes and HID behavior must be captured before assignment.

Glasses, ring, and keyboard events will map into shared semantic actions before context-dependent behavior or terminal encoding. Device identity remains available so verified per-device HID differences can be handled without global keycode guesses. Repeat and deduplication state must be scoped per physical device, so an action from one controller cannot suppress a nearby intentional action from another. This keeps selection/confirmation separate from terminal Enter, distinguishes Backspace from forward delete, preserves printable characters and modifiers, and prevents local UI input from leaking into the remote PTY. Every real control will first be profiled with raw key-event diagnostics, including ring controls, Shutter/Capture, Function, microphone, and future DJI Mic 2 controls.

The basic glasses-only composer flow is implemented: TP single-click opens it and toggles recording, left/right moves a Unicode-grapheme cursor, Shutter deletes the preceding grapheme immediately, long press sends, and double-click or Android Back discards the unsent draft. TP single-click never deletes. Bluetooth `KEYCODE_DEL` remains a second Backspace path. Remote terminal frames continue updating behind the overlay, and compact interaction hints appear below the draft. On the current firmware, long-press and Shutter never arrive as KeyEvents — they are intercepted as ordered system broadcasts (`.docs/ROKID_INPUT_INTERACTIONS.md` §8) and were verified 2026-08-05: long-press sends and Shutter deletes without launching the system assistant or camera.

Claude `/` commands should first use a local command palette that inserts the selected `/command` into the draft. This is safer than immediately entering Claude's live slash menu because cancelling the local palette cannot leave hidden text in the PTY. The keyboard `/` can open the palette at command-prefix position while remaining a literal slash elsewhere.

The composer should remain usable while Claude is busy so another instruction can be prepared and sent to the same PTY. Claude's native type-ahead/queue behavior must be tested against the exact server version before the UI promises queue ordering or a queue count. Cancelling removes only an unsent local draft; input already written to the PTY cannot be safely retracted by the app. A future app-owned queue requires an explicit structured busy/ready signal rather than scraping Claude's terminal output.

See [`rules/input.md`](rules/input.md) for the input architecture, semantic action model, interaction-state matrix, and combined glasses/ring/keyboard hardware tests, and [`rules/composer.md`](rules/composer.md) for the composer lifecycle, ASR partial-result rules, slash-command design, and queue verification plan.

## Voice input status

Verified 2026-08-05: the glasses record 16 kHz mono PCM in memory (never to disk), stream it over the authenticated `asr-fwd` SSH forward to the server-side SenseVoice service, and insert the returned text into the composer draft at the cursor. The draft is sent to Claude only on explicit long-press confirmation. The ordinary Android `SpeechRecognizer` service is unavailable on this firmware.

A future option is Rokid AIUI or LocalSkill system ASR bridged into the SSH session. Do not treat arbitrary speech as a shell command. Speech must remain Claude input and must be confirmed before transmission.

## Build

```bash
chmod +x dev.sh gradlew
./dev.sh build
./dev.sh run
```

The project uses the same Gradle 8.2 wrapper as the sibling Rokid Android apps.

## Connection targets and device provisioning

Each server target has its own profile and dedicated 3072-bit RSA SSH identity. A profile contains a display name, host, port, restricted Linux user, trusted SSH host key, workspace, and tmux session name. Its private key is encrypted with an Android Keystore AES-GCM key and remains in app-private storage.

The MVP uses trusted ADB file provisioning. This avoids typing network addresses and long host keys on the glasses. The exported launcher does not accept connection settings through Intent extras. ADB places a JSON profile in app-private storage via `run-as`; the app imports it once and immediately deletes it. A later pairing screen can import the same profile format from a QR code.

Create a local JSON file for the public cloud target. This file contains no private key or Claude credential, but it should still stay outside Git:

```json
{
  "action": "upsert",
  "id": "cloud",
  "name": "Tencent Cloud",
  "host": "SERVER_IP_OR_DOMAIN",
  "port": 22,
  "user": "rokid",
  "knownHost": "SERVER ssh-ed25519 AAAA...",
  "workspace": "/srv/projects/myWorld",
  "sessionName": "cloud-claude"
}
```

Import it:

```bash
./dev.sh import-profile /absolute/path/to/cloud.json
```

Use the same JSON shape to add another remote development server, changing `id`, `name`, `host`, `knownHost`, `workspace`, and `sessionName`.

The app immediately generates that target's key. For a debug build, read only its public key with:

```bash
./dev.sh public-key cloud
```

Add each public key to the matching server's restricted `rokid` account at `~/.ssh/authorized_keys`. Never copy an administrator private key to the glasses.

`ssh_known_host` must be the complete `known_hosts` line obtained through an independently trusted channel, not a fingerprint accepted from the first network connection. Every target maintains its own host trust and device identity.

Delete a target and its device key by importing:

```json
{
  "action": "delete",
  "id": "old-server"
}
```

Deleting the local profile does not edit the remote account. Also remove that target's public key line from the matching `~/.ssh/authorized_keys` file so the deleted credential is fully revoked.

Profiles are stored in the app's private `SharedPreferences`. Reinstalling with `adb install -r` preserves them; uninstalling clears all profiles and device keys.

## Server prerequisites

Every target user needs `tmux`, `claude`, the validated `/home/rokid/bin/rokid-claude` credential wrapper, and access to its selected workspace. The remote command is equivalent to:

```bash
(tmux has-session -t rokid-claude 2>/dev/null || tmux new-session -d -s rokid-claude -c '/srv/projects/myWorld' /home/rokid/bin/rokid-claude --effort max --dangerously-skip-permissions) && tmux set-option ... && exec tmux attach-session -t rokid-claude
```

The app refreshes a compact tmux status layout on every connection so the 46-column display retains the full session name and does not show tmux truncation markers such as `<lau>`. The launcher path, `--effort max`, and `--dangerously-skip-permissions` startup arguments are fixed in the APK and cannot be replaced by an imported endpoint profile. These arguments apply only when tmux creates a new Claude process; attaching to an existing tmux session preserves that process's current effort and permission mode. The wrapper must pass through command-line arguments and validate its private environment file.

Keep dedicated `rokid` accounts unprivileged. RokidTerminal rejects common administrator usernames including `root`, `ubuntu`, `admin`, `administrator`, and `ec2-user`. Do not add `rokid` to `sudo` or `docker` unless a later capability is explicitly designed and approved.

## Known hardware behaviors

- `DREAMS_CAST` is managed by Rokid system/companion components. The Wi-Fi interface may keep its local IP while Android temporarily removes the validated default network and public route.
- `ENETUNREACH` means the Wi-Fi/default route is unavailable; it is not an SSH key failure. Retry after Android reports a validated default network.
- USB/ADB is less stable through hubs or adapters. A direct Mac USB-C connection is preferred.
- ADB-injected key events are intermittent unless the display is awake and the Activity is actually resumed.
- tmux sessions are owned by `rokid`. From an admin shell, inspect them with `sudo -iu rokid tmux ls`.
- Installing an APK with `adb install -r` disconnects SSH but preserves the endpoint profile, encrypted identity, and server-side tmux session.

For implementation constraints and security invariants, read `AGENTS.md` or `CLAUDE.md` before editing.
