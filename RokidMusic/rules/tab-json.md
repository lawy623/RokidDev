# Tab JSON Rules

RokidMusic uses one JSON score format for both the web renderer and the future
Rokid Glass renderer.

## Format References

- Human-readable spec: `tab_format.md`
- Machine schema: `tab_score.schema.json`

## Core Model

```text
Score
  -> Tracks
  -> Measures
  -> Events
  -> Notes / Rests / Directions
```

Measures may be intentionally empty. An empty measure should keep its normal
`durationTicks` and use `events: []`. This means the score intentionally skips a
visible passage while time still passes. Do not treat this as invalid data and
do not synthesize fake rests unless the source explicitly shows rest symbols.

## Duration

Use explicit musical duration objects:

```json
{ "base": 8, "dots": 0 }
```

Tuplets are set on the `duration` object via `duration.tuplet` and apply to
all events in the same beam group:

```json
{ "base": 8, "dots": 0, "tuplet": { "actual": 3, "normal": 2 } }
```

Common tuplet ratios:
- Triplet: `{ "actual": 3, "normal": 2 }` (3 in the time of 2)
- Duplet: `{ "actual": 2, "normal": 3 }` (2 in the time of 3)
- Quadruplet: `{ "actual": 4, "normal": 3 }` (4 in the time of 3)
- Quintuplet: `{ "actual": 5, "normal": 4 }` (5 in the time of 4)
- Sextuplet: `{ "actual": 6, "normal": 4 }` (6 in the time of 4)

Set `tuplet` to `null` or omit it to remove the tuplet.

Examples:

- Quarter note: `{ "base": 4, "dots": 0 }`
- Eighth note: `{ "base": 8, "dots": 0 }`
- Dotted eighth, equal to `3/16`: `{ "base": 8, "dots": 1 }`
- Sixteenth note: `{ "base": 16, "dots": 0 }`

## Techniques

Use spanners for relationships across time or between notes:

- `hammer-on`
- `pull-off`
- `bend`
- `slide` — optional `fromFret`/`toFret` for single-ended slides to mark
  the open-end pitch as a small number on the same string.
- `vibrato`
- `bend-vibrato`
- `palm-mute`
- `let-ring`
- `tie`
- `slur`
- `trill`

Use per-note effects for properties attached to a single note:

- `harmonic` — `{ type: 'harmonic', label: 'N.H.' | 'A.H.' | 'P.H.' }`.
  Renders a label on the technique rail with dashed line and plays one
  octave higher.
- `tap`
- `accent`
- `staccato`

Note `status` controls display and playback behavior:

- `normal` — standard note, shows fret number.
- `ghost` — silent during playback, shows `(15)`.
- `tied` — connected to a previous note via a tie spanner, shows `(15)`.
- `ring` — draws a circle around the fret number. The note's effective
  tick duration is the remainder of the measure (fills to barline). The
  stored `duration` is kept as-is; playback and tick validation compute
  the effective duration dynamically.
- `dead` — timed muted strike, shows `X`. Consumes measure time normally.
- `mute` — decorative mute, shows `X`. Pure overlay: does not participate
  in tick retiming, does not contribute to measure duration, and triggers
  only a very short pluck during playback.
- `cue` / `unknown` — reserved for transcription hints.

`bend-vibrato` is a hybrid spanner: it carries both a `curve` (like `bend`)
and a `width` (like `vibrato`). The renderer draws the bend-up arrow then a
short vibrato wave at the peak; the audio engine applies the bend pitch ramp
followed by vibrato oscillation around the bent frequency.

`event.articulations` may use either compact string entries such as
`"staccato"` or object entries such as `{ "type": "staccato" }`. Renderers must
accept both forms.

## Metadata

Keep metadata minimal unless a feature needs more fields. The current UI mainly
uses:

- `metadata.title`
- `metadata.artist`

Tempo belongs in `defaults.tempo.bpm`. Tuning belongs in `defaults.tuning`.

## Generated JSON Policy

For generated sample scores, prefer editing the generator/transcription script
and regenerating the JSON instead of hand-editing generated output.

For the current reference score, `tools/image_to_tab_json.py` is the source of
truth for `data/music/zhendeaini_intro.tab.json`.
