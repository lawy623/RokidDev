# Playback Rules

The web renderer includes a basic WebAudio playback/playhead prototype. It is a
timing and tone preview tool, not a final sampled guitar amp model.

## Timing

1. `defaults.tempo.bpm` drives playhead speed and note trigger timing.
2. The playhead should follow the same tick-to-x map used for visual rendering.
3. Missing leading or trailing silence that is not represented as a rest event
   may be skipped by the flattened playback timeline.
4. Explicit rests must remain in the playback timeline.
5. Intentionally empty measures are allowed. If a measure has `events: []`, the
   playback timeline must still spend the full `measure.durationTicks` there
   before moving to the next measure. This is different from missing
   leading/trailing silence inside a non-empty measure.

## Tempo UI

The web renderer may provide a temporary BPM override for auditioning.

Current fixed choices:

- `50`
- `75`
- `100`
- `125`
- `150`

Rules:

1. The default displayed value must come from `score.defaults.tempo.bpm`.
2. If the JSON BPM is not one of the fixed choices, show it as the initial
   selected value while still offering the five standard choices.
3. Tempo overrides affect only playback timing and note envelope calculation.
4. Tempo overrides must not mutate the loaded JSON.
5. A page refresh resets the displayed tempo to the JSON tempo.

## Audio Engine

The current sound uses oscillator plucks, pick noise, waveshaper distortion,
short delay, EQ, chorus, and light reverb. It is useful for checking rhythm and
rough electric-guitar feel, but it is not a final amp/cab simulation.

## Playback Controls

1. **Play** starts playback from the current seek position. If no seek position
   is set, starts from tick 0 (beginning of score).
2. **Pause** (previously Stop) halts playback and keeps the playhead visible at
   its last position. Play after Pause resumes from the paused position.
3. **Click-to-seek** (non-edit mode only): clicking any measure sets
   `playback.currentTick` to that measure's `startTick` and draws a static
   playhead line. Play then starts from that measure.
4. Clicking measure 1 resets the seek position to the beginning.
5. **Edit mode** disables Play and Pause buttons. Playback is stopped when
   entering edit mode and cannot be started while editing.
6. When loading a new score, `currentTick` is reset to null (no playhead line).

## Technique Semantics

Playback should respect cheap and reliable core technique semantics:

1. Ghost (`status: "ghost"`) and tied (`status: "tied"`) notes should not
   trigger a new pluck.
2. Ring notes (`status: "ring"`) use an effective duration equal to the
   remaining measure ticks (`measureDurationTicks - event.tick`), filling the
   measure. Audio sustain is extended accordingly.
3. Bend playback should read `curve.alter`.
4. `full` means +2 semitones.
5. `1/2` means +1 semitone.
6. Connected slides should ramp from the source note frequency to the target
   note frequency.
7. Vibrato can use small periodic pitch modulation.
8. Bend notes need a slightly longer audible envelope than ordinary plucked
   notes so the pitch can reach and hold the target bend tone.
9. For plain bend-up playback, audio may reach the target pitch earlier than the
   visual arrow apex, around the first half of the audible note, then hold the
   target pitch.
10. Pre-bend/release should start near the bent pitch and fall according to its
    curve (advanced — no UI button; set via Bend Params or manually in JSON).
11. Bend-vibrato: apply the bend pitch ramp first, then layer vibrato oscillation
    on top of the bent frequency at the peak. Both effects are active — bend
    reaches the target pitch, then vibrato modulates around that target.

More subtle articulations can remain visual-only until the audio engine is
upgraded.
