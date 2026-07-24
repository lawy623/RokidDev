#!/usr/bin/env python3
"""Build source-frame drafts for the three remaining manually downloaded videos."""

from __future__ import annotations

import json
from pathlib import Path

PPQ = 960
BAR = PPQ * 4


def duration_ticks(base: int) -> int:
    return BAR // base


def note(measure: int, index: int, tick: int, string: int, fret: int, base: int, beam: str | None, status="normal", display=None):
    event_id = f"m{measure}e{index}"
    return {
        "id": event_id,
        "type": "note",
        "tick": tick,
        "duration": {"base": base, "dots": 0},
        "voice": 1,
        "beamGroup": beam,
        "notes": [{
            "id": f"{event_id}n1",
            "string": string,
            "fret": fret,
            "display": display or str(fret),
            "status": status,
            "effects": [],
        }],
        "articulations": [],
    }


def rest(measure: int, index: int, tick: int, base: int):
    return {"id": f"m{measure}e{index}", "type": "rest", "tick": tick,
            "duration": {"base": base, "dots": 0}, "voice": 1}


def bar(number: int, values: list, frame: int, *, string=2, base=None, techniques=None, confidence=0.62):
    """Create a rhythm-valid measure from a left-to-right source TAB sequence."""
    if base is None:
        base = 16 if len(values) > 8 else 8
    step = duration_ticks(base)
    events = []
    tick = 0
    beam = f"m{number}b1" if base >= 8 and len(values) > 1 else None
    for index, value in enumerate(values, 1):
        if tick >= BAR:
            break
        if value == "rest":
            events.append(rest(number, index, tick, base))
        else:
            current_string = string
            raw = value
            if isinstance(value, tuple):
                current_string, raw = value
            if raw == "x":
                events.append(note(number, index, tick, current_string, 0, base, beam, "dead", "x"))
            elif isinstance(raw, str) and raw.startswith("("):
                events.append(note(number, index, tick, current_string, int(raw[1:-1]), base, beam, "tied", raw))
            else:
                events.append(note(number, index, tick, current_string, int(raw), base, beam))
        tick += step
    next_index = len(events) + 1
    while tick < BAR:
        remaining = BAR - tick
        rest_base = 4 if remaining >= 960 else 8 if remaining >= 480 else 16
        events.append(rest(number, next_index, tick, rest_base))
        tick += duration_ticks(rest_base)
        next_index += 1
    return {
        "id": f"m{number}", "number": number, "trackId": "gtr1",
        "startTick": (number - 1) * BAR, "durationTicks": BAR, "attributes": {},
        "barline": {"left": "single", "right": "single", "repeatStart": False,
                    "repeatEnd": False, "repeatCount": None, "ending": None},
        "events": events, "spanners": techniques or [], "directions": [],
        "source": {"frame": frame, "confidence": confidence,
                   "rawText": "Source-frame transcription; verify low-confidence timing and string placement."},
    }


def spanner(sp_id, kind, start, end=None, **extra):
    value = {"id": sp_id, "type": kind, "from": start}
    if end:
        value["to"] = end
    value.update(extra)
    return value


def score(title, artist, tempo, source_file, mode, measures):
    measures[-1]["barline"]["right"] = "final"
    return {
        "schema": "rokid.music.tab-score", "schemaVersion": 1,
        "metadata": {"title": title, "artist": artist},
        "defaults": {
            "ppq": PPQ, "tempo": {"bpm": tempo, "beatUnit": 4, "text": None},
            "timeSignature": {"beats": 4, "beatType": 4, "symbol": "normal", "visible": True},
            "keySignature": {"fifths": 0, "mode": "major", "visible": False},
            "tuning": {"name": "Standard tuning", "capo": 0,
                       "strings": [{"number": n, "pitch": p} for n, p in enumerate(["E4", "B3", "G3", "D3", "A2", "E2"], 1)]},
            "notation": {"staff": "tab", "showRhythm": True, "showStringLabels": True, "colorProfile": "rokid-green"},
        },
        "tracks": [{"id": "gtr1", "name": "Electric Guitar", "instrument": "electric-guitar", "midiProgram": 30,
                    "stringCount": 6, "tuningRef": "defaults.tuning", "visible": True, "playback": {"muted": False, "solo": False}}],
        "systems": [{"id": f"sys-{first}", "measureIds": [f"m{n}" for n in range(first, min(first + 4, len(measures) + 1))]}
                    for first in range(1, len(measures) + 1, 4)],
        "measures": measures,
        "source": {"video": f"data/raw/{source_file}", "motionMode": mode, "transcriptionStatus": "draft-needs-review"},
    }


def main():
    road = [
        bar(1, ["rest", "rest", (3, 5), (3, 5), (2, 6), (2, 8)], 1, base=8,
            techniques=[spanner("m1s1", "slide", "m1e5n1", "m1e6n1", label="sl.")]),
        bar(2, [(2, 8), (2, 6), (2, 6), (2, 8), (2, 10), (2, 8), (2, 10), (2, 8), (3, 7), (3, 9), (3, 7)], 1,
            techniques=[spanner("m2s1", "slide", "m2e4n1", "m2e5n1", label="sl."), spanner("m2s2", "hammer-on", "m2e5n1", "m2e6n1", label="H"), spanner("m2s3", "pull-off", "m2e6n1", "m2e7n1", label="P")]),
        bar(3, [(3, 7), (3, 5), (3, 5), (3, 5), (3, 5), (2, 5), (2, 6)], 1,
            techniques=[spanner("m3s1", "pull-off", "m3e1n1", "m3e2n1", label="P"), spanner("m3s2", "slide", "m3e5n1", "m3e6n1", label="sl.")]),
        bar(4, [(2, 6), (3, 3), (3, 5), (3, 6), (3, 5), (3, 6), (2, 8)], 1,
            techniques=[spanner("m4s1", "slide", "m4e3n1", "m4e4n1", label="sl."), spanner("m4s2", "pull-off", "m4e4n1", "m4e5n1", label="P")]),
        bar(5, [(2, 8), (2, 10), (2, 8), (2, 10), (2, 12), (2, 10), (2, 8)], 30,
            techniques=[spanner("m5s1", "slide", "m5e1n1", "m5e2n1", label="sl.")]),
        bar(6, [(2, 13), (2, 15), (2, 15), (2, 17), (2, 15), (2, 17)], 45,
            techniques=[spanner("m6s1", "hammer-on", "m6e3n1", "m6e4n1", label="H")]),
        bar(7, [(2, 17), (2, 15), (2, 13), (2, 15), (2, 17), (2, 15)], 60,
            techniques=[spanner("m7s1", "slide", "m7e2n1", "m7e3n1", label="sl.")]),
        bar(8, [(2, 15), (2, 13), (2, 13), (2, 15), (2, 15), (2, 12), (2, 15)], 75,
            techniques=[spanner("m8s1", "pull-off", "m8e1n1", "m8e2n1", label="P")]),
        bar(9, [(2, 16), (2, 15), (2, 13), (2, 13), (3, 14), (2, 13)], 100,
            techniques=[spanner("m9s1", "slide", "m9e1n1", "m9e2n1", label="sl.")]),
        bar(10, [(2, 13), (2, 15), (2, "(15)"), (2, 16), (2, 15), (2, 13)], 100,
            techniques=[spanner("m10s1", "hammer-on", "m10e2n1", "m10e3n1", label="H")]),
        bar(11, [(2, 15), (2, 17), (2, 15), (2, 13), (2, 15), (2, 17)], 115,
            techniques=[spanner("m11s1", "slide", "m11e1n1", "m11e2n1", label="sl.")]),
        bar(12, [(2, 17), (2, 19), (2, 17), (2, 19), (2, 20)], 125,
            techniques=[spanner("m12s1", "bend", "m12e3n1", label="full", curve=[{"at": 0, "alter": 0}, {"at": .7, "alter": 2}, {"at": 1, "alter": 0}])]),
        bar(13, [(2, 20), (2, 17), (2, 19), (2, 17)], 140),
        bar(14, [(2, 15), (2, 13), (2, 13), (2, 15), (2, 15), (2, 12), (2, 15)], 155,
            techniques=[spanner("m14s1", "pull-off", "m14e1n1", "m14e2n1", label="P")]),
        bar(15, [(2, 16), (2, 15), (2, 13), (2, 13), (3, 14), (2, 13)], 170),
        bar(16, [(2, 13), (2, 15), (2, 17), (2, 15), (2, 13)], 180),
    ]
    happy = [
        bar(1, ["rest", (2, 10), (1, 12), (1, 13), (1, 12), (1, 13), (1, 12)], 1,
            techniques=[spanner("m1s1", "slide", "m1e2n1", "m1e3n1", label="sl.")]),
        bar(2, [(1, 12), (1, "(12)"), (1, 14), (1, "(14)"), (1, "(14)"), (2, 10)], 1,
            techniques=[spanner("m2s1", "slide", "m2e2n1", "m2e3n1", label="sl."), spanner("m2s2", "vibrato", "m2e3n1", width="normal")]),
        bar(3, [(2, 10), (1, 12), (1, 13), (1, 12), (1, 13), (1, 12)], 1,
            techniques=[spanner("m3s1", "slide", "m3e2n1", "m3e3n1", label="sl.")]),
        bar(4, [(1, 13), (1, 12), (1, 10), (1, 12), (1, 13), (1, 12)], 15),
        bar(5, [(1, 12), (1, 13), (1, 15), (1, 13), (1, 15), (1, 14)], 25),
        bar(6, [(1, 13), (1, 15), (1, 13), (1, 15), (1, 17), (1, "(17)"), (1, 15), (1, 17)], 35,
            techniques=[spanner("m6s1", "vibrato", "m6e5n1", width="normal")]),
        bar(7, [(1, 13), (1, 12), (1, 12), (1, 14), (1, 15), (1, 14)], 40),
        bar(8, [(1, 13), (1, 15), (1, 13), (1, 15), (1, 12), (1, 12), (1, 13), (1, 15)], 45,
            techniques=[spanner("m8s1", "slide", "m8e1n1", "m8e2n1", label="sl."), spanner("m8s2", "hammer-on", "m8e5n1", "m8e6n1", label="H")]),
        bar(9, [(1, 12), "rest", "rest", "rest"], 50, techniques=[spanner("m9s1", "vibrato", "m9e1n1", width="wide")]),
        bar(10, [(1, 11), (1, 10), (1, 11), (1, 13), (1, "(13)"), (1, 11), (1, 13), (1, 15), (1, 13), (1, 15), (1, 17), (1, "(17)"), (1, 15), (1, 17)], 75,
            techniques=[spanner("m10s1", "vibrato", "m10e4n1", width="normal"), spanner("m10s2", "vibrato", "m10e11n1", width="normal")]),
        bar(11, [(1, 18), (1, 17), (1, 18), (1, 20), (1, "(20)"), (1, 18), (1, 17), (1, 18), (1, 20)], 80,
            techniques=[spanner("m11s1", "vibrato", "m11e4n1", width="normal")]),
        bar(12, [(1, 4), (1, 3), (1, 4), (1, 5), (1, 5), (1, 4), (1, 5), (1, 6), (1, 5), (1, 6), (1, 7), (1, 7), (1, 6), (1, 7)], 89, base=16,
            techniques=[spanner("m12s1", "vibrato", "m12e5n1", width="normal")]),
        bar(13, [(1, 1), (1, 7), (1, 1), (1, 2), (1, 2), (1, 1), (1, 3), (1, 4), (1, 3), (1, 4), (1, 5), (1, 5), (1, 7), (1, 1), (1, 2)], 89, base=16,
            techniques=[spanner("m13s1", "vibrato", "m13e4n1", width="normal")]),
        bar(14, [(1, 5), (1, 5), (1, 0), (1, 0), "rest"], 89),
    ]
    dandelion = [
        bar(1, ["rest", "rest", (2, 14), (2, 12), (1, 13), (1, 13), (1, 17)], 1,
            techniques=[spanner("m1s1", "slide", "m1e5n1", "m1e6n1", label="sl.")]),
        bar(2, [(1, 17), (1, 17), (1, 15)], 1,
            techniques=[spanner("m2s1", "bend", "m2e2n1", label="1/2", curve=[{"at": 0, "alter": 0}, {"at": .7, "alter": 1}, {"at": 1, "alter": 0}])]),
        bar(3, [(1, 15), (1, 17), (1, 20), (2, 16), (2, 17)], 1,
            techniques=[spanner("m3s1", "slide", "m3e1n1", "m3e2n1", label="sl.")]),
        bar(4, [(1, 17), (1, 18), (1, 20), (1, 17), (1, 17), (1, 19), (1, 21)], 30,
            techniques=[spanner("m4s1", "slide", "m4e1n1", "m4e2n1", label="sl.")]),
        bar(5, [(1, 21), (2, 17), (2, 19), (2, 17), (2, 20), (2, 19), (2, 17), (1, 19), (1, 17)], 30, base=16),
        bar(6, [(1, 17), (1, 18), (1, 20), (1, 17), (1, 17), (1, 19)], 50),
        bar(7, [(1, 17), (1, 18), (1, 20), (1, 17), (1, 17), (1, 19)], 60),
        bar(8, [(1, 17), (1, 20), (1, 19), (1, "(19)"), (1, 17)], 60,
            techniques=[spanner("m8s1", "bend", "m8e2n1", label="full", curve=[{"at": 0, "alter": 0}, {"at": .7, "alter": 2}, {"at": 1, "alter": 0}]), spanner("m8s2", "bend", "m8e3n1", label="full", curve=[{"at": 0, "alter": 0}, {"at": .7, "alter": 2}, {"at": 1, "alter": 0}])]),
        bar(9, [(1, 19), (1, 19), (1, 19)], 65,
            techniques=[spanner("m9s1", "bend", "m9e1n1", label="full", curve=[{"at": 0, "alter": 0}, {"at": .7, "alter": 2}, {"at": 1, "alter": 0}])]),
        bar(10, [(1, 17), (1, 18), (1, 20), (1, 17), (1, 17), (1, 19)], 75),
        bar(11, [(1, 17), (1, 18), (1, 20), (1, 17), (1, 17), (1, 19)], 85),
        bar(12, [(1, 19), (1, 20), (1, 19), (1, 17), (1, 17), (1, 19), (1, 19), (1, 20), (1, 19), (1, 17), (1, 19), (1, 17)], 90, base=16,
            techniques=[spanner("m12s1", "bend", "m12e1n1", label="full", curve=[{"at": 0, "alter": 0}, {"at": .7, "alter": 2}, {"at": 1, "alter": 0}])]),
        bar(13, [(1, 19), (1, 17), (1, 15), (1, 17), (1, 19), (1, 17), (1, 19)], 95, base=16),
        bar(14, [(1, 17), (1, 18), (1, 20), (1, 17), (1, 17), (1, 19)], 110),
        bar(15, [(1, 17), (1, 18), (1, 20), (1, 17), (1, 17), (1, 19)], 120),
        bar(16, [(1, 17), (1, 19), (1, 17)], 120),
        bar(17, [(1, 17)], 120),
        bar(18, ["rest"], 120, base=1), bar(19, ["rest"], 120, base=1), bar(20, ["rest"], 120, base=1),
    ]
    root = Path(__file__).resolve().parents[1]
    outputs = [
        ("一路向北", "周杰伦", 100, "一路向北_douyin.mp4", "fixed_score_moving_playhead", road, "yiluxiangbei_douyin.tab.json"),
        ("你不是真正的快乐", "五月天", 100, "你不是真正的快乐_douyin.mp4", "fixed_score_moving_playhead", happy, "nibushizhengzhengkuaile_douyin.tab.json"),
        ("蒲公英的约定", "周杰伦", 68, "蒲公英的约定_抖音.mp4", "scrolling_score_window", dandelion, "pugongyingdeyueding_douyin.tab.json"),
    ]
    for title, artist, tempo, source_file, mode, measures, filename in outputs:
        path = root / "data" / "music" / filename
        path.write_text(json.dumps(score(title, artist, tempo, source_file, mode, measures), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(path)


if __name__ == "__main__":
    main()
