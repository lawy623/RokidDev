# Transcription Rules

Use these rules when converting source media into RokidMusic `.tab.json`.

## Media-To-JSON Workflow

Use this workflow for TikTok, Douyin, YouTube, or other video links.

1. Download the source video with a tool such as `yt-dlp`. Save the untouched
   video in `data/raw/`.
2. Use `ffmpeg` to extract frames, usually at `0.5s` intervals.
3. Deduplicate near-identical score frames.
4. Put extracted frames, crops, debug images, and dedup results in a dedicated
   subfolder under `data/tmp/`, not `data/raw/`.
5. Crop the score area from each unique frame. Usually the score is the
   brightest area, but some videos use colored or transparent notation and need
   special handling.
6. Before transcribing notes, identify every measure using TAB staff lines,
   measure start/end barlines, and visible measure numbers.
7. Verify that measure numbers at the beginning of measures are complete when
   the source shows them. If measure numbers are missing or ambiguous, stop and
   ask the user to check.
8. Detect the musical skeleton for each measure: string/fret positions, note or
   rest duration, ticks, and beam groups.
9. Do not infer beam groups from note count alone. They must follow the source
   rhythm marks.
10. Add playing techniques such as slide, vibrato, bend, hammer-on, pull-off,
    let-ring, palm mute, harmonics, and ties.
11. Fill metadata: title, artist, tempo, tuning, capo, and any source
    information that matters.
12. If tempo is missing, default to `100` BPM.
13. If title or artist is missing from the score image, use video metadata or a
    user-provided description when available.
14. Write final `.tab.json` files to `data/music/` and verify them in
    `tab_renderer.html`.

If the source is already a JPG or PDF, skip download/frame extraction and start
at measure identification.

## String Mapping Accuracy

String numbers are read from the TAB geometry, not guessed from pitch. A common
failure mode is a systematic one-string-up result, so every transcription must
make the line direction and local y calibration explicit.

- Detect all six TAB lines separately for each crop or alignment group. Refit
  them after scrolling, zooming, vertical movement, or a crop change.
- Verify the direction before reading notes: top line = string 1, bottom line =
  string 6. Keep an explicit `line_index 0..5 -> string 1..6` mapping so an
  inverted array cannot shift the whole score.
- Detect the complete fret glyph first. Assign its string from the glyph
  bounding-box center y and the local six-line geometry. Do not use the glyph's
  top/bottom edge, a single character center, or a fixed y percentage.
- Group multi-digit frets such as `15` and chord notes before assigning strings;
  map each note in a chord independently.
- Exclude the playback line/highlight from TAB-line detection. Use an adjacent
  clean frame when it covers a line or note.
- For scrolling videos, align the current crop before measuring y positions;
  video-global coordinates are not valid.

For debugging, create overlays showing the six lines, labels 1-6, note glyph
boxes/centers, and assigned strings in:

```text
data/tmp/<source-stem>/string_mapping/
```

Inspect at least the beginning, middle, and end of each system, plus high/low
string changes, chords, and large fret numbers. Keep adjacent-string
candidates when confidence is low and calculate a per-measure/per-frame offset
histogram. If most notes are consistently one string above or below the source,
re-check the line direction and calibration before generating the final JSON.

Standard tuning may be used as a sanity check (`1:E4, 2:B3, 3:G3, 4:D3, 5:A2,
6:E2`), but it must never override clear visual TAB evidence. If the geometry is
ambiguous, lower confidence and report the exact measure rather than silently
applying a global string offset.

## Horizontally Scrolling Videos

For horizontally scrolling score videos, unique frames overlap. Never assume
`frame N` continues directly after `frame N-1`.

First build a measure map that connects visible measure numbers and barlines to
unique measure IDs. Then merge overlapping frames before generating JSON.

## Current Reference Score Notes

Treat `tools/image_to_tab_json.py` as the source of truth for the current
reference score. When correcting this sample, edit the script and regenerate the
JSON instead of hand-editing `data/music/zhendeaini_intro.tab.json`.

Tempo in `defaults.tempo.bpm` drives both playhead speed and WebAudio note
trigger timing. For the reference intro score, the source marking is
`Moderate q = 75`, so keep `bpm: 75` unless intentionally testing playback
speed.

Known calibrated correction: measure 10 starts with `999` as `8 + 16 + 16`,
followed by `9757` as `16 + 16 + 16 + 16`, then dotted-quarter `7`, then final
`7-9` as `16 + 16`. This is a useful regression case because it was previously
misgrouped as two eighth notes and missed the second group's final `7`.
