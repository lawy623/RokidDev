# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Cross-Agent Project Instructions

`AGENTS.md` is the primary instruction file for Codex, while Claude Code uses
the sibling `CLAUDE.md` as its primary project guidance. When working in this
repository, Codex should also inspect the applicable `CLAUDE.md` (root and, if
present, project-level) for project context, verification notes, and conventions
that may not be duplicated here. Relevant non-conflicting guidance from both
files should be followed. If instructions conflict, obey the higher-priority
system/developer/user instructions first, then this `AGENTS.md`; do not let a
secondary `CLAUDE.md` override them. Shared conventions should be synchronized
in both files when practical.

## Project Overview

This is a monorepo for Rokid Glass (AR glasses) application development. Each sub-directory is an independent Android app project. The Rokid Glass runs a custom Android OS with AR display capabilities.

## Rokid Glass Development Reference

Full documentation is saved locally in `.docs/glass-docs/`. The official source is https://rokid.github.io/glass-docs/.

Community reference repository index: `.docs/ROKID_REFERENCE_REPOSITORIES.md`; the local snapshot of `Anezium/awesome-rokid` is under `.docs/references/awesome-rokid/`.

Detailed physical-button, touchpad, AIUI `GlobalHook`, two-finger interaction,
and device-verification reference: `.docs/ROKID_INPUT_INTERACTIONS.md`.

### Hardware Interaction Model

Rokid Glass replaces touch screen with **touch pad (TP)** and has **no preview** — the real world IS the preview. Key input mappings:

| User Action | KeyEvent | Notes |
|---|---|---|
| TP single click | KEYCODE_DPAD_CENTER (23) | Confirm |
| TP swipe right | KEYCODE_DPAD_RIGHT (22) | Continuous events |
| TP swipe left | KEYCODE_DPAD_LEFT (21) | Continuous events |
| TP fast swipe right | KEYCODE_DPAD_RIGHT + KEYCODE_DPAD_DOWN (20) | Single event |
| TP fast swipe left | KEYCODE_DPAD_LEFT + KEYCODE_DPAD_UP (19) | Single event |
| TP long press | KEYCODE_TV (170) | User customizable |
| TP double click | KEYCODE_ENTER (66) | User customizable |
| Back single | KEYCODE_BACK (4) | Return |
| Back long | Intent: `com.rokid.glass.homekey.longpress` | Occupied by voice assistant |
| Back double | Configurable (see below) | |
| Power | KEYCODE_POWER (26) | The system may consume it first |
| Volume+ | KEYCODE_VOLUME_UP (24) | Volume up |
| Volume- | KEYCODE_VOLUME_DOWN (25) | Volume down |

**Firmware variance:** Do not assume the table is identical on every Rokid build. On the current RokidTerminal device, the primary TP confirm action has produced `KEYCODE_ENTER` (66), and another touch action has produced `KEYCODE_NOTIFICATION` (83). For a new app or firmware, capture real `WindowManager`/app key logs first and let project-level `AGENTS.md` document the verified mapping.

**Newer-device controls:** Rokid's user guide also documents model-specific
Function and Shutter/Capture buttons, plus configurable trackpad two-finger
shortcuts. Public documentation does not currently define a stable Android
`KeyEvent`, `MotionEvent`, or AIUI code for two-finger gestures. Treat them as
system-level capabilities until verified on the exact model and firmware; a
configured AI shortcut may consume the gesture before the foreground app.

### System Configuration (via adb shell setprop)

- **Double back behavior:** `persist.rokid.backPanicBehavior` — `0` (do nothing), `1` (return to launcher, default), `2` (broadcast Intent `com.rokid.glass.homekey.doubleback`)
- **Default launcher:** Configured via prop, supports multiple pre-installed launchers
- System app blacklist for controlling which apps appear in launcher

### Critical Phone→Glass Porting Differences

1. **Touch pad, not touch screen** — some controls need custom focus handling
2. **No preview** — real world doesn't need preview; use UI SDK for alignment
3. **Camera alignment** — camera world must map to real world (use `RokidSystem.getAlignmentRect()`)
4. **Glass-style UI** — reference OS design spec and UI SDK

### Android Canvas Focus and System Frame Pitfall

On Rokid Android builds, a full-screen custom `View` that is focusable can show
Android's default keyboard/DPAD focus highlight as a large green frame. It can
appear only after the first TP click/swipe, which makes it look like an
intermittent system border and can consume that first input while focus moves.

For a Canvas surface that still needs TP/DPAD events:

```kotlin
isFocusableInTouchMode = true
isFocusable = true
defaultFocusHighlightEnabled = false
```

Disabling the default highlight does not disable focus or key delivery; it only
removes the framework-drawn focus outline. When switching between full-screen
custom views with `setContentView`, explicitly call `requestFocus()` after the
new view is attached (for example via `post { requestFocus() }`). Otherwise a
reused selection view may lose focus after returning from a player, causing the
first click/swipe to do nothing. This setting does not affect an independent
Rokid system overlay or hardware display border, so those must be diagnosed
separately.

### AR Camera

Use Android **Camera2.0 API** for AR projection functionality.

### System Update

- **Manual flash:** Download from `https://rokid-ota.oss-cn-hangzhou.aliyuncs.com/toB/Rokid_Glass/standard/full_image.zip`, extract, run `./update.sh` in msm8998 directory via USB
- **OTA:** Settings → System Update → Check for updates (requires date sync, battery >50%)

### Embedded HTTP Server (Port 8848)

Glasses run a lightweight HTTP server on the local network for face database management:
- `GET /get_user_num` — total user count
- `GET /get_user_info?offset=0&limit=50` — paginated user info
- `POST /add_face` — add face (headers: face_name_web, face_tag_web, isCover, uid)
- Delete face via web UI at the device's local IP:8848

## Available SDKs (all via jcenter)

All SDKs require `jcenter()` in repositories and are initialized in `Application.onCreate()`:

| SDK | Gradle Dependency | Version | Purpose |
|---|---|---|---|
| Face (offline) | `com.rokid.glass:facelib` | 3.1.2.2 | Face detection, tracking, recognition |
| Face (online) | (see docs) | — | Cloud-based face recognition |
| LPR | `com.rokid.glass:lpr` | 3.0.1 | License plate detection & recognition |
| Glass UI | `com.rokid.glass:ui` | 1.5.4 | GlassAlignment, GlassButton, GlassDialog |

Common permissions needed: `INTERNET`, `CAMERA`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`

### Voice Assistant

Rokid Glass has a built-in voice assistant (system app, source not modifiable). It supports:
- **Push-to-talk** and **voice activation** modes (configurable in Settings)
- **Custom wake words** via `/sdcard/Android/data/com.rokid.ai.glassaudio/files/active_word_config.json`
- **LocalSkill SDK** — package your APK as a voice skill for commands like "open/close X", "next page"
- **Instruct SDK** — offline voice command support

### Glass UI SDK — Alignment Concept

Camera preview data must be mapped to the LCD display for the human eye. Use `RokidSystem.getAlignmentRect(previewWidth, previewHeight, previewRect)` to get the correct LCD display region for marking AR overlays.

### Public Companion App (Android Phone)

The phone-side app handles: device binding (QR code-based WiFi provisioning), remote cooperation, face database management, file management, account management.

## GitHub Repos

- Docs: https://github.com/rokid/glass-docs
- Face SDK samples: https://github.com/Rokid/RokidFaceSDK
- LPR SDK samples: https://github.com/Rokid/RokidLprSDK

## Practical Development Lessons (from Flappy Bird project)

### Build & Deploy Pipeline (macOS)

Rokid Glass apps are standard Android APKs. The pipeline:

```bash
# 1. Set up env (Android Studio must be installed)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"

# 2. Build
cd project-dir && ./gradlew assembleDebug

# 3. Install & launch
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.package/.MainActivity

# 4. Watch crash logs
adb logcat | grep -E "AndroidRuntime|System.err"
```

The APK persists on the device after install. Once installed, the app appears in the glasses launcher — no cable needed for subsequent use.

### ADB Connection Quirks

- The device shows as **`RG-glasses-IDP _SN:XXXXXXXX`** in `ioreg -p IOUSB`
- USB-C hubs/adapters can interfere. Direct connection to Mac USB-C port is more reliable
- If `adb devices` shows empty even though the device appears in ioreg: run `adb kill-server && adb start-server && adb devices`
- USB debugging must be enabled on the glasses (Settings → Developer options). If "Developer options" is hidden, tap the build number 7 times in Settings → About

### Sensor (IMU)

Rokid Glass has a **TDK-Invensense icm4x6xx** IMU. Key findings:

- **`Sensor.TYPE_GAME_ROTATION_VECTOR` (type 15) works.** Used for device orientation (pitch/roll/yaw).
- **`Sensor.TYPE_ROTATION_VECTOR` (type 11) is NOT exposed.** Always try both in code: check type 11 first, fall back to type 15.
- `Sensor.TYPE_GYROSCOPE` (type 4) is also available for raw angular velocity.
- Pitch: `SensorManager.getOrientation()` returns `orientation[1]` as pitch in radians. On Rokid Glass, tilting head UP produces a positive change from baseline.
- For game input, **trend-based detection** (detecting rise/fall cycles of pitch) works much better than absolute angle thresholds, because users don't return their head to a precise neutral position after each tilt.

### Display

- The glasses display is a **green monochrome** AR overlay
- Use `Color.GREEN` on `Color.BLACK` background for visibility
- Simple geometric shapes and line art work well; avoid subtle gradients
- Dense bright-green content may create a faint optical duplicate near the top
  of the lens. If `adb screencap` is clean, this is after framebuffer
  composition rather than Canvas overflow or stale frames. App-local brightness
  limiting was not an effective fix in RokidMusic; seek official panel/waveguide
  guidance instead of accumulating padding or global display workarounds.

### Input Design Principles

- **TP click** (KEYCODE_DPAD_CENTER) = primary confirm/select action
- **TP swipe** = navigation between options
- **Back key** = exit or back to previous screen. Always provide a Back-to-exit path on the title screen
- Head motion via gyro is viable for continuous gameplay input, but keep gestures simple (single-axis, trend-based)

### Project Structure Convention

```
RokidDev/
├── AGENTS.md              ← shared Rokid knowledge (this file)
├── .docs/glass-docs/       ← offline copy of official docs
├── RokidGame/
│   ├── AGENTS.md          ← project-specific notes
│   ├── dev.sh             ← build/install/run/log helper script
│   └── app/src/main/...
├── RokidMusic/
└── RokidTerm/
```

Each sub-project should have its own `AGENTS.md` for project-specific architecture and a `dev.sh` script encapsulating the build-deploy commands.
