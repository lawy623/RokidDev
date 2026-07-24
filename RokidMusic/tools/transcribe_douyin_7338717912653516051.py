#!/usr/bin/env python3
"""Create the first source-only transcription draft for Douyin 7338717912653516051.

The note data below is read from the aligned score crops for this video. It is
intentionally kept separate from older score drafts so corrections remain
traceable to the current source.
"""

from __future__ import annotations

import json
from pathlib import Path

PPQ = 960
MEASURE_TICKS = PPQ * 4
SOURCE_ID = "src-douyin-7338717912653516051"
SOURCE_ROOT = "data/tmp/douyin_7338717912653516051/full_score_crops"


def note(event_id, tick, string, fret, base=8, beam=None, **extra):
    out = {
        "id": event_id,
        "type": "note",
        "tick": tick,
        "duration": {"base": base, "dots": extra.pop("dots", 0)},
        "voice": 1,
        "beamGroup": beam,
        "notes": [{"id": f"{event_id}n1", "string": string, "fret": fret,
                   "display": extra.pop("display", str(fret)),
                   "status": extra.pop("status", "normal"), "effects": []}],
        "articulations": extra.pop("articulations", []),
    }
    out.update(extra)
    return out


def rest(event_id, tick, base=4, dots=0):
    return {"id": event_id, "type": "rest", "tick": tick,
            "duration": {"base": base, "dots": dots}, "voice": 1}


def spanner(sp_id, kind, **values):
    if "from_id" in values:
        values["from"] = values.pop("from_id")
    return {"id": sp_id, "type": kind, **values}


def source(measure, frame, raw, confidence=0.72):
    return {
        "sourceId": SOURCE_ID,
        "frame": frame,
        "file": f"{SOURCE_ROOT}/score_{frame:04d}_*.jpg",
        "rawText": raw,
        "confidence": confidence,
    }


def measure(number, events, spanners, frame, raw, confidence=0.72):
    return {
        "id": f"m{number}",
        "number": number,
        "trackId": "gtr1",
        "startTick": (number - 1) * MEASURE_TICKS,
        "durationTicks": MEASURE_TICKS,
        "attributes": {},
        "barline": {"left": "single", "right": "final" if number == 16 else "single",
                     "repeatStart": False, "repeatEnd": False, "repeatCount": None,
                     "ending": None},
        "events": events,
        "spanners": spanners,
        "directions": [],
        "source": source(number, frame, raw, confidence),
    }


def main():
    # Ticks are explicit so the renderer can flag any bar whose visual rhythm
    # needs correction without silently retiming the source transcription.
    measures = [
        measure(1, [
            note("m1e1", 0, 3, 7, 16, "m1b1"), note("m1e2", 240, 3, 6, 16, "m1b1"),
            note("m1e3", 480, 3, 7, 16, "m1b1"), note("m1e4", 720, 3, 9, 16, "m1b1"),
            note("m1e5", 960, 2, 7, 8, "m1b2"), note("m1e6", 1440, 2, 8, 8, "m1b2"),
            note("m1e7", 1920, 2, 10, 8, None), rest("m1e8", 2400, 4, 1),
        ], [spanner("m1s1", "slide", from_id="m1e7n1", direction="down", slideKind="slide-out", label="sl.")], 1,
            "Visible m1: 7-6-7-9, 7-8, 10 with slide-out; source frame shows a final dotted-quarter rest.", 0.86),
        measure(2, [
            note("m2e1", 0, 2, 12, 8, "m2b1", display="/12"),
            note("m2e2", 480, 1, 17, 8, "m2b1"), note("m2e3", 960, 1, 17, 8, "m2b1"),
            note("m2e4", 1440, 1, 15, 8, "m2b1"), note("m2e5", 1920, 1, 17, 8, "m2b1"),
            rest("m2e6", 2400, 4, 1),
        ], [spanner("m2s1", "bend", from_id="m2e2n1", label="full", curve=[{"at": 0, "alter": 0}, {"at": .7, "alter": 2}, {"at": 1, "alter": 2}]),
            spanner("m2s2", "bend", from_id="m2e5n1", label="full", curve=[{"at": 0, "alter": 0}, {"at": .7, "alter": 2}, {"at": 1, "alter": 0}])], 1,
            "Visible m2: /12, 17 full bend/release, 15-17; exact release placement needs review.", 0.8),
        measure(3, [
            note("m3e1", 0, 2, 14, 4), note("m3e2", 960, 1, 17, 8, "m3b1"),
            note("m3e3", 1440, 1, 15, 8, "m3b1"), note("m3e4", 1920, 2, 16, 8, "m3b2"),
            note("m3e5", 2400, 2, 14, 8, "m3b2"),
        ], [spanner("m3s1", "bend", from_id="m3e2n1", label="full", curve=[{"at": 0, "alter": 0}, {"at": .7, "alter": 2}, {"at": 1, "alter": 0}]),
            spanner("m3s2", "slide", from_id="m3e4n1", to="m3e5n1", label="sl.", direction="down", slideKind="shift")], 1,
            "Visible m3: 14, 17 bend to 15, 16-14 descending figure.", 0.68),
        measure(4, [
            note("m4e1", 0, 1, 15, 8, "m4b1"), note("m4e2", 480, 1, 17, 8, "m4b1"),
            note("m4e3", 960, 1, 17, 8, "m4b1"), note("m4e4", 1440, 1, 15, 8, "m4b1"),
            note("m4e5", 1920, 1, 17, 8, "m4b2"), note("m4e6", 2400, 1, 1, 8, "m4b2", display="(17)", status="tied"),
        ], [spanner("m4s1", "bend", from_id="m4e2n1", label="full", curve=[{"at": 0, "alter": 0}, {"at": .5, "alter": 2}, {"at": 1, "alter": 0}]),
            spanner("m4s2", "tie", from_id="m4e5n1", to="m4e6n1", placement="below")], 25,
            "Visible overlap around m4/m5: repeated 17 bend figure and parenthesized continuation.", 0.55),
        measure(5, [
            note("m5e1", 0, 3, 12, 16, "m5b1"), note("m5e2", 240, 3, 14, 16, "m5b1"),
            note("m5e3", 480, 3, 15, 16, "m5b1"), note("m5e4", 720, 2, 13, 8, None),
        ], [], 25, "Visible m5: 12-14-15, then 13.", 0.78),
        measure(6, [
            note("m6e1", 0, 2, 15, 16, "m6b1"), note("m6e2", 240, 2, 2, 16, "m6b1", display="(11)", status="tied"),
            note("m6e3", 480, 2, 11, 16, "m6b1"), note("m6e4", 720, 2, 11, 16, "m6b1"),
            note("m6e5", 960, 3, 10, 8, "m6b2"), note("m6e6", 1440, 3, 10, 8, "m6b2"),
            note("m6e7", 1920, 3, 10, 8, "m6b2"), note("m6e8", 2400, 3, 7, 8, "m6b2"),
            note("m6e9", 2880, 3, 7, 8, "m6b2"), note("m6e10", 3360, 3, 7, 8, "m6b2"),
        ], [spanner("m6s1", "tie", from_id="m6e1n1", to="m6e2n1", placement="below")], 25,
            "Visible m6: 15-(11)-11-11, 10-10-10, 7-7-7; first parenthesized number is partly occluded.", 0.58),
        measure(7, [
            note("m7e1", 0, 1, 17, 16, "m7b1"), note("m7e2", 240, 1, 13, 16, "m7b1"),
            note("m7e3", 480, 1, 13, 16, "m7b1"), note("m7e4", 720, 1, 13, 16, "m7b1"),
            note("m7e5", 960, 2, 10, 8, "m7b2"), note("m7e6", 1440, 2, 10, 8, "m7b2"),
            note("m7e7", 1920, 2, 10, 8, "m7b2"), note("m7e8", 2400, 3, 9, 8, "m7b3"),
            note("m7e9", 2880, 3, 9, 8, "m7b3"), note("m7e10", 3360, 3, 9, 8, "m7b3"),
        ], [spanner("m7s1", "slide", from_id="m7e1n1", to="m7e2n1", label="sl.", direction="down", slideKind="shift")], 35,
            "Visible m7: 17-13-13-13, 10-10-10, 9-9-9 with slide marks.", 0.74),
        measure(8, [
            note("m8e1", 0, 1, 20, 16, "m8b1", display="/20"), note("m8e2", 240, 1, 17, 16, "m8b1"),
            note("m8e3", 480, 1, 17, 16, "m8b1"), note("m8e4", 720, 1, 17, 16, "m8b1"),
            note("m8e5", 960, 1, 17, 16, "m8b2"), note("m8e6", 1200, 1, 19, 16, "m8b2"),
            note("m8e7", 1440, 1, 20, 16, "m8b2"), note("m8e8", 1680, 1, 19, 16, "m8b2"),
            note("m8e9", 1920, 1, 20, 16, "m8b2"), note("m8e10", 2160, 1, 17, 8, None),
        ], [spanner("m8s1", "slide", from_id="m8e1n1", direction="up", slideKind="slide-in", label="sl.")], 45,
            "Visible m8: /20-17-17-17-17-19-20-19-20, ending 17.", 0.62),
        measure(9, [
            note("m9e1", 0, 2, 14, 16, "m9b1"), note("m9e2", 240, 2, 16, 16, "m9b1"),
            note("m9e3", 480, 2, 17, 16, "m9b1"), note("m9e4", 720, 3, 14, 16, "m9b2"),
            note("m9e5", 960, 3, 16, 16, "m9b2"), note("m9e6", 1200, 3, 17, 16, "m9b2"),
        ], [spanner("m9s1", "slide", from_id="m9e1n1", to="m9e3n1", label="sl.", direction="up", slideKind="shift")], 45,
            "Visible m9: 14-16-17 and a second 14-16-17 figure.", 0.6),
        measure(10, [
            note("m10e1", 0, 1, 17, 8, "m10b1"), note("m10e2", 480, 1, 15, 8, "m10b1"),
            note("m10e3", 960, 2, 0, 8, "m10b2", display="x", status="dead"), note("m10e4", 1440, 2, 15, 8, "m10b2"),
        ], [spanner("m10s1", "bend", from_id="m10e1n1", label="full", curve=[{"at": 0, "alter": 0}, {"at": .7, "alter": 2}, {"at": 1, "alter": 0}])], 55,
            "Visible m10: 17 bend to 15, then X-15.", 0.84),
        measure(11, [
            note("m11e1", 0, 1, 17, 8, "m11b1"), note("m11e2", 480, 2, 0, 8, "m11b1", display="x", status="dead"),
            note("m11e3", 960, 2, 17, 8, "m11b1"),
        ], [spanner("m11s1", "bend", from_id="m11e3n1", label="full", curve=[{"at": 0, "alter": 0}, {"at": .7, "alter": 2}, {"at": 1, "alter": 2}])], 65,
            "Visible m11: 17, X-17 with a full bend/release mark at the right.", 0.7),
        measure(12, [
            note("m12e1", 0, 1, 20, 4, display="/20"), note("m12e2", 960, 1, 18, 2),
        ], [], 65, "Visible m12: /20 sustained to 18.", 0.74),
        measure(13, [
            note("m13e1", 0, 1, 17, 4, display="17\\"),
            note("m13e2", 1920, 2, 9, 16, "m13b1", display="/9"), note("m13e3", 2160, 2, 11, 16, "m13b1"),
            note("m13e4", 2400, 2, 12, 16, "m13b1"),
        ], [spanner("m13s1", "slide", from_id="m13e2n1", to="m13e4n1", label="sl.", direction="up", slideKind="shift")], 75,
            "Visible m13: sustained 17, then /9-11-12 slide figure.", 0.64),
        measure(14, [
            note("m14e1", 0, 1, 17, 4, display="17\\"),
            note("m14e2", 1920, 2, 9, 16, "m14b1", display="/9"), note("m14e3", 2160, 2, 11, 16, "m14b1"),
            note("m14e4", 2400, 2, 12, 16, "m14b1"),
        ], [spanner("m14s1", "slide", from_id="m14e2n1", to="m14e4n1", label="sl.", direction="up", slideKind="shift"),
            spanner("m14s2", "let-ring", fromEvent="m14e1", toEvent="m14e4", label="let ring")], 75,
            "Visible m14: 17 and /9-11-12; long let-ring rail is visible above the system.", 0.62),
        measure(15, [rest("m15e1", 0, 2), rest("m15e2", 1920, 2)], [], 85,
            "Visible m15 appears empty except for the notation rest marks.", 0.58),
        measure(16, [rest("m16e1", 0, 2), rest("m16e2", 1920, 2)], [], 85,
            "Visible m16 is empty and ends with a final double barline.", 0.66),
    ]

    score = {
        "schema": "rokid.music.tab-score",
        "schemaVersion": 1,
        "metadata": {"title": "突然好想你", "artist": "五月天"},
        "defaults": {
            "ppq": PPQ,
            "tempo": {"bpm": 70, "beatUnit": 4, "text": None},
            "timeSignature": {"beats": 4, "beatType": 4, "symbol": "normal", "visible": True},
            "keySignature": {"fifths": 0, "mode": "major", "visible": False},
            "tuning": {"name": "Standard tuning", "capo": 0, "strings": [
                {"number": n, "pitch": p} for n, p in enumerate(["E4", "B3", "G3", "D3", "A2", "E2"], 1)
            ]},
            "notation": {"staff": "tab", "showRhythm": True, "showStringLabels": True, "colorProfile": "rokid-green"},
        },
        "tracks": [{"id": "gtr1", "name": "Electric Guitar", "instrument": "electric-guitar", "midiProgram": 30, "stringCount": 6, "tuningRef": "defaults.tuning", "visible": True, "playback": {"muted": False, "solo": False}}],
        "systems": [{"id": f"sys-{i}", "measureIds": [f"m{n}" for n in range(i, min(i + 4, 17))], "source": {"sourceId": SOURCE_ID, "motionMode": "scrolling_score_window"}} for i in range(1, 17, 4)],
        "measures": measures,
        "source": {"video": "data/raw/douyin_7338717912653516051.mp4", "motionAnalysis": "data/tmp/douyin_7338717912653516051/motion_analysis.json", "transcriptionStatus": "draft-needs-review"},
    }
    root = Path(__file__).resolve().parents[1]
    out = root / "data" / "music" / "turanhaoxiangni_douyin.tab.json"
    out.write_text(json.dumps(score, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(out)


if __name__ == "__main__":
    main()
