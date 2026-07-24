# CLAUDE.md - RokidMusic

RokidMusic is a Rokid Glass electric-guitar tablature viewer/player. It uses a
green monochrome visual style and a shared `.tab.json` score format for the
completed web and Android glasses renderers.

Keep this file short. Load only the focused rule files needed for the current
task.

## Rule File Index

- `rules/glasses-app.md`
  - Glasses Android app architecture
  - Start page layout and touch-pad interaction
  - HTTP Score Manager (phone-to-glasses sync over WiFi)
  - Canvas renderer, AudioTrack player, focus handling
  - Build & deploy commands

- `rules/project.md`
  - Project architecture
  - Directory layout
  - Data folder rules
  - Local web renderer startup
  - Start-page score selection behavior

- `rules/tab-json.md`
  - JSON score structure
  - Duration encoding
  - Technique spanners and per-note effects
  - Metadata policy
  - Generated JSON policy

- `rules/transcription.md`
  - Media-to-JSON workflow
  - Video frame extraction and overlap handling
  - Measure mapping requirements
  - Current reference-score transcription notes

- `rules/rendering.md`
  - Timing-first layout, measure width and padding
  - TAB/clef and time-signature reserve areas (notes must not intrude)
  - Rhythm notation, rest symbols (whole, half, quarter, eighth+)
  - Technique rendering: bend, bend-vibrato, vibrato, slide, slur, tie, H/P
  - Range techniques: let-ring, P.M. (technique rail + dashed line, stop at rests)
  - Harmonic marks, ring notes (circled), tuplet brackets, duration warning
  - Measure numbers above staff, time signatures centered on staff
  - Playhead visibility and edit-mode suppression

- `rules/playback.md`
  - Play/Pause/Click-to-seek controls
  - Tempo override UI (playback speed independent of score default tempo)
  - WebAudio preview engine with per-technique pitch modulation
  - Technique playback: bend, bend-vibrato, vibrato, slide, harmonic (octave up)
  - Ring note auto-fill, tied/ghost note suppression, tuplet timing

- Root/project agent guidance
  - Codex primarily follows `AGENTS.md`; Claude Code primarily follows this
    `CLAUDE.md`. Both agents should inspect the other guidance file for useful
    non-conflicting project context and keep shared conventions synchronized.

- `rules/editor.md`
  - Full web score editing mode with selection model
  - Note, beam group, spanner, and measure mutation commands
  - Beam Del (deletes entire beam group), Dup (copies to measure end)
  - Tuplet, harmonic, ring, mute/dead note, and widen controls
  - New empty score creation, inline title/artist/tempo editing (pencil icons)
  - JSON persistence and undo stack

- `skills/video-link-to-tab-json/SKILL.md`
  - End-to-end media-link/JPG/PDF-to-`.tab.json` skill
  - Download, frame extraction, crop, dedup, measure mapping, draft JSON
  - Validation checklist and user handoff requirements

## Loading Guidance

- For repository setup, file placement, or renderer startup, read
  `rules/project.md`.
- For score schema, JSON modeling, or adding a new notation field, read
  `rules/tab-json.md`.
- For converting images, PDFs, videos, or user-provided tabs into JSON, read
  `rules/transcription.md` and `rules/tab-json.md`.
- For converting a TikTok/Douyin/YouTube/video link into a draft score JSON,
  use `skills/video-link-to-tab-json/SKILL.md`.
- For Canvas drawing, visual spacing, rhythm marks, or technique symbols, read
  `rules/rendering.md`.
- For playhead timing, WebAudio, tempo behavior, or guitar-sound changes, read
  `rules/playback.md` and the relevant timing sections in `rules/rendering.md`.
- For web editing mode, manual correction workflows, or JSON save behavior, read
  `rules/editor.md` and `rules/tab-json.md`.

## Important Defaults

- Final score JSON files belong in `data/music/`.
- Original source media belongs in `data/raw/`.
- Temporary OCR/crop/debug artifacts belong in `data/tmp/`.
- **Always start the dev server with `python3 tools/dev_score_server.py --port 8765`**, NOT `python3 -m http.server`. The dev server provides both static file serving AND the `/api/save-score` POST endpoint that the browser editor needs to persist JSON changes. Using the plain http.server will cause save failures with an HTML error page.
- `data/music/index.json` remains useful for the static web renderer, but the
  Android APK does not use it: the APK lists uploaded `.tab.json` files from
  its runtime score directory and reads each file's metadata.
- `tab_renderer.html` is a generic renderer. Do not hard-code song-specific
  correction logic into it.
- For the current reference score, edit `tools/image_to_tab_json.py` and
  regenerate `data/music/zhendeaini_intro.tab.json` instead of hand-editing the
  generated JSON.

## Current State (2026-07-17)

### Web Phase — Complete
`tab_renderer.html` (~5,700 lines). Full editor + playback engine. Reference for glasses port.

### Android App — Start Page, Renderer, Audio & Sync Complete

**Start screen** (`StartScreenView.kt`): Canvas-drawn UI with guitar silhouette, expandable score list (auto-scrolling viewport), context-sensitive interaction hints.

**Touch-pad interaction:**

| State | Click (23/66) | Swipe | Long-press (170 / AI broadcast) | Back |
|---|---|---|---|---|
| Collapsed | Expand list | No action | Enter current | Exit app |
| Expanded | Confirm highlight + collapse | Select (auto-scroll) | No action | Collapse |

Long-press is handled as KEYCODE_TV=170 when delivered, and the current
firmware's `com.android.action.ACTION_AI_START` ordered broadcast is intercepted
in the foreground to prevent the system AI assistant from stealing it. Key 83
is only consumed as the firmware's TP-contact precursor.

**Score sync** (`ScoreServer.kt`): HTTP server on port 8849. Phone/PC browser → upload, delete (with custom modal), delete-all. WiFi auto-detect with ConnectivityManager callback. SharedPreferences persists deletion state across restarts.

**Renderer/player** (`TabRenderer.kt`, `PlayerView.kt`, `AudioEngine.kt`): timing-first
one-measure-per-row Canvas rendering, rhythm/technique overlays, flattened playback
timeline, low-latency PCM guitar synthesis, system media-volume session override
with restore on exit, and automatic scroll are implemented.

Full-screen Canvas focus rule: retain `isFocusableInTouchMode = true` and
`isFocusable = true`, set `defaultFocusHighlightEnabled = false` to suppress the
framework's large DPAD focus frame, and call `post { requestFocus() }` after
switching back to a reused selection view. Otherwise the first click/swipe after
returning from the player may be consumed by focus acquisition.

### Release Status

The Android glasses app's core scope is complete. Remaining ideas such as a
sample/cabinet guitar model, voice commands, and broader firmware validation
are optional enhancements rather than release-blocking TODOs.

The next likely product phase is public hosting for the web renderer so scores
can be browsed from any device. A public read-only deployment may serve the
static renderer, assets, `data/music/*.tab.json`, and `data/music/index.json`.
Do not expose the local development `/api/save-score` endpoint publicly without
authentication, authorization, validation, backups, and write-rate limits.
