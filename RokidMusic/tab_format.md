# RokidMusic Electric Guitar Tab JSON Format

本格式用于把 PDF、图片、视频帧或 Guitar Pro / MusicXML 等来源解析成稳定的中间 JSON，再由 Rokid Glass 前端渲染为电吉他六线谱。目标不是一次性复刻所有排版细节，而是先保存“音乐语义 + 吉他演奏语义 + 必要视觉线索”，让后续 AR 渲染可以在小视野、绿色单色屏上重新排版。

## Design Goals

- **Loss-tolerant**: 能从模糊 OCR 结果逐步补全，字段允许 `confidence`、`source`、`rawText`。
- **Renderer-friendly**: 时间、音符、技巧、跨区间标记都可直接布局，不需要从字符串 tab 反推。
- **Guitar-native**: 以 `string + fret` 为核心，pitch 只是可选校验/播放信息。
- **Multi-voice**: 支持同一小节内 lead、rhythm、低音持续音、和弦、休止符并行。
- **Extensible**: 特殊技巧、教材标注、来源框选、未来音频播放都能挂在 `extensions`。

## Top-level Shape

```json
{
  "schema": "rokid.music.tab-score",
  "schemaVersion": 1,
  "metadata": {},
  "defaults": {},
  "tracks": [],
  "systems": [],
  "measures": []
}
```

### metadata

```json
{
  "title": "真的爱你",
  "artist": "BEYOND"
}
```

Keep metadata intentionally small. OCR/import provenance belongs on object-level `source` fields, not in the score identity.

### defaults

全局默认值可以在任意 measure 内通过 `attributes` 覆盖。

```json
{
  "ppq": 960,
  "tempo": { "bpm": 75, "beatUnit": 4, "text": "Moderate" },
  "timeSignature": { "beats": 4, "beatType": 4 },
  "keySignature": { "fifths": 0, "mode": "major", "visible": false },
  "tuning": {
    "name": "Standard tuning",
    "capo": 0,
    "strings": [
      { "number": 1, "pitch": "E4" },
      { "number": 2, "pitch": "B3" },
      { "number": 3, "pitch": "G3" },
      { "number": 4, "pitch": "D3" },
      { "number": 5, "pitch": "A2" },
      { "number": 6, "pitch": "E2" }
    ]
  },
  "notation": {
    "staff": "tab",
    "showRhythm": true,
    "showStringLabels": true,
    "colorProfile": "rokid-green"
  }
}
```

String numbers follow common tab/MusicXML convention: `1` is the highest/thinnest string, `6` is the lowest/thickest string.

## Tracks

Even if the first version renders one guitar only, keep `tracks` from day one. This avoids schema churn when adding backing guitar, bass, rhythm slash notation, lyrics, or guide clicks.

```json
{
  "id": "gtr1",
  "name": "Electric Guitar",
  "instrument": "electric-guitar",
  "midiProgram": 30,
  "stringCount": 6,
  "tuningRef": "defaults.tuning",
  "visible": true,
  "playback": { "muted": false, "solo": false }
}
```

## Systems

`systems` is optional semantic layout metadata from the source page. The AR renderer may ignore it and reflow measures, but OCR/import tools should preserve it.

```json
{
  "id": "sys-1",
  "source": { "sourceId": "src-cover", "page": 1, "bbox": [35, 294, 1113, 360] },
  "measureIds": ["m1", "m2", "m3", "m4"]
}
```

`bbox` is `[x, y, width, height]` in source pixels.

## Measures

```json
{
  "id": "m1",
  "number": 1,
  "trackId": "gtr1",
  "startTick": 0,
  "durationTicks": 3840,
  "attributes": {},
  "barline": {
    "left": "single",
    "right": "single",
    "repeatStart": false,
    "repeatEnd": false,
    "repeatCount": null,
    "ending": null
  },
  "events": [],
  "spanners": [],
  "directions": [],
  "source": {}
}
```

### Measure Attributes

Use `attributes` only when a value changes at this measure.

```json
{
  "timeSignature": { "beats": 3, "beatType": 4 },
  "keySignature": { "fifths": 2, "mode": "major", "visible": false },
  "tempo": { "bpm": 92, "beatUnit": 4, "text": "Allegro" },
  "tuning": { "name": "Drop D", "strings": [] },
  "capo": 2,
  "clef": "TAB"
}
```

## Events

Events are the time-bearing objects. A tab number, chord, rest, grace note group, text mark, or cue all lives at a `tick` inside a measure.

### Note or Chord Event

```json
{
  "id": "e3",
  "type": "note",
  "tick": 960,
  "duration": { "base": 8, "dots": 0 },
  "voice": 1,
  "beamGroup": "b1",
  "notes": [
    {
      "id": "n3a",
      "string": 2,
      "fret": 15,
      "display": "15",
      "pitch": "D5",
      "effects": []
    }
  ],
  "articulations": [],
  "source": {}
}
```

### Rest Event

```json
{
  "id": "e1",
  "type": "rest",
  "tick": 0,
  "duration": { "base": 4, "dots": 0 },
  "voice": 1
}
```

### Direction / Text Event

Use this for one-off marks anchored to a position: tempo text, section names, `sl.`, `full`, dynamics, rehearsal letters, finger hints.

```json
{
  "id": "d1",
  "type": "direction",
  "tick": 0,
  "placement": "above",
  "text": "Moderate",
  "style": "tempo"
}
```

### Duration

```json
{
  "base": 16,
  "dots": 1,
  "tuplet": { "actual": 3, "normal": 2, "bracket": true, "number": true }
}
```

`base` uses standard note denominators: `1, 2, 4, 8, 16, 32, 64, 128`. Use `tick` and `durationTicks` in addition when imported material has irregular spacing or OCR uncertainty.

## Note Object

```json
{
  "id": "n7",
  "string": 3,
  "fret": 14,
  "display": "(14)",
  "pitch": "A4",
  "status": "normal",
  "leftHandFinger": null,
  "rightHandFinger": null,
  "effects": [
    { "type": "ghost" }
  ],
  "confidence": 0.92,
  "source": {}
}
```

### Note Status

- `normal`: normal fretted/open note.
- `dead`: dead note / muted pluck, usually shown as `x` or `X`.
- `ghost`: quiet/optional note, usually parenthesized.
- `tied`: displayed as continuation or parenthesized tie destination.
- `cue`: small guide note.
- `unknown`: parser saw a mark but cannot classify yet.

## Spanners

Spanners connect notes/events or cover ranges. Prefer spanners over putting fake `*_start` and `*_end` techniques on notes.

```json
{
  "id": "sp1",
  "type": "hammer-on",
  "from": "n5",
  "to": "n6",
  "label": "H",
  "placement": "above"
}
```

Range-style spanners:

```json
{
  "id": "sp-pm-1",
  "type": "palm-mute",
  "fromEvent": "e12",
  "toEvent": "e20",
  "label": "P.M.",
  "line": "dashed",
  "placement": "below"
}
```

### Core Spanner Types

- `tie`: same pitch/string continuation, no re-pick.
- `slur`: generic phrasing slur.
- `hammer-on`: H between fretted notes.
- `pull-off`: P between fretted notes.
- `slide`: connected slide between notes. `slideKind`: `shift`, `legato`, `slide-in`, `slide-out`, `pick-slide`.
- `bend`: bend curve anchored to a note or note pair.
- `vibrato`: wavy line after or above a note/range.
- `let-ring`: sustain line, usually `let ring`.
- `palm-mute`: P.M. dashed line below.
- `trill`: `tr` or rapid alternation line.
- `tremolo-picking`: repeated picking line.
- `barre`: barre/finger span for chord shapes.
- `ottava`: octave line if imported from standard notation.

## Effects and Techniques

Use note `effects` for local properties; use `spanners` when the mark spans time or connects notes.

### Fretting / Sound

```json
{ "type": "open-string" }
{ "type": "dead" }
{ "type": "ghost" }
{ "type": "accent" }
{ "type": "marcato" }
{ "type": "staccato" }
{ "type": "tenuto" }
{ "type": "fermata" }
```

### Harmonics

```json
{ "type": "harmonic", "kind": "natural" }
{ "type": "harmonic", "kind": "artificial", "touchingPitch": "A5" }
{ "type": "harmonic", "kind": "pinch", "label": "P.H." }
{ "type": "harmonic", "kind": "tapped", "label": "T.H." }
{ "type": "harmonic", "kind": "semi" }
```

### Picking / Strumming

```json
{ "type": "pick", "direction": "down" }
{ "type": "pick", "direction": "up" }
{ "type": "alternate-picking" }
{ "type": "sweep", "direction": "down" }
{ "type": "rake" }
{ "type": "strum", "direction": "down" }
{ "type": "pick-scrape" }
```

### Tapping / Legato

```json
{ "type": "tap", "hand": "right", "label": "T" }
{ "type": "tap", "hand": "left", "label": "L.H." }
```

Hammer-on and pull-off should be spanners:

```json
{ "type": "hammer-on", "from": "n1", "to": "n2", "label": "H" }
{ "type": "pull-off", "from": "n2", "to": "n3", "label": "P" }
```

### Slides

```json
{
  "type": "slide",
  "from": "n1",
  "to": "n2",
  "slideKind": "shift",
  "direction": "up",
  "label": "sl.",
  "line": "solid"
}
```

For slide-in/out where one side has no explicit note:

```json
{ "type": "slide", "to": "n2", "slideKind": "slide-in", "direction": "up" }
{ "type": "slide", "from": "n2", "slideKind": "slide-out", "direction": "down" }
```

### Bends

Represent bends as a curve, not only a label. `alter` is semitones: half bend = `1`, full bend = `2`, quarter bend = `0.5`.

```json
{
  "id": "sp-b1",
  "type": "bend",
  "from": "n4",
  "label": "full",
  "curve": [
    { "at": 0.0, "alter": 0 },
    { "at": 0.65, "alter": 2 },
    { "at": 1.0, "alter": 2 }
  ],
  "releaseTo": null,
  "preBend": false,
  "withBar": false
}
```

Common bend variants:

```json
{ "type": "bend", "label": "1/4", "curve": [{ "at": 0, "alter": 0 }, { "at": 1, "alter": 0.5 }] }
{ "type": "bend", "label": "1/2", "curve": [{ "at": 0, "alter": 0 }, { "at": 1, "alter": 1 }] }
{ "type": "bend", "label": "full", "curve": [{ "at": 0, "alter": 0 }, { "at": 1, "alter": 2 }] }
{ "type": "bend", "label": "full", "preBend": true, "curve": [{ "at": 0, "alter": 2 }, { "at": 1, "alter": 2 }] }
{ "type": "bend", "label": "full release", "curve": [{ "at": 0, "alter": 0 }, { "at": 0.5, "alter": 2 }, { "at": 1, "alter": 0 }] }
```

Unison bends are chords where one note has a bend spanner and another note rings normally.

### Vibrato and Tremolo Bar

```json
{ "type": "vibrato", "from": "n1", "toEvent": "e4", "width": "normal", "placement": "above" }
{ "type": "bend-vibrato", "from": "n1", "width": "wide" }
{ "type": "whammy", "from": "n1", "kind": "dive", "curve": [{ "at": 0, "alter": 0 }, { "at": 1, "alter": -12 }] }
{ "type": "whammy", "from": "n1", "kind": "scoop" }
{ "type": "whammy", "from": "n1", "kind": "dip" }
```

### Muting / Sustain

```json
{ "type": "palm-mute", "fromEvent": "e1", "toEvent": "e8", "label": "P.M.", "line": "dashed" }
{ "type": "let-ring", "fromEvent": "e1", "toEvent": "e5", "label": "let ring", "line": "solid" }
{ "type": "mute", "hand": "left" }
{ "type": "mute", "hand": "right" }
```

### Ornaments

```json
{ "type": "grace", "slash": true, "duration": { "base": 32 }, "notes": [] }
{ "type": "trill", "from": "n1", "toEvent": "e4", "auxFret": 8 }
{ "type": "mordent" }
{ "type": "turn" }
```

## Directions

Measure-level directions are not notes but are important for display and navigation.

```json
{
  "id": "dir1",
  "tick": 0,
  "type": "tempo",
  "text": "Moderate",
  "placement": "above"
}
```

Direction types:

- `tempo`: bpm, metronome mark, text such as `Moderate`.
- `dynamic`: `pp`, `p`, `mp`, `mf`, `f`, `ff`, crescendo/diminuendo hairpins.
- `section`: intro, verse, chorus, solo, bridge.
- `rehearsal`: A/B/C, boxed measure marks.
- `chord-symbol`: `C`, `Am7`, `G/B`, power chords.
- `lyric`: lyric syllables, optional for vocal/guitar sheets.
- `instruction`: `simile`, `tacet`, `repeat x4`, `D.S. al Coda`, `Fine`.
- `technique-text`: free text such as `sl.`, `let ring`, `full`.

## Barline, Repeats, Navigation

```json
{
  "left": "repeat-start",
  "right": "repeat-end",
  "repeatStart": true,
  "repeatEnd": true,
  "repeatCount": 2,
  "ending": { "number": "1", "type": "start" }
}
```

Barline values:

- `none`
- `single`
- `double`
- `final`
- `repeat-start`
- `repeat-end`
- `repeat-both`
- `dashed`

Navigation directions:

- `segno`
- `coda`
- `to-coda`
- `d-c-al-fine`
- `d-c-al-coda`
- `d-s-al-fine`
- `d-s-al-coda`
- `fine`

## Source Mapping

Every object may include `source` when parsed from an image/PDF/video:

```json
{
  "sourceId": "src-cover",
  "page": 1,
  "frame": null,
  "bbox": [505, 277, 38, 31],
  "rawText": "full",
  "confidence": 0.88
}
```

Keep low-confidence marks instead of dropping them:

```json
{
  "type": "unknown",
  "tick": 1440,
  "rawText": "~",
  "source": { "confidence": 0.41 }
}
```

## Minimal Example

```json
{
  "schema": "rokid.music.tab-score",
  "schemaVersion": 1,
  "metadata": { "title": "Example", "artist": "Unknown" },
  "defaults": {
    "ppq": 960,
    "tempo": { "bpm": 120, "beatUnit": 4 },
    "timeSignature": { "beats": 4, "beatType": 4 },
    "tuning": {
      "name": "Standard tuning",
      "capo": 0,
      "strings": [
        { "number": 1, "pitch": "E4" },
        { "number": 2, "pitch": "B3" },
        { "number": 3, "pitch": "G3" },
        { "number": 4, "pitch": "D3" },
        { "number": 5, "pitch": "A2" },
        { "number": 6, "pitch": "E2" }
      ]
    }
  },
  "tracks": [{ "id": "gtr1", "name": "Electric Guitar", "instrument": "electric-guitar", "stringCount": 6 }],
  "measures": [
    {
      "id": "m1",
      "number": 1,
      "trackId": "gtr1",
      "startTick": 0,
      "durationTicks": 3840,
      "barline": { "left": "single", "right": "single" },
      "events": [
        { "id": "e1", "type": "rest", "tick": 0, "duration": { "base": 4 }, "voice": 1 },
        {
          "id": "e2",
          "type": "note",
          "tick": 960,
          "duration": { "base": 4 },
          "voice": 1,
          "notes": [{ "id": "n2", "string": 2, "fret": 12, "display": "12" }]
        },
        {
          "id": "e3",
          "type": "note",
          "tick": 1920,
          "duration": { "base": 8 },
          "voice": 1,
          "notes": [{ "id": "n3", "string": 1, "fret": 13, "display": "13" }]
        }
      ],
      "spanners": []
    }
  ]
}
```

## Elements Checklist

Use this checklist when building importers. It combines common electric guitar tab practice, the local reference material in `data/raw/` / `data/tmp/`, and MusicXML tablature concepts.

### Score / Layout

- title, artist
- tuning name, per-string pitch, capo
- tempo text, metronome mark, beat unit
- time signature, key signature, visible/hidden flags
- TAB clef / tab staff, optional standard staff
- measure numbers, system/line breaks, page/source location
- section labels, rehearsal marks, navigation symbols

### Rhythm

- whole, half, quarter, eighth, sixteenth, thirty-second, sixty-fourth notes
- dotted and double-dotted rhythms
- tuplets: triplets, quintuplets, sextuplets, custom ratios
- rests of all durations
- stems, beams, flags, beam groups
- grace notes, cue notes, slash notation
- multiple voices and same-time chords

### Guitar Fretboard

- string number 1-6, fret number 0+
- open strings
- dead/muted notes `x`
- ghost/optional notes `(12)`
- tied continuation notes
- left-hand and right-hand fingerings
- barre notation and position shifts
- chord diagrams / chord symbols when present

### Electric Guitar Techniques

- hammer-on `H`
- pull-off `P`
- legato slurs
- normal tie
- slide up/down, slide in/out, legato slide, shift slide, pick slide
- bend: 1/4, 1/2, full, 1.5, 2, pre-bend, bend-release, hold bend
- unison bend
- vibrato, wide vibrato, bend vibrato
- natural harmonic, artificial harmonic, pinch harmonic `P.H.`, tapped harmonic `T.H.`
- palm mute `P.M.`
- let ring
- dead note / left-hand mute / right-hand mute
- tapping `T`, left-hand tapping
- tremolo picking
- pick direction: downstroke, upstroke
- sweep/rake/strum/arpeggio
- pick scrape
- trill/mordent/turn
- staccato, tenuto, accent, marcato, fermata
- whammy bar: dive, scoop, dip, return, bar vibrato
- volume swell, fade in/out, wah/filter text, feedback/sustain text

### Repeats / Song Form

- repeat start/end, repeat count
- first/second endings
- double bar, final bar
- segno, coda, to coda, Fine
- D.C. / D.S. al Fine / al Coda
- simile marks and repeat-measure symbols

## Rokid Glass Rendering Notes

- Prefer reflowing by measure groups instead of copying source page width; the glasses have limited horizontal field of view.
- Keep a stable tick grid and allow horizontal scrolling by beat or measure.
- Green monochrome display means technique labels must be short: `H`, `P`, `sl.`, `full`, `P.M.`, `let ring`.
- Do not rely on subtle gray hierarchy. Use spacing, line type, and text position.
- For live practice mode, event IDs and ticks let the app highlight the current note without reparsing.

## References

- MusicXML 4.0 tablature tutorial: https://www.w3.org/2021/06/musicxml40/tutorial/tablature/
- MusicXML 4.0 technical notation reference: https://www.w3.org/2021/06/musicxml40/musicxml-reference/elements/technical/
- MusicXML 4.0 bend reference: https://www.w3.org/2021/06/musicxml40/musicxml-reference/elements/bend/
- MusicXML 4.0 slide reference: https://www.w3.org/2021/06/musicxml40/musicxml-reference/elements/slide/
