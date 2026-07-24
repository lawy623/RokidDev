#!/usr/bin/env python3
"""Create a first-pass tab JSON directly from the Douyin frame crops.

This draft intentionally does not import or reuse older hand-calibrated scores.
The measure data below is a rough transcription read from:

  data/tmp/douyin_7379895619067858213/tab_crops_vertical.jpg

It is meant as a complete visual draft for human correction, not a final score.
"""

from __future__ import annotations

import json
from pathlib import Path


PPQ = 960
MEASURE_TICKS = PPQ * 4
TRACK_ID = "gtr1"
SOURCE_ID = "src-douyin-7379895619067858213"


# A note is (string, fret). A chord is a list of note tuples.
# A timed event is {"item": note_or_chord_or_rest, "base": denominator}.
# String numbering follows tab convention: 1 = high E, 6 = low E.
MEASURES: list[list[tuple[int, int] | list[tuple[int, int]] | str]] = [
    [
        {"item": "rest", "base": 4},
        {"item": "rest", "base": 4},
        {"item": "rest", "base": 8},
        {"item": (3, 12), "base": 8, "beam": None},
        {"item": (2, 13), "base": 8, "beam": "m1b1"},
        {"item": (2, 15), "base": 8, "beam": "m1b1"},
    ],
    [
        {"item": (2, 15), "base": 4},
        {"item": (2, 15), "base": 8, "beam": "m2b1"},
        {"item": (2, 13), "base": 16, "beam": "m2b1"},
        {"item": (2, 15), "base": 16, "beam": "m2b1"},
        {"item": (2, 15), "base": 4, "dots": 1},
    ],
    [
        {"item": (2, 15), "base": 8, "beam": "m3b1"},
        {"item": (2, 13), "base": 8, "beam": "m3b1"},
        {"item": (2, 12), "base": 16, "beam": "m3b2"},
        {"item": (2, 13), "base": 16, "beam": "m3b2"},
        {"item": (2, 12), "base": 8, "beam": "m3b2"},
        {"item": (3, 14), "base": 4},
        {"item": (2, 13), "base": 16, "beam": "m3b3"},
        {"item": (2, 15), "base": 16, "beam": "m3b3"},
    ],
    [
        {"item": (2, 15), "base": 4},
        {"item": (2, 15), "base": 8, "beam": "m4b1"},
        {"item": (2, 13), "base": 16, "beam": "m4b1"},
        {"item": (2, 15), "base": 16, "beam": "m4b1"},
        {"item": (2, 15), "base": 4},
    ],
    [(2, 13), (3, 12), (2, 13), (1, 15), (2, 12), (2, 13), (1, 15), (2, 13), (2, 13), (2, 15)],
    [(2, 15), (2, 13), (2, 12), (2, 13), (2, 12), (3, 14), (2, 13), (2, 15)],
    [(2, 15), (2, 15), (2, 13), (2, 15), (2, 15), (2, 15), (2, 13), (2, 12), (2, 13), (3, 12), (3, 10)],
    [(2, 13), (3, 12), (3, 10), (3, 9)],
    [(2, 13), (3, 12), (3, 10), (3, 9), (3, 7), (3, 5), (3, 4)],
    ["rest", "rest", (2, 13), (3, 12), (2, 13)],
    [(2, 12), (2, 13), (2, 13), (2, 15), (2, 13), (2, 13), (2, 12), (2, 13), (2, 12), (2, 14), (2, 12), (2, 14)],
    [(2, 12), (2, 13), (2, 13), (2, 15), (2, 13), (2, 13), (2, 12), (2, 13), (2, 12), (2, 14), (2, 12), (2, 14)],
    [(2, 14), (2, 12), (2, 10), (3, 9), (3, 12), (3, 10), (3, 9), (3, 10)],
    [(3, 10), (3, 10), (3, 9), (3, 10), (3, 12), (3, 10), (3, 9), (3, 10), (3, 9)],
    [(3, 12), (3, 10), (3, 9), (3, 10), (3, 12), (3, 10), (3, 9), (3, 10), (3, 9)],
    [(3, 10), (3, 10), (3, 7), (3, 5), (3, 5), (3, 4), (3, 7), (3, 9), (3, 9), (3, 7)],
    [(3, 8), (3, 8), (3, 6), (3, 5), (3, 5), (3, 7), (3, 5), (3, 4)],
    [(3, 5), (3, 7), (3, 7), (3, 7), (3, 9), (3, 10), (3, 9)],
    [(3, 9), (3, 10), (3, 12), (3, 10), (3, 12), (3, 13), (3, 10), (3, 12), (3, 13)],
    [[(1, 13), (2, 13)], [(1, 15), (2, 15)], [(1, 13), (2, 13)], [(1, 15), (2, 15)]],
    [[(1, 13), (2, 13)], [(1, 15), (2, 15)], [(1, 13), (2, 13)], [(1, 15), (2, 15)]],
    ["rest", "rest", (3, 5), (3, 5), (3, 5), (3, 7)],
    [(3, 7), (3, 7), (3, 7), (3, 5), (3, 7), (3, 7), (3, 7)],
    [(3, 7), (3, 5), (3, 4), (3, 5), (3, 4), (3, 5), (3, 7), (3, 2), (3, 7)],
    [(3, 9), (3, 5), (3, 12), (3, 12), (3, 15), (3, 12)],
    [(2, 15), (2, 10), (2, 8), (2, 7), (3, 10), (2, 8), (2, 12), (2, 12)],
    [(2, 13), (2, 15)],
    [(2, 13), (3, 14), (3, 5), (3, 7), (3, 5), (3, 4), (3, 5), (3, 7), (3, 5), (3, 7)],
    [(3, 7), (3, 7), (3, 5), (3, 7), (3, 7), (3, 7), (3, 5)],
    [(3, 4), (3, 5), (3, 4), (3, 5), (3, 7), (3, 5), (3, 7), (3, 12)],
    [(3, 9), (3, 10), (3, 12), (3, 10), (3, 9), (3, 10), (3, 9), (3, 10), (3, 12), (3, 10), (3, 12), (3, 13), (3, 10), (3, 12), (3, 13)],
    [(3, 10), (3, 12), (3, 13), (3, 12), (3, 10), (3, 12), (3, 10), (3, 12), (3, 10), (3, 9), (3, 12), (3, 10), (3, 9), (3, 7), (3, 5)],
    [(3, 7), (3, 5), (3, 5), (3, 7), (3, 5), (3, 5), (3, 7), (3, 5)],
    [(3, 5), (3, 8), (3, 5), (3, 5)],
]


SPANNERS = {
    2: [
        {"type": "bend", "from": "n1", "label": "full", "curve": "full"},
        {"type": "bend", "from": "n2", "label": "full", "curve": "release-from-full", "preBend": True},
        {"type": "vibrato", "from": "n5", "toEvent": "e5", "width": "normal"},
    ],
    3: [
        {"type": "bend", "from": "n1", "label": "full", "curve": "full"},
        {"type": "hammer-on", "from": "n3", "to": "n4", "label": "H"},
        {"type": "pull-off", "from": "n4", "to": "n5", "label": "P"},
        {"type": "vibrato", "from": "n6", "toEvent": "e6", "width": "normal"},
    ],
    4: [
        {"type": "bend", "from": "n1", "label": "full", "curve": "full"},
        {"type": "bend", "from": "n2", "label": "full", "curve": "release-from-full", "preBend": True},
        {"type": "vibrato", "from": "n5", "toEvent": "e5", "width": "normal"},
    ],
}


def event_item(raw):
    return raw.get("item") if isinstance(raw, dict) else raw


def duration_for_raw(raw, count: int) -> dict:
    if isinstance(raw, dict):
        return {"base": raw.get("base", 8), "dots": raw.get("dots", 0)}
    return duration_for_count(count)


def beam_for_raw(raw, measure_number: int, count: int):
    if isinstance(raw, dict):
        return raw.get("beam")
    return f"m{measure_number}b1" if count > 4 else None


def duration_ticks(duration: dict) -> int:
    base = duration["base"]
    ticks = PPQ * 4 // base
    if duration.get("dots"):
        ticks += ticks // 2
    return ticks


def duration_for_count(count: int) -> dict:
    if count <= 4:
        return {"base": 4, "dots": 0}
    if count <= 8:
        return {"base": 8, "dots": 0}
    return {"base": 16, "dots": 0}


def quantized_tick(index: int, count: int) -> int:
    if count <= 1:
        return 0
    raw = round(index * MEASURE_TICKS / count)
    return int(round(raw / 120) * 120)


def source_map(measure_number: int, confidence: float = 0.42) -> dict:
    return {
        "sourceId": SOURCE_ID,
        "page": None,
        "frame": None,
        "bbox": [0, 4, 1280, 266],
        "rawText": f"rough transcription from Douyin frame crops, measure {measure_number}",
        "confidence": confidence,
    }


def note_aliases_for_events(events: list[dict]) -> dict[str, str]:
    aliases = {}
    counter = 1
    for event in events:
        for note in event.get("notes", []):
            aliases[f"n{counter}"] = note["id"]
            counter += 1
    return aliases


def event_aliases_for_events(events: list[dict]) -> dict[str, str]:
    return {f"e{index}": event["id"] for index, event in enumerate(events, start=1)}


def bend_curve(kind: str) -> list[dict]:
    if kind == "release-from-full":
        return [{"at": 0, "alter": 2}, {"at": 0.5, "alter": 2}, {"at": 1, "alter": 0}]
    if kind in {"half", "1/2"}:
        return [{"at": 0, "alter": 0}, {"at": 0.7, "alter": 1}, {"at": 1, "alter": 1}]
    return [{"at": 0, "alter": 0}, {"at": 0.7, "alter": 2}, {"at": 1, "alter": 2}]


def make_spanner(measure_number: int, index: int, raw: dict, note_aliases: dict, event_aliases: dict) -> dict:
    spanner = {"id": f"m{measure_number}sp{index}", "type": raw["type"]}
    if "from" in raw:
        spanner["from"] = note_aliases.get(raw["from"], raw["from"])
    if "to" in raw:
        spanner["to"] = note_aliases.get(raw["to"], raw["to"])
    if "fromEvent" in raw:
        spanner["fromEvent"] = event_aliases.get(raw["fromEvent"], raw["fromEvent"])
    if "toEvent" in raw:
        spanner["toEvent"] = event_aliases.get(raw["toEvent"], raw["toEvent"])
    for key in ("label", "direction", "width"):
        if key in raw:
            spanner[key] = raw[key]

    if raw["type"] == "bend":
        spanner["placement"] = "above"
        spanner["line"] = "solid"
        spanner["curve"] = bend_curve(raw.get("curve", "full"))
        spanner["preBend"] = bool(raw.get("preBend", False))
        spanner["withBar"] = False
    elif raw["type"] == "slide":
        spanner["placement"] = "above"
        spanner["line"] = "solid"
        spanner["slideKind"] = raw.get("slideKind", "shift")
    elif raw["type"] in {"hammer-on", "pull-off", "tie", "slur"}:
        spanner["placement"] = "above"
        spanner["line"] = "solid"
        spanner.setdefault("label", None)
    elif raw["type"] == "vibrato":
        spanner["placement"] = "above"
        spanner["line"] = "wavy"
        spanner.setdefault("label", None)
    elif raw["type"] in {"let-ring", "palm-mute"}:
        spanner["placement"] = "above" if raw["type"] == "let-ring" else "below"
        spanner["line"] = "solid" if raw["type"] == "let-ring" else "dashed"
    return spanner


def make_event(measure_number: int, event_index: int, tick: int, raw_item, count: int) -> dict:
    event_id = f"m{measure_number}e{event_index}"
    item = event_item(raw_item)
    duration = duration_for_raw(raw_item, count)
    if item == "rest":
        return {
            "id": event_id,
            "type": "rest",
            "tick": tick,
            "duration": duration,
            "voice": 1,
            "display": None,
            "source": source_map(measure_number, 0.35),
        }

    raw_notes = item if isinstance(item, list) else [item]
    notes = []
    for note_index, (string, fret) in enumerate(raw_notes, start=1):
        notes.append(
            {
                "id": f"m{measure_number}n{event_index}_{note_index}",
                "string": string,
                "fret": fret,
                "display": str(fret),
                "pitch": None,
                "status": "normal",
                "leftHandFinger": None,
                "rightHandFinger": None,
                "effects": [],
                "confidence": 0.42,
            }
        )

    return {
        "id": event_id,
        "type": "note",
        "tick": tick,
        "duration": duration,
        "voice": 1,
        "beamGroup": beam_for_raw(raw_item, measure_number, count),
        "notes": notes,
        "articulations": [],
        "source": source_map(measure_number, 0.42),
    }


def make_measure(number: int, items: list) -> dict:
    events = []
    count = len(items)
    explicit_tick = 0
    has_explicit_durations = any(isinstance(item, dict) for item in items)
    for index, item in enumerate(items):
        tick = explicit_tick if has_explicit_durations else quantized_tick(index, count)
        events.append(make_event(number, index + 1, tick, item, count))
        if has_explicit_durations:
            explicit_tick += duration_ticks(duration_for_raw(item, count))
    note_aliases = note_aliases_for_events(events)
    event_aliases = event_aliases_for_events(events)
    spanners = [
        make_spanner(number, index, raw, note_aliases, event_aliases)
        for index, raw in enumerate(SPANNERS.get(number, []), start=1)
    ]
    return {
        "id": f"m{number}",
        "number": number,
        "trackId": TRACK_ID,
        "startTick": (number - 1) * MEASURE_TICKS,
        "durationTicks": MEASURE_TICKS,
        "attributes": {"clef": "TAB"} if number == 1 else {},
        "barline": {
            "left": "single",
            "right": "final" if number == len(MEASURES) else "single",
            "repeatStart": False,
            "repeatEnd": False,
            "repeatCount": None,
            "ending": None,
        },
        "events": events,
        "spanners": spanners,
        "directions": [
            {
                "id": f"m{number}d1",
                "tick": 0,
                "type": "instruction",
                "text": "抖音截图粗转录，待人工校正",
                "placement": "above",
                "source": source_map(number, 0.42),
            }
        ],
        "source": source_map(number, 0.42),
    }


def make_score() -> dict:
    measures = [make_measure(i, items) for i, items in enumerate(MEASURES, start=1)]
    systems = []
    for index in range(0, len(measures), 4):
        chunk = measures[index : index + 4]
        systems.append(
            {
                "id": f"sys-{len(systems) + 1}",
                "source": source_map(chunk[0]["number"], 0.35),
                "measureIds": [measure["id"] for measure in chunk],
            }
        )
    return {
        "schema": "rokid.music.tab-score",
        "schemaVersion": 1,
        "metadata": {"title": "真的爱你", "artist": "BEYOND"},
        "defaults": {
            "ppq": PPQ,
            "tempo": {"bpm": 75, "beatUnit": 4, "text": "Moderate"},
            "timeSignature": {"beats": 4, "beatType": 4, "symbol": "normal", "visible": True},
            "keySignature": {"fifths": 0, "mode": "major", "visible": False},
            "tuning": {
                "name": "Standard tuning",
                "capo": 0,
                "strings": [
                    {"number": 1, "pitch": "E4", "gauge": None},
                    {"number": 2, "pitch": "B3", "gauge": None},
                    {"number": 3, "pitch": "G3", "gauge": None},
                    {"number": 4, "pitch": "D3", "gauge": None},
                    {"number": 5, "pitch": "A2", "gauge": None},
                    {"number": 6, "pitch": "E2", "gauge": None},
                ],
            },
            "notation": {
                "staff": "tab",
                "showRhythm": True,
                "showStringLabels": False,
                "colorProfile": "rokid-green",
            },
        },
        "tracks": [
            {
                "id": TRACK_ID,
                "name": "Electric Guitar",
                "instrument": "electric-guitar",
                "midiProgram": 30,
                "stringCount": 6,
                "tuningRef": "defaults.tuning",
                "visible": True,
                "playback": {"muted": False, "solo": False},
            }
        ],
        "systems": systems,
        "measures": measures,
    }


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    out = root / "data" / "music" / "zhendeaini_douyin.tab.json"
    out.write_text(json.dumps(make_score(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
