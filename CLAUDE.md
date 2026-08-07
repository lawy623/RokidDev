# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a monorepo for Rokid Glass (AR glasses) application development. Each sub-directory is an independent Android app project. The Rokid Glass runs a custom Android OS with AR display capabilities.

## Rokid Glass Development Reference

Full documentation is saved locally in `.docs/glass-docs/`. The official source is https://rokid.github.io/glass-docs/.

Community reference repository index: `.docs/ROKID_REFERENCE_REPOSITORIES.md`; the local snapshot of `Anezium/awesome-rokid` is under `.docs/references/awesome-rokid/`.

**Rokid 开发生态选型参考 → `.docs/ROKID_ECOSYSTEM.md`** — 对比 AIUI、Android APK、CXR-M、CXR-L、裸机开发五种路径的功能、适用场景和取舍。做技术选型时必读。

**AIUI 完整开发文档 → `.docs/aiui-docs/`** (290 files) — Rokid AI 眼镜官方 JS 小程序框架。含 Canvas 2D API 参考、WXML/WXSS 组件、页面生命周期、AI 接口（ASR/TTS/LLM）、设备传感器、蓝牙、相机、音频、网络等全部 API。与已有的 `.docs/glass-docs/`（旧版 Glass SDK）互补。

**按键、触摸板与双指交互参考 → `.docs/ROKID_INPUT_INTERACTIONS.md`** — 汇总 Android KeyEvent、AIUI `GlobalHook`、新款设备实体键、系统双指快捷方式及真机验证方法。

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

**Firmware variance:** Do not assume the table is identical on every Rokid build. On the current RokidTerminal device, the primary TP confirm action has produced `KEYCODE_ENTER` (66), and another touch action has produced `KEYCODE_NOTIFICATION` (83). For a new app or firmware, capture real `WindowManager`/app key logs first and let project-level `CLAUDE.md` document the verified mapping.

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
5. **Rokid Glass Display** — app View fills full 480×640 pixels (portrait) at 240dpi. Use `@android:style/Theme.NoTitleBar.Fullscreen` theme for a black background. All Canvas content MUST stay within the 480×640 View bounds — always verify the bottommost element's Y coordinate ≤ 640 px before deploying.

   **HTTP Score Server (port 8849):** embedded in the APK, starts in `MainActivity.onCreate()`. Uses `ConnectivityManager.NetworkCallback` to auto-restart on WiFi up/down. `ScoreServer.infoLine()` polls `getLocalIp()` each call — never cache IP/WiFi state. Server restart sets `running = false` before `server.close()`, and the accept-loop catch checks `if (running)` to avoid treating an intentional restart as an error. `Score Manager Error` on the glasses HUD means `error != null`; "WiFi not connected" means `getLocalIp()` returned null.

   **Window/focus frame:** A large frame that appears only after the first TP click/swipe is usually Android's default keyboard/DPAD focus highlight on a full-screen custom View, not the Canvas renderer or an unavoidable hardware border. Keep both `isFocusableInTouchMode = true` and `isFocusable = true` for TP/DPAD delivery, but set `defaultFocusHighlightEnabled = false` to suppress the framework outline. When switching views with `setContentView`, call `post { requestFocus() }` on the view being shown; otherwise a reused selection View can lose focus after returning from a player, causing the first click/swipe to be consumed. This does not affect an independent Rokid system overlay or hardware border, which must be diagnosed separately.

   **Display ghosting (optical):** Dense bright-green content can produce a
   faint ~80–100px optical duplicate near the top of the Rokid display.
   `adb screencap` and saved Canvas output are pixel-perfect, so this is not
   Canvas overflow, clipping failure, or a stale Surface frame. A test that
   capped the app window at 55% brightness did not materially improve it and
   was reverted. Keep reasonable visual breathing room, but do not treat black
   padding as a software fix; ask Rokid for panel/waveguide guidance.

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

> 输入/按键/触摸板真机经验汇总：`.docs/ROKID_INPUT_INTERACTIONS.md`
> （共享参考，含 2026-08-08 快滑配对实测）；RokidTerm 项目级交互合同在
> `RokidTerm/rules/input.md`（Part 1-4，含对话选择器键位）。

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

### SSH / Networking (hardware-verified 2026-08-08, via RokidTerm)

- **JSch exec channels never deliver EOF on this firmware.** The remote
  command exits 0 and its output arrives through `available()`/`read()`,
  but EOF never comes within the timeout. Readers must return on a quiet
  period (~750 ms), never wait for EOF or discard accumulated bytes on
  timeout — the latter silently drops every response. This affects ANY
  JSch-based SSH terminal/exec code on these glasses.
- **`java.io.ByteArrayOutputStream.toString(Charset)` does not exist on
  this firmware** (Android API 33+ method). It throws `NoSuchMethodError`,
  which is an `Error`, NOT an `Exception` — `catch (Exception)` will NOT
  catch it and the background thread crashes. Always use
  `bytes.toString("UTF-8")` (the legacy overload) instead.
- **Do not put `|| true` before appended command arguments**:
  `HELPER="cmd 2>/dev/null || true"` + `"$HELPER list ..."` makes the shell
  run `cmd` with NO arguments and `true` win — every verb invocation fails
  silently. If the helper must tolerate failure, append `|| true` at the
  END of the full command, or drop it and rely on the exit status.

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

### Input Design Principles

- **TP click** (KEYCODE_DPAD_CENTER) = primary confirm/select action
- **TP swipe** = navigation between options
- **Back key** = exit or back to previous screen. Always provide a Back-to-exit path on the title screen
- Head motion via gyro is viable for continuous gameplay input, but keep gestures simple (single-axis, trend-based)

### Project Structure Convention

```
RokidDev/
├── CLAUDE.md              ← shared Rokid knowledge (this file)
├── .docs/glass-docs/       ← offline copy of official docs
├── RokidGame/
│   ├── CLAUDE.md          ← project-specific notes
│   ├── dev.sh             ← build/install/run/log helper script
│   └── app/src/main/...
├── RokidMusic/
├── RokidLocalAsr/          ← on-glasses Whisper tiny test app (superseded)
├── RokidAiuiAsrProbe/      ← AIUI ASR capability probe
└── RokidTerm/
    ├── CLAUDE.md          ← terminal app guidance
    ├── app/src/main/...   ← Android terminal app
    ├── server/            ← server-side helpers deployed to the endpoint
    │                        (rokid-commands, rokid-sessions — Claude
    │                         command list + conversation switcher)
    └── third_party/
        └── asr-server/    ← server-side ASR (FastAPI + SenseVoiceSmall),
                             lives here as a RokidTerm component, not a
                             separate repo; see its own CLAUDE.md
```

Each sub-project should have its own `CLAUDE.md` for project-specific architecture and a `dev.sh` script encapsulating the build-deploy commands.
