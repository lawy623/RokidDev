# Score Editor Rules

Use these rules when adding or changing the web editing mode for RokidMusic
`.tab.json` scores.

## Purpose

The editor is a correction tool for OCR/video/PDF transcription results. It
edits the loaded JSON score data and then asks the normal renderer to redraw the
score. It must not introduce a second renderer-specific score model.

The first priority is the web editor. The Rokid Glass version only needs display
mode at first.

## Entry Point

- Add an `Edit` button in the player controls, visually separated from `Back`.
- Clicking `Edit` toggles edit mode and highlights the button.
- Edit mode stops playback and keeps playback controls available but secondary.
- Leaving edit mode does not discard changes already applied to the in-memory
  score.

## Toolbar Layout

Keep editor controls grouped by workflow, not by implementation detail.

- The player header design must remain visible in edit mode. When editing long
  scores, pin the normal player header to the viewport top and place the editor
  panel directly below it.
- Use an in-score placeholder slot matching the fixed header plus editor panel
  height so the toolbar does not cover the visible system. Avoid plain CSS
  `position: sticky` inside the scaled `.app` container, because transforms can
  make sticky/fixed positioning behave like an overlay attached to the score
  instead of the viewport.
- When moving the header or editor panel outside the scaled `.app` container
  for fixed positioning, apply the current app scale to the fixed UI as well.
  The header title, HUD, and buttons must not visually grow in edit mode.
- The fixed player header keeps the main navigation/playback controls
  available: exit edit mode, back to score selection, play, and stop. The
  header `Edit` button should become `Exit Edit` while edit mode is enabled.
- Keep the fixed editor panel compact. Editor labels and buttons should use the
  compact pre-edit editor sizing, so the edit toolbar does not consume too much
  vertical score space.
- Editor control groups should behave like compact tool groups, not large
  cards. Group labels stay inline with their controls and must not consume a
  whole row inside the group. Groups should shrink to their content instead of
  stretching to fill the full system width.
- Compact means no wasted blank row inside a group; it does not mean groups
  should touch each other. Keep clear horizontal spacing between neighboring
  groups so Pitch, Mark, Measure, Insert, and rhythm controls remain visually
  separable.
- Keep enough vertical spacing between editor rows. Rows should read as
  separate tool belts; do not compress row gaps so much that controls visually
  merge.
- The fixed editor panel should show all editor rows at once. Do not give the
  editor panel its own vertical scrolling region; instead, reserve enough
  in-score placeholder height so the score starts below the full editor island.
- Editor rows should be visually balanced and centered. Do not force every
  group to left-align or fill all available width. Prefer grouping rows by
  workflow so each row has a comparable visual length: note editing, beam/line
  techniques, then bend/spanner parameters.
- All editor-only controls, including the status text and file actions, should
  live inside one centered editor island with a fixed readable max width. Keep
  the island relatively narrow; do not let rows grow across the page just
  because horizontal space exists.
  Leave natural empty space on the left and right instead of spreading editor
  controls across the full viewport.
- Row 1: edit status text, with file actions (`Save JSON`, `Download`, `Undo`)
  aligned at the end of the same line, plus a `?` help button at the far right.
- Row 2: pitch edits and rhythm values.
- Row 3: note marks (tied, A.H., ghost, ring, dead, staccato, delete) and range
  target controls (`Set Target`, `Clear Target`).
- Row 4: measure edits and note insertion defaults.
- Row 5: beam grouping controls.
- Row 6: hammer/pull techniques (H, P, HP, H In, P Out), line techniques
  (Tie, Slur, Slide, Slide In, Slide Out), and harmonics (N.H., A.H., P.H.).
- Row 7: sound techniques (Vibrato, Let Ring, P.M., Trill), bend quick-add
  buttons, and spanner parameter controls (vibrato width, slide direction/kind,
  bend amount, label, Apply Params).

Controls with similar editing intent should stay on the same row where possible.
Do not return to one large mixed button cloud as more features are added.
If one group becomes visually too long, split it into smaller semantic groups
such as Pitch vs Note Mark or HP vs Line Tech vs Sound Tech.

## Current Operation Inventory

This list must stay aligned with the buttons and controls in
`tab_renderer.html`.

File actions:

- `Save JSON`: persist the current in-memory score back to the loaded
  `data/music/*.json` file through `/api/save-score`.
- `Download`: explicitly export the current in-memory score as a downloaded
  JSON copy.
- `Undo`: restore the previous in-memory score snapshot and redraw.

Selection actions:

- `Set Target`: store the next clicked note/event/measure as the range target
  while keeping the current anchor selection.
- `Clear Target`: clear the stored range target.

Note actions:

- `String Up` / `String Down`: move the selected note across strings within
  `1..6`.
- `Fret -` / `Fret +`: change the selected note fret within `0..24` and sync
  `display`.
- `Tied`: toggle tied display and auto-create a
  matching `tie` when possible.
- `A.H.`: toggle `note.status = "artificial-harmonic"`. The note is rendered
  in angle brackets, such as `<19>`; this Mark is independent of the harmonic
  effect in the Technique row.
- `Ghost`: toggle ghost-note display. Ghost notes are silent during playback.
- `Ring`: toggle ring status. Draws a circle around the fret number and
  auto-fills the remaining measure duration (effective tick = measure end).
- `Dead`: toggle dead-note display. A dead note with normal duration is a
  timed muted strike (X); it consumes measure time and advances the tick
  cursor.
- `Mute` (Insert selector): insert a decorative mute (X) at the current
  insertion tick. Mute notes are pure overlays — they do not participate
  in tick retiming, do not contribute to measure duration, and do not
  shift other events. Playback triggers a very short pluck.
- `Staccato`: toggle event-level `{ "type": "staccato" }`.
- `Delete`: delete the selected note or rest event and remove dangling spanners.

Event and insertion actions:

- Duration selector: set selected event duration base to `1`, `2`, `4`, `8`,
  `16`, `32`, or `64`.
- Dots selector: set selected event dots to `0`, `1`, or `2`.
- `Add`: insert an empty measure after the selected measure/event/note.
  If nothing is selected, append an empty measure to the end of the score.
- `Del`: delete the selected measure or the measure containing the
  selected note/event/spanner. Do not allow deleting the only remaining measure.
- `Widen` / `Narrow`: toggle the selected measure between 1-slot and 2-slot
  (wide). Wide measures allocate more horizontal space and the playhead moves
  slower within them.
- `Dup`: duplicate the entire measure — all events, spanners, and attributes —
  and insert the copy after the selected measure.
- `Dup End`: duplicate the selected measure and append the copy to the very
  end of the score.
- `After`: insert a new note or rest event after the selected event, or at the
  selected measure insertion point.
- `Before`: insert a new note or rest event before the selected event.
- `Chord`: add another note to the selected event at the same tick.
- Insert controls set defaults for new notes/rests: string, fret, and status
  (`normal`, `rest`, `dead`, `mute`, or `tied`). Selecting `Rest` creates a
  rest event instead of a note event.

Beam actions:

- `Group Beam`: assign one new `beamGroup` to all note events between the
  anchor event and range target in the same measure.
- `Beam Next`: group the selected note event with the next note event in the
  same measure.
- `Add Prev`: group the selected note event with the previous note event in the
  same measure.
- `Split`: cleave the beamGroup after the selected event — the selection and
  everything before keep the old group, events after get a new group.
- `Dup`: duplicate the entire beam group (all its note events) and append the
  copy at the very end of the measure. The duplicated notes share a new
  beam group ID and have the same strings, frets, durations, and effects.
- `Del`: delete all events in the selected event's beam group, along with any
  spanners that reference the deleted notes or events. The measure is then
  retimed.
- `Ungroup`: clear the selected event's entire `beamGroup` — removes the beam
  connection without deleting the notes.
- `Tuplet` selector: apply a tuplet ratio (`duration.tuplet`) to all events
  in the selected event's beam group. Choices: none, 3:2 (triplet), 2:3,
  4:3, 5:4, 6:4.

Technique quick-add actions:

- `H`: add hammer-on to the target/next note. Default connected (both ends).
- `P`: add pull-off to the target/next note. Default connected (both ends).
- `H In`: add a single-ended hammer-on into the selected note (no source).
  Optional `fromFret` marks the starting fret at the open end.
- `P Out`: add a single-ended pull-off out of the selected note (no target).
  Optional `toFret` marks the ending fret at the open end.
- `HP`: add a three-note hammer-on then pull-off chain.
- `Tie`: add a tie, preferring the next matching string/fret note.
- `Slur`: add a generic slur.
- `Slide`: add a connected slide to the target/next note.
- `Slide In`: add a single-ended slide into the selected note. Optional
  `fromFret` marks the starting fret shown as a small number at the open end.
- `Slide Out`: add a single-ended slide out of the selected note. Optional
  `toFret` marks the ending fret shown as a small number at the open end.
- `Vibrato`: add vibrato to the target/next note.
- `Let Ring`: add an event-range let-ring.
- `P.M.`: add an event-range palm mute.
- `Trill`: add a trill to the target/next note.
- `N.H.` / `A.H.` / `P.H.` / `Off`: toggle natural, artificial, or pinch
  harmonic on the selected note. Stored as `note.effects[{ type: 'harmonic' }]`.
  Plays one octave higher.
- `Bend Up`: add a bend-up curve.
- `Bend Up Down`: add an up-then-down bend curve to the target/next note,
  skipping tied/ghost notes.
- `Bend Vib`: add bend-vibrato — bend up to the target pitch, then hold with
  vibrato oscillation at the peak. Also skips tied/ghost notes for its target.
- `Del Tech`: delete the selected spanner.

Spanner parameter controls (all applied together via `Apply Params`):

- Vibrato width selector controls selected `vibrato` / `bend-vibrato` width:
  `narrow`, `normal`, or `wide`.
- Slide direction selector controls single-ended slide direction: `up` or
  `down`.
- Kind selector (`slideKind`) controls the spanner shape: `shift`
  (connected), `in` (into the note), or `out` (out of the note). For
  slide spanners this maps to `slide-in`/`slide-out`; for H/P spanners
  it maps to `hammer-in`/`pull-out`. Changing this restructures the
  spanner's `from`/`to` fields. Single-ended kinds show an optional
  endpoint fret input.
- Bend amount selector controls bend `curve.alter` and label:
  `1/4`, `1/2`, `full`, `1.5`, or `2`.
- Label input edits selected spanner `label`. Leave empty to auto-generate
  from bend amount.
- `Apply Params` applies all the above settings to the selected spanner.

## Selection Model

The editor has one active selection:

- `note`: one note inside a note/chord event.
- `event`: a note event or rest event.
- `spanner`: a technique object in `measure.spanners`.
- `measure`: a measure, used as the insertion context when no note is selected.

The editor may also have one range target:

- Click `Set Target`, then click a note/event/measure to store that object as
  `editor.target` without replacing the current anchor selection.
- Range operations use the current selection as the anchor and `editor.target`
  as the end.
- `Clear Target` removes the target.

Canvas hit testing should use the same indexes created for rendering:

- note hit boxes from rendered note positions and glyph bounds.
- event hit boxes from tick positions.
- spanner hit boxes use source/target note or event anchors and the
  type-specific visual area. They should cover the visible mark, not just the
  underlying note anchor.
- Technique hit testing should run before normal note hit testing so a click on
  a bend, vibrato, slide, tie, or range mark selects the spanner instead of the
  note underneath.
- Exception: the tight note-number glyph box has the highest priority. If the
  click lands directly on a fret number, select the note even when a bend or
  other technique hit box overlaps that area.
- Bend hit boxes must cover the upper bend curve, arrow, and label area.
- Vibrato hit boxes must cover the full visible wavy line range.

Click behavior:

- Click a note number to select that note.
- Click near a rest to select that rest event.
- Click a technique mark to select the spanner. Then `Del Tech` deletes only
  that selected technique object.
- Click empty measure space to select the measure and insertion tick nearest the
  click.
- Empty measures must still be selectable, even though they have no event hit
  boxes. Use per-measure layout boxes for measure hit testing and selection
  overlays.

Selections are visual overlays only. They must not alter spacing, ticks,
measure widths, or renderer logic.

## Note And Event Editing

For a selected note:

- `String up/down`: decrement/increment `note.string` within `1..6`.
- `Fret -/+`: decrement/increment `note.fret` within `0..24` by default and
  keep `note.display` synchronized unless it has explicit parentheses or other
  source text.
- `Ghost`: toggle `note.status = "ghost"` and display text like `(15)`.
- `Dead`: toggle `note.status = "dead"` and `display = "x"`.
- `Tied`: toggle `note.status = "tied"` and display text like
  `(15)`. When possible, auto-create a `tie` from the previous matching
  string/fret note.
- `A.H.`: toggle `note.status = "artificial-harmonic"` and display the fret in
  angle brackets such as `<19>` without adding a technique-rail effect.
- `Staccato`: toggle `event.articulations[{ type: "staccato" }]`.
- `Delete note`: remove the note from its event; if the event has no notes
  left, remove the event and any spanners pointing at its notes.

For a selected event:

- Duration choices: whole, half, quarter, eighth, sixteenth, thirty-second,
  sixty-fourth.
- Dots: 0, 1, or 2.
- Tick is not a user-facing coordinate. After adding, deleting, or changing the
  duration of note/rest events, the editor recalculates event ticks from the
  ordered note/rest sequence and their durations, then lets the renderer redraw
  from JSON.
- Convert note/rest is allowed only when enough fields can be generated
  safely.

Renderers should always show a circled `?` above the ending barline when the
sum of note/rest durations in a measure does not equal `measure.durationTicks`.
This warning is visible in both display mode and edit mode, and disappears only
after the measure duration is corrected.

After tick or duration edits, sort `measure.events` by `tick`, then by existing
order for ties.

## Adding Notes

`Add Measure` creates a fully empty measure:

1. Insert after the selected measure/event/note; append to the end if no
   selection exists.
2. Use the score time signature to compute `durationTicks`; default to 4/4
   (`3840` ticks at `ppq = 960`) if missing.
3. Set `events: []`, `spanners: []`, and `directions: []`.
4. Generate a new stable measure id without renaming existing ids.
5. Recompute visible measure numbers and `startTick` for all measures.
6. Rebuild rendered systems because adding a measure changes row layout.
7. Select the new empty measure with insertion tick `0`, so `Add After` can
   immediately create its first note.

`Delete Measure` removes a whole measure:

1. Delete the selected measure, or the measure containing the selected object.
2. Remove any spanners in remaining measures whose `from`, `to`, `fromEvent`,
   or `toEvent` points into the removed measure.
3. Recompute visible measure numbers and `startTick` for all remaining
   measures.
4. Rebuild rendered systems because deleting a measure changes row layout.
5. Select the next measure at the same index, or the previous measure if the
   deleted measure was the last one.
6. Do not permit deleting the only measure in the score.

The main add operation is "add after selected event":

1. If a note/rest event is selected, create a note event after it at
   the next position in the measure's event array. Do not ask the user to set
   a raw tick.
2. If only a measure is selected, create it at the selected insertion tick.
3. Default duration is copied from the selected event, otherwise eighth note.
4. Default string/fret are copied from the selected note, otherwise string 2
   fret 0.
   If the editor insert controls are present, their string/fret/status values
   override the copied defaults. Status may be `normal`, `dead`, or `tied`.
5. Generate stable IDs within the measure:
   - event id: `m<number>e<next>`
   - note id: `m<number>n<eventIndex>_<noteIndex>`
   - spanner id: `m<number>sp<next>`
6. After insertion, recalculate note/rest ticks in measure order from durations.
7. Clamp new events inside `measure.durationTicks`.
8. Re-sort events and regenerate IDs only for newly created objects. Do not
   rewrite existing IDs unless a later migration explicitly requires it.

Adding a chord note means adding another `note` object to the selected event at
the same tick and duration.

## Beam Group Editing

Beam grouping edits only `event.beamGroup`.

- `Group selected run`: assign the same new group id to selected note events in
  one measure, e.g. `m4b3`.
- `Group Beam` with a range target assigns a new group id to all note events
  between the anchor event and target event in the same measure.
- `Add to previous group`: copy previous note event's `beamGroup`.
- `Add to next group`: copy next note event's `beamGroup`.
- `Split`: cleave the group after the selected event. Events up to and
  including the selection keep the old `beamGroup`; later events get a new
  `beamGroup`.
- `Clear group`: set all events in the selected event's group to `null`.

Do not change ticks or durations when grouping or splitting beams.

## Technique Editing

Techniques that connect notes or events are stored in `measure.spanners`.

Supported quick-add operations:

- `Tie`: selected note to next note, `{ type: "tie", placement: "below" }`.
- `Slur`: selected note to next note, `{ type: "slur", placement: "below" }`.
- `Hammer-on`: selected note to next note, `{ type: "hammer-on", label: "H" }`.
- `Pull-off`: selected note to next note, `{ type: "pull-off", label: "P" }`.
- `HP`: selected note plus the next two notes generate `hammer-on` then
  `pull-off`. The renderer should draw a chained H/P curve.
- `Slide`: selected note to next note,
  `{ type: "slide", label: "sl.", slideKind: "shift" }`.
- `Slide In`: selected note as `to`, with `direction: "up" | "down"` and
  `slideKind: "slide-in"`.
- `Slide Out`: selected note as `from`, with `direction: "up" | "down"` and
  `slideKind: "slide-out"`.
- `Bend`: selected note, default full bend:
  `{ type: "bend", label: "full", curve: [{ at: 0, alter: 0 }, { at: 0.7, alter: 2 }, { at: 1, alter: 2 }] }`.
- `Bend release`: selected note, default release from full:
  `{ type: "bend", label: "full", preBend: true, curve: [{ at: 0, alter: 2 }, { at: 0.5, alter: 2 }, { at: 1, alter: 0 }] }`.
- `Bend Up Down`: selected note to target/next note, using a bend curve
  `0 -> amount -> 0`.
- Bend amount is parameterized by the editor amount control:
  `1/4 = 0.5`, `1/2 = 1`, `full = 2`, `1.5 = 3`, `2 = 4`.
- `Vibrato`: selected note to next note if present, otherwise to selected event
  range, `{ type: "vibrato", width: "normal" }`.
- `Bend vibrato`: selected note, `{ type: "bend-vibrato", width: "normal" }`.
- `Let ring`: selected event to next event,
  `{ type: "let-ring", fromEvent, toEvent, label: "let ring" }`.
- `Palm mute`: selected event to next event,
  `{ type: "palm-mute", fromEvent, toEvent, label: "P.M.", line: "dashed" }`.
- `Trill`: selected note to next note,
  `{ type: "trill", label: "tr" }`.

For note-to-note techniques, use the explicit range target when present.
Otherwise the default depends on the technique type:

- `tie`: prefer the next matching string/fret note.
- `slide`: auto-link only to a note on the **same string** in the next event.
  If the next event has no note on that string, no target is set.
- `hammer-on`, `pull-off`, `slur`, `trill`, `bend-up-down`: auto-link to the next
  note globally (any string).
- `vibrato`, `bend-vibrato`: **never** auto-link. These only have a target when the
  user explicitly sets one via Set Target.

If the target is in the next measure, store the spanner in the source measure
and point `to` at the next measure's note id.

For event-range techniques (`let-ring` and `palm-mute`), use the explicit target
event when present. Without a target, search only within the **same measure**
for the next event; if this is the last event in the measure, no `toEvent` is
set and the rendered line stops at the measure's content end. Cross-measure
ranges always require an explicit Set Target.

When a spanner is selected, the editor may update its `type`, `label`,
`width`, `direction`, `slideKind`, and bend amount/curve from the parameter
controls.

Technique delete removes only the selected spanner. Deleting a note should also
delete any spanner whose `from`, `to`, `fromEvent`, or `toEvent` references a
removed id.

## JSON Persistence

The renderer remains static-display capable. Editing persistence is optional
and local-development only.

- The browser edits the in-memory `playback.score`.
- After each edit, call the normal render path again with the same score object.
- `Save` sends the full JSON score to a local API endpoint.
- `Save` must write back to the currently loaded `data/music/*.json` file.
  It must not silently download a copy.
- If the API is unavailable, show a clear error telling the developer to run
  `python3 tools/dev_score_server.py --port 8765`.
- `Download` may remain as a separate explicit export action.
- The save endpoint may only write files under `data/music/`.
- Keep generated/transcribed scripts as provenance, but user editor saves may
  update the `.tab.json` directly because they are manual corrections.

Suggested endpoint:

```text
POST /api/save-score
{
  "path": "data/music/zhendeaini_douyin.tab.json",
  "score": { ...full score... }
}
```

The server must normalize and validate the target path so requests cannot write
outside `data/music/`.

## Undo

Keep an undo stack of full JSON snapshots for the first editor version. The
score files are small enough that this is simpler and safer than operation
diffs.

- Push a snapshot before every mutating command.
- `Undo` restores the previous snapshot and redraws.
- `Undo` only changes the in-memory score object. It must never write to
  `data/music/`; only `Save JSON` persists changes to disk.
- After restoring a snapshot, all rendered system/measure references must be
  rebound to the restored score before drawing. Canvas rows must not keep stale
  references to measure objects from the pre-undo score.
- Undo should rebuild rendered systems after structural changes such as adding
  or deleting measures, because system grouping and row count may change.
- Mark the editor dirty after mutations and clean after a successful save.

## Non-Goals For The First Version

- No source-image overlay editor yet.
- No automatic MusicXML/Guitar Pro export.
- No drag-to-rewrite musical time until the click/button model is stable.
- No Rokid Glass editing UI. Glasses remain display-only for now.
