# RokidMusic Project Rules

RokidMusic is a guitar tablature viewer/player for Rokid Glass. It displays
electric guitar six-line tablature in a green monochrome AR style.

## Architecture

```text
Image / PDF / Video
  -> Transcription system
  -> .tab.json
  -> Web renderer for testing
  -> Android glasses renderer for production
  -> Visual verification and guided playback
```

## Two Renderers, One Format

Both renderers must share the same JSON format and visual language.

| Area | Web Renderer | Glasses Renderer |
|---|---|---|
| File | `tab_renderer.html` | `app/src/main/java/com/rokid/music/render/TabRenderer.kt` |
| Role | Static preview, debugging, timing test | Interactive Rokid Glass playback |
| Platform | Browser Canvas | Android Canvas |
| Input | Browser controls | Rokid touch pad and Back key |
| Data | `fetch()` from local server | Read `.tab.json` from storage or bundled assets |

## Development Workflow

1. Put untouched source media in `data/raw/`.
2. Generate or correct a `.tab.json` score and place final JSON in `data/music/`.
3. **New empty score**: select "✚ New empty score..." from the start-page dropdown
   and click Create. Builds a blank score (1 empty measure, 75 bpm, title/artist =
   Untitled) in memory. Written to `data/music/Untitled.tab.json` on **Save JSON**.
   Multiple saves auto-increment (Untitled2, Untitled3...).
4. Open `tab_renderer.html` through a local HTTP server and verify rendering.
5. Keep OCR, structure detection, crop, and debug artifacts in `data/tmp/`.
6. When the score is verified, use the same JSON in the glasses renderer.

## Repository Layout

```text
RokidMusic/
├── CLAUDE.md
├── rules/
│   ├── project.md
│   ├── tab-json.md
│   ├── transcription.md
│   ├── rendering.md
│   └── playback.md
├── tab_format.md
├── tab_score.schema.json
├── tab_renderer.html
├── rhythm_test.html
├── assets/
│   └── guitar-player-lineart-transparent.png
├── data/
│   ├── raw/
│   ├── music/
│   │   ├── index.json
│   │   └── *.tab.json
│   └── tmp/
└── app/
```

## Data Directories

- `data/raw/` stores original source files: videos, images, and PDFs.
- `data/music/` is the only long-term output folder for finished score JSON.
- `data/music/index.json` is a static score list for environments that cannot
  list a directory, such as an Android WebView or bundled assets.
- During local development, the web renderer also scans `data/music/` directly,
  so new JSON files usually appear without editing `index.json`.
- `data/tmp/` is disposable workspace output: OCR results, line/system
  detection, symbol candidates, crops, and debug overlays.

## Running the Web Renderer

```bash
cd RokidMusic
python3 tools/dev_score_server.py --port 8765
```

Open:

- `http://localhost:8765/tab_renderer.html`
- `http://localhost:8765/rhythm_test.html`

Do not open `tab_renderer.html` through `file://`. Browsers block `fetch()` for
local JSON files. The renderer redirects to `http://localhost:8765/tab_renderer.html`
when opened from `file://`.

Use `tools/dev_score_server.py` for editing sessions. It serves the same static
files and also exposes `POST /api/save-score`, which lets the editor save
changes directly back to the loaded `data/music/*.json` file. A plain
`python3 -m http.server` is display-only and cannot write JSON files.

## Start Page

`tab_renderer.html` starts on a `Guitar Player` score-selection page. It loads
scores from `data/music/index.json` and also attempts local directory scanning.
The hero guitar image is a generated green line-art PNG in `assets/`.

Use this start page to choose a `.tab.json`; do not hard-code one song into the
generic renderer.

## Public Web Deployment (Future)

The web renderer is complete and may later be deployed to a public server for
always-available score browsing.

- A read-only deployment can be static: publish `tab_renderer.html`, assets,
  `data/music/*.tab.json`, and `data/music/index.json`.
- Public hosting cannot rely on local directory scanning, so keep
  `data/music/index.json` accurate or generate it during deployment.
- Keep source media in `data/raw/` and temporary artifacts in `data/tmp/` out of
  the public artifact unless explicitly needed.
- `tools/dev_score_server.py` is a trusted local editing server. Its
  `/api/save-score` endpoint must not be exposed directly to the internet.
- If remote editing is added later, put it behind authentication, per-score
  authorization, schema validation, backups/versioning, audit logging, and
  rate/size limits.
