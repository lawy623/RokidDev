---
name: video-link-to-tab-json
description: Convert a TikTok, Douyin, YouTube, local video, image, or PDF source into a draft RokidMusic `.tab.json` score with inspectable intermediates and validation checks. Use when the user provides a media link/file and wants a playable TAB JSON draft for manual correction.
---

# Video Link To RokidMusic TAB JSON

Use this skill to produce a draft RokidMusic `.tab.json` from TikTok, Douyin,
YouTube, video files, JPGs, or PDFs. The goal is not a perfect transcription in
one pass. The goal is an inspectable draft with enough checks and source
artifacts that the user can correct the remaining mistakes in the web editor.

## Required Context

Before transcribing, read:

- `RokidMusic/rules/transcription.md`
- `RokidMusic/rules/tab-json.md`
- `RokidMusic/tab_format.md` when field-level details are needed.

If renderer/editor behavior matters during QA, also read:

- `RokidMusic/rules/rendering.md`
- `RokidMusic/rules/editor.md`

## Directory Contract

- Untouched source media: `RokidMusic/data/raw/`
- Temporary frames, crops, maps, debug overlays: `RokidMusic/data/tmp/<source-stem>/`
- Final draft score JSON: `RokidMusic/data/music/<slug>.tab.json`
- Final `.tab.json` filenames must be ASCII only: lowercase English/pinyin,
  digits, underscores, and the `.tab.json` suffix. Do not use Chinese or other
  non-ASCII characters in JSON paths, even when `metadata.title` is Chinese.
  Example: `pugongyingdeyueding_douyin.tab.json`.
- Do not put extracted frames or crops in `data/raw/`.
- Do not hard-code song-specific transcription fixes into `tab_renderer.html`.

## Workflow

### 1. Acquire Source

For video links, first try the local project `yt-dlp` environment without
Cookie access. This is the historical RokidMusic path and works for some
public Douyin videos. Prefer the project virtualenv when it exists; use the
mirror only when installing from the default package index times out:

```bash
RokidMusic/.venv/bin/python -m pip install yt-dlp
RokidMusic/.venv/bin/yt-dlp --no-playlist -f best \
  -o 'RokidMusic/data/raw/%(extractor)s_%(id)s.%(ext)s' '<url>'
```

Installation fallback:

```bash
RokidMusic/.venv/bin/python -m pip install \
  -i https://pypi.tuna.tsinghua.edu.cn/simple yt-dlp
```

If the extractor reports `Fresh cookies are needed`, do not assume the
download succeeded. Retry with an explicitly authorized browser/session
fallback or ask for the local video file. This error can be link-specific or
time-dependent: an earlier public Douyin link was downloaded successfully with
the same no-Cookie command, while another link may be rejected.

Manual browser fallback for a logged-in Chrome session:

1. Open the video in Chrome, press `F12`, and open `Network`.
2. Enable `Preserve log`, reload, and play the video.
3. Filter by `Media` or search for `mp4`; choose the largest video request.
4. Right-click it and choose `Copy -> Copy as cURL`.
5. Run the copied command locally and append an output path under
   `RokidMusic/data/raw/`, for example:

```bash
mkdir -p RokidMusic/data/raw
# Paste the copied cURL command, remove any Range: bytes=... header,
# and append:
-o 'RokidMusic/data/raw/douyin_<aweme-id>.mp4'
```

Do not paste the cURL command or its Cookie headers into the conversation.
Verify that the resulting file is a complete playable video with `ffprobe`
before starting frame extraction. If the request is an HLS playlist instead of
an MP4, keep the URL and required headers locally and use `ffmpeg` to save the
playlist to `data/raw/`.

Save the untouched video in `RokidMusic/data/raw/` using a stable source slug,
for example:

```bash
yt-dlp -o 'RokidMusic/data/raw/%(extractor)s_%(id)s.%(ext)s' '<url>'
```

For a local JPG/PDF/image source, skip video download and frame extraction.
Place the original source under `data/raw/` when it is part of the
transcription record.

### 2. Extract Frames

For video, extract frames at about `0.5s` intervals. This is usually enough
because one full visible TAB system rarely scrolls away in less than half a
second.

Preferred helper:

```bash
python3 RokidMusic/tools/dedup_video_score_frames.py \
  RokidMusic/data/raw/<video-file> \
  --out-dir RokidMusic/data/tmp/<source-stem> \
  --fps 2
```

Equivalent raw `ffmpeg` command:

```bash
ffmpeg -hide_banner -loglevel error -i RokidMusic/data/raw/<video-file> \
  -vf fps=2 RokidMusic/data/tmp/<source-stem>/frames/frame_%05d.jpg
```

### 3. Crop Score Area And Deduplicate

Use the score crop helper output first:

- `detected_score_crop.jpg`
- `dedup/`
- `score_crops/`
- `contact_sheet.jpg` if generated.

Check that the crop contains the complete TAB staff, rhythm marks, measure
numbers, and technique rail. The score area is often the whitest region, but
some videos use colored notation or a transparent/colored background. If the
auto crop cuts off rhythm marks or techniques, adjust the crop before
transcribing.

Deduplication should ignore moving highlights where possible. Keep the
deduplication report so later mistakes can be traced to frames.

#### Motion-mode decision

Choose the mode from the notation geometry, not from the video filename:

| Observation across adjacent frames | Mode | Deduplication anchor |
| --- | --- | --- |
| Staff lines, barlines, and note centers keep the same x/y positions; only a playback line or highlight changes | Fixed score / moving playhead | Normalized score ink after masking the playhead |
| Staff lines, barlines, and note centers translate; neighboring frames show overlapping measures at different x positions | Scrolling score window | Measure number + barline pair + aligned note/technique content |

Use at least three adjacent samples before deciding. A single changed frame is
not enough evidence: it may be a playhead, a transition, or compression noise.
If the score also changes y position or scale, classify it as scrolling and
record the estimated x/y offset and scale for each alignment group. When the
classification remains ambiguous, keep both frames and report the affected
measure instead of deduplicating it silently.

Before trusting deduplication, classify the video motion model:

- **Fixed score / moving playhead**: the notation stays at fixed coordinates and
  only a vertical playback line or highlight changes. Mask the playhead and
  deduplicate by score ink.
- **Scrolling score window**: the notation itself shifts horizontally between
  frames. Do not treat every changed full-frame hash as a new musical passage.
  Detect the six TAB lines, barlines, visible measure numbers, and note ink;
  estimate horizontal translation between neighboring frames; normalize the
  score crop by that translation; then deduplicate by measure identity and
  overlapping content.

Technique handling depends on the motion model:

- In fixed-score mode, keep technique marks from one clean frame and mask only
  the playback line/highlight. Do not duplicate bend, vibrato, slide, slur, or
  H/P objects when the playhead crosses them.
- In scrolling-score mode, attach technique marks to aligned notes/events or
  measures before deduplication. Bend arrows, vibrato waves, H/P, slide lines,
  ties, let-ring lines, P.M. brackets, staccato dots, and parenthesized notes
  move with the score and are not frame noise.
- Keep neighboring frames when a playhead or scroll transition covers a
  technique mark. A frame is not a duplicate if it reveals a new technique for
  the same measure.
- Resolve cross-measure technique endpoints after the measure map is built, so
  a curve crossing a barline remains one spanner even when its endpoints come
  from different frames.

For scrolling windows, build the measure map before transcription:

1. Resolve visible measure numbers and barline pairs in every candidate frame.
2. Use overlapping frames to join the same measure across different x
   positions.
3. Prefer the sharpest/least-obstructed frame for each measure, while keeping
   neighboring frames when a technique mark or note is partly occluded.
4. Never infer chronological order from frame filename or timestamp alone.
5. Record discarded duplicate frames and the source frame chosen for each
   measure in `motion_analysis.json` and `measure_map.json`.

Use the printed measure number above the start barline as the primary identity
when it is present. A moving playhead/highlight, a different crop offset, or a
different frame timestamp must never create another measure with the same
printed number. OCR is only a candidate generator: accept a number only after
checking that it sits above a start barline and that neighboring numbers form a
plausible sequence. If a number cannot be read, keep the frame as ambiguous
and report it rather than deduplicating by frame hash alone.

If the video scrolls vertically or changes zoom, estimate both x/y translation
and scale before comparing frames. If alignment confidence is low, preserve
the frames and report the ambiguous measure numbers instead of silently
deduplicating them.

The crop must include the complete notation rail: standard staff if present,
all six TAB lines, barlines, measure numbers, rhythm marks, and technique marks.
Brightness-only crop detection is only a proposal; manually enlarge the crop
when it cuts off TAB or technique symbols.

Do not assume the score is at the top of the video. Some lessons place a
semi-transparent score over the lower guitar footage, while others place a
bright score panel above the player. Inspect a raw sample frame before choosing
the crop. For a lower overlay, preserve the score's alpha/composited contrast
and crop the full notation rail even if the guitar remains visible behind it.
Record the manual crop in `motion_analysis.json`; an auto crop that selects
only the guitar is invalid input for transcription.

### 4. Build A Measure Map

Do not start from notes. First identify measures.

Use TAB staff lines, left/right barlines, visible measure numbers, and overlap
between frames to build a measure map. For horizontally scrolling videos, unique
frames overlap; never assume `frame N` continues directly after `frame N-1`.

Use the number-aware helper before note transcription:

```bash
python3 RokidMusic/tools/build_video_measure_map.py \
  RokidMusic/data/tmp/<source-stem>/manual_frames/*.jpg \
  --stride 5 \
  --out RokidMusic/data/tmp/<source-stem>/measure_map_v2.json
```

Useful helper:

```bash
python3 RokidMusic/tools/detect_video_measure_bars.py \
  RokidMusic/data/tmp/<source-stem>/dedup/*.jpg \
  --out-dir RokidMusic/data/tmp/<source-stem>/bars
```

For each measure, record at least:

- source frame/crop file
- measure number if visible
- staff/string line positions
- barline x positions
- measure bbox
- confidence or notes about ambiguity.

Before creating a JSON measure, reconcile the measure map into one ascending
number sequence. A repeated frame, replayed system, or second appearance of a
scrolling window is additional evidence for the existing measure number, not a
new measure. Do not create filler measures by copying the preceding bar or
repeating its note pattern. If a printed number or its contents cannot be read,
record the gap and lower confidence so the user can repair that exact measure.

For scrolling-score videos, also record:

- all frames in which the measure appears
- normalized x/y offset and scale used to align those frames
- whether the measure number was visible or inferred from neighbors
- the selected best frame and any supplemental frame(s)
- duplicate/overlap decisions and their confidence.

If the source shows measure numbers and any number is missing, duplicated, or
ambiguous, tell the user exactly which measure/frame needs manual checking.

### String Mapping: Prevent One-String-Up Errors

String assignment is a high-risk part of video transcription. A draft must not
assume that a note is on the line visually nearest to the top of its glyph, and
must not infer string numbers from pitch alone. The source TAB geometry has
priority.

For every source crop or alignment group:

1. Detect and record all six TAB lines, their y coordinates, adjacent-line
   spacing, and detection confidence. Re-estimate these coordinates after a
   scroll, zoom, vertical shift, or crop change; do not reuse absolute y values
   from a previous frame.
2. Make the direction explicit before reading notes: the top line is string 1
   (the high E string) and the bottom line is string 6 (the low E string).
   Record the mapping as `line_index: 0..5 -> string: 1..6` so an array reversal
   cannot silently move every note by one string.
3. Detect each complete fret-number glyph first, then use the glyph bounding-box
   center y for string assignment. Do not use the top edge, bottom edge, or the
   center of an individual character. Multi-digit frets such as `15` and
   stacked/chord notes must be grouped before mapping.
4. Compare the glyph center with the local six-line geometry. When a number
   crosses or obscures a line, use the line intersection, glyph baseline, and
   neighboring notes in the same event as evidence. Do not blindly choose the
   nearest line above or below the glyph.
5. Map chord notes independently. A single event can contain notes on several
   strings, and a multi-character fret must never be split into multiple notes.
6. Ignore the moving playback line or highlight when detecting TAB lines. If it
   covers a note or line, use an adjacent clean frame from the same measure.

Create a string-mapping overlay for inspection before writing the final JSON.
The overlay should show the source crop with the six detected lines, labels
`1` through `6`, each note glyph bounding box and center, and the assigned
string number. Save overlays under:

```text
RokidMusic/data/tmp/<source-stem>/string_mapping/
```

At minimum, inspect the first, middle, and last measure of every visible system,
plus measures containing high/low-string changes, chords, or large fret text.

For each note, keep local candidates when confidence is low: the predicted
string and its adjacent alternatives (`string - 1`, `string + 1` when valid).
Build a per-measure and per-frame offset histogram. If a large majority of
notes are consistently one string above or below the overlay evidence, mark the
measure/system as a suspected systematic mapping error and re-check the line
direction and y calibration before delivery. Do not silently correct the whole
pass by pitch or by applying a global offset.

Use standard tuning only as a sanity check when useful:

```text
1: E4   2: B3   3: G3   4: D3   5: A2   6: E2
```

Calculate the implied pitch from `string + fret` to find suspicious jumps or
technique relationships, but never replace a clearly observed source string
with a musically more convenient one. Image geometry is authoritative;
`pitch` is only a validation signal.

Special cases:

- If TAB lines have low contrast, use local contrast enhancement or horizontal
  projection before line detection.
- If the notation scrolls, align the current crop first and then estimate line
  positions in the aligned crop; global video coordinates are invalid.
- If a note is between lines or the six-line fit is unstable, lower confidence,
  preserve the source frame, and report the exact measure for user review.
- Do not deliver a high-confidence string assignment when the overlay shows a
  possible one-string systematic shift.

### 5. Transcribe The Musical Skeleton

For each verified measure:

1. Detect note/rest events from left to right.
2. Map each note center to a string using the detected string-line geometry.
3. Read fret text exactly as visible, including `X`, parenthesized notes, and
   tied/ghost-looking notes.
4. Detect duration marks: stems, flags, beams, dots, tuplets, and rests.
5. Assign `tick`, `duration.base`, `duration.dots`, and `beamGroup`.

Important rules:

- Do not infer beam groups from note count alone.
- Beam groups must follow visible beam marks.
- Keep chords as one event with multiple notes at the same tick.
- Keep rests as events when visible.
- Prefer explicit uncertainty comments in the working notes rather than
  silently guessing.
- Never infer a later measure by repeating an earlier note sequence merely
  because the video contains repeated playback frames.

### 6. Add Techniques

After the skeleton is in place, add techniques. This step usually needs user
double-checking.

Map common source marks to RokidMusic JSON as follows:

- H: `hammer-on` spanner.
- P: `pull-off` spanner.
- HP chain: multiple `hammer-on`/`pull-off` spanners across consecutive notes.
- slur/tie curves: `slur` or `tie`; tied target notes usually use
  `note.status = "tied"` and parenthesized display.
- slide between notes: `slide` with `from` and `to`.
- slide-in/slide-out: `slide` with single endpoint plus `direction` and
  `slideKind`.
- vibrato: `vibrato` spanner with `width` when visible.
- bend/release: `bend` spanner with `label` and `curve`.
- bend vibrato: `bend-vibrato`.
- let ring: `let-ring` spanner, usually event range based.
- P.M.: `palm-mute` spanner, usually event range based.
- staccato dot: `event.articulations`.
- harmonics: per-note `effects` with `type: "harmonic"` and source label.
- ring/circled note: `note.status = "ring"`.
- dead/mute notes: `note.status = "dead"` or `"mute"` depending on whether
  the mark consumes rhythm time.

Support cross-measure and cross-system spanners by using stable note/event IDs.

### 7. Fill Metadata

Fill minimal metadata:

- `metadata.title`
- `metadata.artist`
- `defaults.tempo.bpm`
- `defaults.tuning`
- source notes if useful.

If tempo is missing, default to `100` BPM. If title or artist is missing from
the score image, use the video title/description or ask the user.

### 8. Write Draft JSON

Write the result to:

```text
RokidMusic/data/music/<slug>.tab.json
```

Use stable IDs for measures, events, notes, and spanners. Keep the generated
JSON deterministic so repeated corrections produce readable diffs.

Choose an ASCII filename from a stable English/pinyin slug. The filename is a
system path, not display text: put the original Chinese song title only in
`metadata.title`.

## Validation Checklist

Run these checks before handing the draft to the user.

### File And Schema Checks

- JSON parses:

```bash
python3 -m json.tool RokidMusic/data/music/<slug>.tab.json >/tmp/<slug>.json
```

- If a schema validator is available, validate against
  `RokidMusic/tab_score.schema.json`.

- Filename is ASCII-only and matches a safe path pattern such as
  `[a-z0-9_]+\.tab\.json`.

### Measure Coverage Checks

- Measure numbers are continuous when the source shows them.
- Every visible measure in the measure map has a JSON measure.
- No duplicate measure numbers unless the source explicitly repeats a section.
- The measure map has been checked first; repeated video frames appear as
  additional source occurrences for one measure number, not duplicate JSON
  measures.
- Each JSON measure has `durationTicks`, usually `3840` for 4/4.

### Rhythm Checks

- Sum each measure's event durations and compare to `durationTicks`.
- If the sum is wrong, keep the draft but report the measure numbers. The web
  renderer also shows a circled `?` warning for duration mismatch.
- Verify that rests count toward duration when visible.
- Verify tuplets and dotted rhythms manually; they are common error sources.

### Geometry Checks

- For every source crop/alignment group, six TAB lines were detected or the
  failure was explicitly reported.
- The top-to-bottom mapping was verified as string 1 through string 6.
- Each note was mapped using the complete fret glyph's bounding-box center y,
  not a character edge or a fixed global y ratio.
- Multi-digit frets and chord notes were grouped before string mapping.
- Playback lines/highlights were excluded from line detection.
- String overlays exist under `data/tmp/<source-stem>/string_mapping/` and were
  checked at the start, middle, and end of each system.
- A per-measure/per-frame adjacent-string offset check was performed; suspected
  one-string-up or one-string-down systems are listed in the handoff.
- Pitch/tuning checks were used only as sanity checks, never to override clear
  TAB geometry.
- Preserve source crop filenames for disputed measures.

### Beam And Technique Checks

- Beam groups match visible beams, not just event counts.
- Techniques are present where visible: slide, vibrato, bend, H/P, let-ring,
  P.M., harmonic, staccato, ties, and ring notes.
- Cross-measure ties/slurs/bends point to valid target IDs.
- Deleting or moving notes should not leave dangling spanners.

### Renderer QA

Start the save-capable local server when the user will edit in browser:

```bash
python3 RokidMusic/tools/dev_score_server.py --port 8768
```

Open:

```text
http://127.0.0.1:8768/tab_renderer.html?score=data/music/<slug>.tab.json&v=<cache-bust>
```

Check:

- score loads without console errors
- title/artist render correctly
- all systems are visible
- duration mismatch warnings are expected and reported
- obvious note/string/fret placements match the source
- the user can use Edit mode and `Save JSON` on the dev server.

## Handoff To User

When finished, report:

- final JSON path
- raw source path
- tmp working folder
- number of frames extracted and unique frames kept
- measure count and any missing/ambiguous measure numbers
- rhythm mismatch measures
- string-mapping overlay path and any low-confidence or suspected offset
  measures
- technique areas that need user double-checking
- local renderer URL.

Always state that the generated JSON is a draft and needs manual correction.

## Recovery Rules

- If download fails, ask the user for a local file or a different link.
- If crop detection fails, create manual crop notes and debug images rather
  than continuing blindly.
- If measure boundaries cannot be trusted, stop before note transcription and
  ask the user to verify the measure map.
- If OCR/note recognition is uncertain, use `status: "unknown"` or working
  notes instead of fabricating confidence.
- If the same notation reappears, verify the printed measure number above the
  start barline before deciding whether it is a repeated section or a duplicate
  frame. Do not use raw frame order or image hash alone.
- If direct JPG/PDF transcription is requested, skip video steps 1-3 and start
  with measure mapping.
