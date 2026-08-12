# Rendering Rules

These rules are hard constraints shared by `tab_renderer.html` and the completed
Android Canvas renderer in `app/src/main/java/com/rokid/music/render/TabRenderer.kt`.

## Note Status Glyphs

- `status = "artificial-harmonic"` renders the note's original fret/display
  text inside angle brackets, for example `19` as `<19>`.
- The brackets are part of the note glyph. Background masking, density
  estimation, selection hit boxes, and spanner endpoints must account for the
  complete bracketed text.
- This status is a standalone note Mark. Do not treat it as the separate
  `harmonic` effect that draws `N.H.` / `A.H.` / `P.H.` on the technique rail
  and changes playback pitch.

## Timing-First Layout

The renderer is not just a static score preview. It must support guided playback
with a vertical playhead moving at a constant speed. Horizontal geometry must
preserve musical time.

1. Every rendered x position must be derived from event `tick` and `duration`.
2. A playhead moving linearly from measure start to measure end must reach each
   event exactly when that event should sound.
3. For a 4/4 measure, `tick = 0` maps to the start content x and
   `tick = durationTicks` maps to the end content x.
4. Distance between events must match their tick distance.
5. Static readability rules may add fixed visual padding, masks, labels, and
   technique marks, but must not change the tick-to-x timing map.
6. If a future renderer needs optical compaction, keep a separate
   `playbackX(tick)` map and ensure notes, stems, beams, spanners, and the
   playhead all use the same map.

## Measure Width And Padding

1. Use a stable base duration width, such as `layout.beatWidth`, as the normal
   one-measure density reference.
2. All measures use a uniform `layout.beatWidth` by default. Automatic
   density-based widening is disabled — measures only expand when the user
   manually applies the Widen button in the editor. Manual widening (2 slots)
   uses a wider beat width based on content density, but rests and notes with
   few events keep the uniform default width.
3. Density estimation must be based on rhythmic events and fret/rest glyph
   widths only. Technique overlays such as slide, bend, vibrato, hammer-on, and
   pull-off must not make a measure consume extra slots; they are drawn on top
   of the note positions created by the timing map.
4. The `beatWidth` chosen for a measure applies to the whole measure timing map.
   Do not arbitrarily move individual notes inside the measure after the map is
   chosen.
5. Complete measures with the same `durationTicks` no longer need identical
   pixel width when their density differs. They must still preserve correct
   tick-to-x proportions within their own measure.
6. The playhead speed is therefore measure-dependent: it moves slower across a
   wider dense measure and faster across a narrower sparse measure. This is
   expected and must match the measure's tick-to-x map.
7. The renderer should pack systems against a fixed target width of about four
   normal measure slots. A normal 4/4 measure usually consumes one slot; a dense
   measure may consume more than one slot.
8. A measure may consume at most two normal measure slots. This means an
   extremely dense 4/4 measure can be displayed as roughly the width of two
   normal 4/4 measures, but not more.
9. If a dense fourth measure would make a four-measure system exceed the target
   width, the system may contain only three measures. If needed, it may contain
   fewer. Keep the row/canvas width consistent with the normal four-slot target
   where practical.
10. The density estimate is only used for manual widen mode to calculate the
   expanded beat width. It should have a tunable upper cap, because very dense
   passages such as 32nd-note runs can become too loose if the renderer gives
   every event its full glyph-comfort spacing.
11. If the glasses renderer needs a fixed viewport width, solve it through
   horizontal scrolling, paging, playback-window clipping, or fewer measures per
   system, not by breaking tick-to-x proportions.
12. Measure start and end padding must be small, fixed, and as symmetrical as
   possible.
13. TAB/time-signature space at the start of a system must be counted separately
    as `clefReserve` (78 px). After dynamic reflow, render TAB/time signature
    only for the first measure of the current rendered system. Do not reserve
    mid-system clef space just because the JSON measure retained source-system
    attributes.
13b. When a measure changes time signature mid-system, a dedicated
    `timeSigReserve` (42 px) is carved out before the first event. No note or
    rest event may be placed inside this reserve area — it is exclusively for
    the time-signature numbers. Technique overlays (vibrato, let-ring, P.M.,
    harmonic lines) that originate in the prior measure may cross into it, but
    the first note of the measure must start after the reserve.
13c. `tickToMeasureX` and `indexMeasure` must both account for
    `measure._showTimeSig` so the note-anchor x positions stay in the timed
    content area beyond the time-sig reserve.
14. Bar lines, TAB, time signature, system-leading space, and system-trailing
   space are visual padding and are outside the musical time domain.
15. The playhead may skip visual empty space between a barline and the first
    event. It may also skip visual empty space after the last real event if that
    space is not represented by a rest/event.
16. A fully empty measure is different from visual padding inside a non-empty
    measure. If `events: []`, render the empty staff measure and let the
    playhead traverse the full `durationTicks`.
17. Measure numbers should sit close to the left barline and above the staff,
    not inside the first-note spacing area. Keep them high enough that dense
    first-position notes cannot collide with the number.

The web player uses a flattened playback timeline: each measure segment starts
at its first event and ends at its last event/rest duration. Missing leading or
trailing silence that is not represented as a rest event is skipped.
For an intentionally empty measure, the segment spans the full measure duration.

For rendering, the active event range of each measure is centered inside the
measure's timed slot. Unused leading/trailing ticks that are not represented by
real events are redistributed evenly as visual empty space, while note-to-note
distances still use the global tick width.

## Rhythm Rendering

1. Rhythm rendering is the foundation layer.
2. The renderer must support single notes/rests and beamed groups by duration:
   `4`, `4.`, `8`, `8.`, `16`, `32`, `64`, and future extensions.
3. Techniques such as slide, vibrato, bend, hammer-on, pull-off, tie, and
   let-ring are overlays on top of the timing skeleton. They must not change
   rhythm spacing.
4. Mixed-duration beam groups can use the same `beamGroup`, for example
   `8th + 16th + 16th`.
5. For mixed groups, the first beam spans the full group. Deeper beams only
   cover consecutive notes that need that level.
6. Beam rendering should be generic by beam level: eighth notes use one beam,
   sixteenths use two, thirty-seconds use three, and so on.
7. A single short note inside a mixed group uses a short hook toward the
   adjacent note.
8. `3/16 + 1/16` must be represented as dotted eighth plus sixteenth:
   `base: 8, dots: 1` followed by `base: 16`.
9. In a beamed dotted pair, the dot belongs near the dotted note's own stem and
   must stay inside the group interval. If the dotted note is followed by
   another grouped note, place the dot about `+6px` after the current stem. If
   the dotted note is the last note in the group, place the dot about `-4px`
   before the current stem.
10. Do not place beamed duration dots below the beam, outside the group, or in a
    visually floating center position.
11. For a single sixteenth following a dotted eighth, the second beam hooks
    backward; it should not extend to the right.
12. Dotted rests should place their dot near the rest glyph itself. For block
    rests (whole and half) the dot belongs directly to the right of the block
    and near the block height. For quarter, eighth, and shorter rests, place
    the dot near the staff middle line.

## Rest Symbols

Rest symbols follow standard notation conventions adapted for green monochrome:

| Rest | Base | Symbol |
|---|---|---|
| Whole | 1 | Rectangular block **below** string 3, top edge flush with the string line |
| Half | 2 | Rectangular block **above** string 3, bottom edge flush with the string line |
| Quarter | 4 | Tall Z-shaped zigzag (upper & lower openings vertical, middle cross bar thick) with a leftward C-curl at the bottom-right endpoint |
| Eighth | 8 | Slanted straight stem + one filled head on the left, connected by a downward-arcing curve |
| 16th+ | 16, 32, 64 | Same as eighth but with 2, 3, or 4 stacked heads following the stem slant |

Use `rhythm_test.html` when changing notes/rests, beam grouping, flags, dots, or
global tick spacing. It covers broad rhythm cases from whole notes through
sixty-fourths, including dotted complements such as `8.+16`, `16.+32`, and
`32.+64`, plus reversed forms. Do not use the song page as the only rhythm
regression test.

## Technique Rails

1. Upper technique marks use a fixed rail at `stringY(1) - stringGap`, exactly
   one string spacing above the first string.
2. `sl.`, hammer-on/pull-off labels, vibrato waves, let-ring text, and many
   bend targets should live on or near this rail.
3. Technique text such as `H`, `P`, `sl.`, and ordinary `full` labels should
   use baseline alignment so the lower edge of the letters sits on the rail.
4. Vibrato waves must stay on the upper technique rail, not follow the current
   string vertically. Use a flat amplitude so the green display does not become
   visually heavy.
5. Hammer-on/pull-off curves are the exception: short two-note curves should sit
   just above the involved fret numbers. Only the `H`/`P` labels stay on the
   upper rail.
6. Lower rhythm marks use a fixed area below the staff. Their highest point
   should not rise above `stringY(6) + stringGap`, exactly one string spacing
   below the sixth string.
7. `layout.rhythmY` may sit further down to lengthen stems, but it must preserve
   the upper no-collision boundary.

## Articulations

1. Event-level `articulations` such as `staccato` are visual overlays attached
   to the event's tick x position. They must not affect rhythm spacing or
   density estimation.
2. Render `staccato` as a small filled dot centered on the upper technique rail
   at `stringY(1) - stringGap`.
3. For a chord or multi-note event, draw one articulation mark at the shared
   event x position, not one mark per string.

## Hammer-On And Pull-Off

1. A continuous three-note hammer/pull chain such as `12-H-13-P-12` should draw
   one curve across all three notes, not two separate short curves.
2. `H` and `P` labels still belong between their respective note pairs.
3. Identical technique structures should use consistent visual style.
4. Actual note distances are still determined by duration and ticks.

## Tie And Slur Curves

1. Ties such as `15-(15)`, including cross-measure ties, should default to a
   shallow curve below the fret numbers.
2. Generic slurs that are not hammer-on or pull-off should also default below
   the fret numbers unless a source-specific case requires otherwise.
3. Both endpoints of a tie or slur curve stay on the same string line as the
   starting note. Do not shift the endpoint to match the target note's string —
   the curve is always flat across one string.
4. Hammer-on and pull-off curves are the main exception: keep their curves above
   the involved fret numbers, with only the `H`/`P` labels on the upper
   technique rail.
5. Tie/slur curves are overlays on the existing note positions and must not
   change measure width, beat width, or tick-to-x spacing.
6. If a tie/slur crosses a rendered system break, do not attempt to draw one
   continuous curve across rows. Split it into two partial curves: the outgoing
   half from the source note to the current system's content end, and the
   incoming half from the next system's content start to the destination note.

## Slide Rendering

Connected slide, where both `from` and `to` are present:

1. Draw a short diagonal line between fret numbers.
2. Mask the underlying string line only in the gap between the numbers.
3. Place `sl.` near the upper technique rail.
4. Compute the x range from rendered fret-number glyph boxes, not note centers
   alone.
5. Use the full whitespace between the two numbers, but never enter or cover
   either number.
6. The horizontal gap is determined by the previous note's rhythmic/tick
   spacing.
7. Slope direction follows fret change: `13 -> 15` rises, `15 -> 13` falls.
8. Keep the vertical offset small so the mark stays close to the string.

Single-ended slide, where only `from` or `to` is present:

1. Store direction in JSON as `direction: "up"` or `"down"` based on the source
   score.
2. Render a short diagonal segment outside the known note glyph box.
3. The default segment length is currently about `12px` with a mild fixed slope.
4. Optional `fromFret` (slide-in) or `toFret` (slide-out) stores the endpoint
   fret number. When present, draw a small fret number at the open end of the
   slide line, on the same string as the anchor note.
5. No `sl.` label is drawn for single-ended slides.

## Bend Rendering

Bend data must be label/curve driven, not hard-coded to `full`.

- `label` controls visible text: `full`, `1/2`, `1/4`, etc.
- `curve.alter` controls semitone meaning: `full` = 2 semitones,
  `1/2` = 1 semitone, `1/4` = 0.5 semitone.
- The renderer should display `sp.label` directly when present.

Normal bend with a following note:

1. Start just above/right of the source fret number.
2. Endpoint x should sit around the horizontal midpoint between the source note
   center and following note center, not all the way at the following glyph box.
3. Draw the first roughly 70% as a shallow downward arc.
4. Only the final segment should rise sharply enough for the arrow to join a
   near-vertical end.
5. The arrow target may reach `stringY(1) - stringGap * 2`.
6. The bend label belongs centered above the arrow and may sit above the normal
   upper technique rail.

Pre-bend/release to a following note (advanced — no UI button, set via Bend
Params or manually in JSON):

1. Use the current note center and following note center as main x anchors.
2. The bend-up endpoint and release-down start should sit at the horizontal
   midpoint between the two note centers.
3. Draw a visible bend-up segment from the current note to this midpoint.
4. Then curve down toward the following note.

Bend-vibrato (bend up + vibrato at peak):

1. Draw the same bend-up curve as a normal bend (first ~70% shallow, final
   segment rising sharply to the upper rail).
2. Draw an upward arrow head at the peak of the bend curve.
3. From the peak, draw a short rightward vibrato wave (~18–40 px, amplitude
   ~2.2 px).
4. Place the bend label centered above the vibrato wave section.

Normal bend without a following note in the same measure:

1. Use the same normal-bend curve.
2. Place the endpoint around the horizontal midpoint between the source note
   center and the measure's right barline.

## Let Ring

1. Render `let ring` text on the upper technique rail, vertically centered on
   the rail line.
2. Draw a dashed line from after the text to the target event position.
3. Draw a short vertical end line at the target position.
4. Store `let-ring` on the previous event in JSON, but start text at the
   horizontal midpoint between the from-event and the following event.

## Palm Mute

1. Render `P.M.` text on the upper technique rail, vertically centered on
   the rail line.
2. Draw a dashed line from after the text to the target event position.
3. Draw a short vertical end line at the target position.

## Vibrato

1. Vibrato and bend-vibrato must respect the target note or `toEvent` x
   position.
2. The wave must start directly above the starting note center.
3. It must end directly above the target note or `toEvent` center.
4. Do not artificially inset the wave.
5. The wave must not pass the current measure's barline — clamp x2 to
   `contentEnd` when the target is in a later measure.
6. The wave must not pass later notes in the same measure.

## Ring Notes

A note with `status: "ring"` represents a sustained note that fills the
remainder of the measure:

1. Draw a circle (ellipse) around the fret number, centered on the text box.
2. The circle uses the standard green stroke (`lineWidth: 1.5`), matching the
   green monochrome style.
3. The visual fret number shows the raw fret value — no parentheses or other
   decoration. The circle is the only differentiator.
4. The stored `duration` object is kept as-is. The effective tick duration
   (`measureDurationTicks - event.tick`) is computed at playback and tick
   validation time.

## Playhead Visibility

1. In display (non-edit) mode, draw the playhead line at `playback.currentTick`
   when it is not null. This covers the moving playhead during playback, the
   paused position after Pause, and the seek marker after clicking a measure.
2. In edit mode, hide the playhead line entirely (`editor.enabled` → skip
   `drawSystemPlayhead`).

## Tuplet Rendering

1. Draw a horizontal bracket below the beam group at `layout.rhythmY + 12`.
2. Small vertical hooks at both ends of the bracket.
3. The tuplet number (`actual`) centered below the bracket.
4. The bracket spans from the first to the last note in the beam group.

## Barlines

1. Draw vertical barline from `stringY(1) - 6` to `stringY(6) + 6`.
2. Double and final barlines use a 4 px horizontal offset.
3. Measure numbers sit at `layout.top - 26`, well above the upper technique rail
   to avoid overlap with technique marks.

## Duration Warning

1. When the summed tick durations of a measure's note/rest events do not equal
   `measure.durationTicks`, draw a circled `?` above the right barline.
2. Intentionally empty measures (`events: []`) are exempt — no warning is drawn.
3. Ring notes fill the remainder of the measure, so a measure ending with a ring
   note will satisfy the duration check.

## Small Visual Optimizations

Rests, short-duration single notes, and dense groups may receive minor visual
optimizations, but only when they do not break the playback timing map.
