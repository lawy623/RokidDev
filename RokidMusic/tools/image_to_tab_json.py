#!/usr/bin/env python3
"""Convert a compact image transcription into RokidMusic tab-score JSON.

This is intentionally a semi-automatic bridge: OCR / vision tools should
produce the compact TRANSCRIPTION data, and this script expands it into the
renderer/schema-friendly JSON model.
"""

from __future__ import annotations

import json
from pathlib import Path


PPQ = 960
MEASURE_TICKS = PPQ * 4
TRACK_ID = "gtr1"
SOURCE_ID = "src-zhendeaini-jpg"


def duration(base: int, dots: int = 0) -> dict:
    return {"base": base, "dots": dots}


def note(
    tick: int,
    string: int,
    fret: int,
    base: int,
    *,
    dots: int = 0,
    beam: str | None = None,
    status: str = "normal",
) -> dict:
    display = f"({fret})" if status == "ghost" else str(fret)
    effects = [{"type": "ghost"}] if status == "ghost" else []
    return {
        "kind": "note",
        "tick": tick,
        "duration": duration(base, dots),
        "beamGroup": beam,
        "notes": [
            {
                "string": string,
                "fret": fret,
                "display": display,
                "pitch": None,
                "status": status,
                "leftHandFinger": None,
                "rightHandFinger": None,
                "effects": effects,
                "confidence": 0.9,
            }
        ],
    }


def rest(tick: int, base: int, *, dots: int = 0) -> dict:
    return {
        "kind": "rest",
        "tick": tick,
        "duration": duration(base, dots),
        "display": None,
    }


# String numbers follow tab convention: 1 is the top/high E line, 6 is bottom/low E.
# This is a geometry-assisted manual transcription from data/raw/zhendeaini.jpg.
TRANSCRIPTION = [
    {
        "number": 1,
        "source": [35, 294, 242, 66],
        "attributes": {"clef": "TAB"},
        "events": [
            rest(0, 4),
            rest(960, 8),
            note(1440, 3, 12, 8),
            note(1920, 2, 13, 8, beam="m1b1"),
            note(2400, 2, 15, 8, beam="m1b1"),
        ],
        "spanners": [],
    },
    {
        "number": 2,
        "source": [277, 294, 243, 66],
        "events": [
            note(0, 2, 15, 4),
            note(960, 2, 15, 8, beam="m2b1"),
            note(1440, 2, 13, 16, beam="m2b1"),
            note(1680, 2, 15, 16, beam="m2b1"),
            note(1920, 2, 15, 4, dots=1),
            note(3360, 2, 15, 8),
        ],
        "spanners": [
            {"type": "bend", "from": "n1", "label": "full", "curve": "full"},
            {"type": "bend", "from": "n2", "label": "full", "curve": "release-from-full", "preBend": True},
            {"type": "slide", "from": "n3", "to": "n4", "label": "sl.", "direction": "up"},
            {"type": "vibrato", "from": "n5", "toEvent": "e6", "width": "normal"},
            {"type": "bend", "from": "n6", "label": "full", "curve": "full"},
        ],
    },
    {
        "number": 3,
        "source": [520, 294, 300, 66],
        "events": [
            note(0, 2, 15, 8, beam="m3b1"),
            note(480, 2, 13, 8, beam="m3b1"),
            note(960, 2, 12, 16, beam="m3b2"),
            note(1200, 2, 13, 16, beam="m3b2"),
            note(1440, 2, 12, 8, beam="m3b2"),
            note(1920, 3, 14, 4),
            note(2880, 3, 14, 8, status="ghost", beam="m3b3"),
            note(3360, 2, 13, 16, beam="m3b3"),
            note(3600, 2, 15, 16, beam="m3b3"),
        ],
        "spanners": [
            {"type": "hammer-on", "from": "n3", "to": "n4", "label": "H"},
            {"type": "pull-off", "from": "n4", "to": "n5", "label": "P"},
            {"type": "tie", "from": "n6", "to": "n7"},
            {"type": "vibrato", "from": "n6", "to": "n8", "width": "wide"},
            {"type": "hammer-on", "from": "n8", "to": "n9", "label": "H"},
        ],
    },
    {
        "number": 4,
        "source": [821, 294, 292, 66],
        "events": [
            note(0, 2, 15, 4),
            note(960, 2, 15, 8, beam="m4b1"),
            note(1440, 2, 13, 16, beam="m4b1"),
            note(1680, 2, 15, 16, beam="m4b1"),
            note(1920, 2, 15, 4),
            note(2400, 2, 15, 16, beam="m4b2"),
            note(2640, 2, 13, 16, beam="m4b2"),
            note(2880, 2, 12, 16, beam="m4b2"),
            note(3120, 2, 13, 16, beam="m4b2"),
        ],
        "spanners": [
            {"type": "bend", "from": "n1", "label": "full", "curve": "full"},
            {"type": "bend", "from": "n2", "label": "full", "curve": "release-from-full", "preBend": True},
            {"type": "slide", "from": "n3", "to": "n4", "label": "sl.", "direction": "up"},
            {"type": "vibrato", "from": "n5", "toEvent": "e6", "width": "normal"},
        ],
    },
    {
        "number": 5,
        "source": [35, 477, 408, 66],
        "events": [
            note(0, 2, 13, 8, dots=1, beam="m5b1"),
            note(720, 3, 12, 16, beam="m5b1"),
            note(960, 2, 13, 16, beam="m5b2"),
            note(1200, 1, 15, 16, beam="m5b2"),
            note(1440, 2, 12, 16, beam="m5b2"),
            note(1680, 2, 13, 16, beam="m5b2"),
            note(1920, 1, 15, 8, beam="m5b3"),
            note(2400, 2, 13, 8, beam="m5b3"),
            note(2880, 2, 13, 8, beam="m5b4"),
            note(3360, 2, 15, 8, beam="m5b4"),
        ],
        "spanners": [
            {"type": "let-ring", "fromEvent": "e1", "toEvent": "e4", "label": "let ring"},
            {"type": "let-ring", "fromEvent": "e5", "toEvent": "e7", "label": "let ring"},
        ],
    },
    {
        "number": 6,
        "source": [443, 477, 296, 66],
        "events": [
            note(0, 2, 15, 4),
            note(960, 2, 15, 8, beam="m6b1"),
            note(1440, 2, 13, 16, beam="m6b1"),
            note(1680, 2, 15, 16, beam="m6b1"),
            note(1920, 2, 15, 4, dots=1),
            note(3360, 2, 15, 8),
        ],
        "spanners": [
            {"type": "bend", "from": "n1", "label": "full", "curve": "full"},
            {"type": "bend", "from": "n2", "label": "full", "curve": "release-from-full", "preBend": True},
            {"type": "slide", "from": "n3", "to": "n4", "label": "sl.", "direction": "up"},
            {"type": "vibrato", "from": "n5", "toEvent": "e6", "width": "normal"},
        ],
    },
    {
        "number": 7,
        "source": [740, 477, 372, 66],
        "events": [
            note(0, 2, 15, 8, beam="m7b1"),
            note(480, 2, 13, 8, beam="m7b1"),
            note(960, 2, 12, 16, beam="m7b2"),
            note(1200, 2, 13, 16, beam="m7b2"),
            note(1440, 2, 12, 8, beam="m7b2"),
            note(1920, 3, 14, 4),
            note(2880, 3, 14, 8, status="ghost", beam="m7b3"),
            note(3360, 2, 13, 16, beam="m7b3"),
            note(3600, 2, 15, 16, beam="m7b3"),
        ],
        "spanners": [
            {"type": "hammer-on", "from": "n3", "to": "n4", "label": "H"},
            {"type": "pull-off", "from": "n4", "to": "n5", "label": "P"},
            {"type": "tie", "from": "n6", "to": "n7"},
            {"type": "vibrato", "from": "n6", "to": "n8", "width": "wide"},
            {"type": "hammer-on", "from": "n8", "to": "n9", "label": "H"},
        ],
    },
    {
        "number": 8,
        "source": [35, 657, 302, 66],
        "events": [
            note(0, 2, 15, 4),
            note(960, 2, 15, 8, beam="m8b1"),
            note(1440, 2, 13, 16, beam="m8b1"),
            note(1680, 2, 15, 16, beam="m8b1"),
            note(1920, 2, 15, 4),
            note(2400, 2, 15, 16, beam="m8b2"),
            note(2640, 2, 13, 16, beam="m8b2"),
            note(2880, 2, 12, 16, beam="m8b2"),
            note(3120, 2, 13, 16, beam="m8b2"),
        ],
        "spanners": [
            {"type": "bend", "from": "n1", "label": "full", "curve": "full"},
            {"type": "bend", "from": "n2", "label": "full", "curve": "release-from-full", "preBend": True},
            {"type": "slide", "from": "n3", "to": "n4", "label": "sl.", "direction": "up"},
            {"type": "vibrato", "from": "n5", "toEvent": "e6", "width": "normal"},
            {"type": "pull-off", "from": "n6", "to": "n7", "label": "P"},
            {"type": "hammer-on", "from": "n8", "to": "n9", "label": "H"},
        ],
    },
    {
        "number": 9,
        "source": [337, 657, 229, 66],
        "events": [
            note(0, 2, 13, 4),
            note(960, 3, 12, 8, beam="m9b1"),
            note(1440, 3, 10, 8, beam="m9b1"),
            note(1920, 3, 9, 8, beam="m9b2"),
            note(2400, 4, 5, 8, beam="m9b2"),
            note(2880, 3, 5, 8, beam="m9b3"),
            note(3360, 3, 7, 8, beam="m9b3"),
        ],
        "spanners": [],
    },
    {
        "number": 10,
        "source": [567, 657, 284, 66],
        "events": [
            note(0, 3, 9, 8, beam="m10b1"),
            note(480, 3, 9, 16, beam="m10b1"),
            note(720, 3, 9, 16, beam="m10b1"),
            note(960, 3, 9, 16, beam="m10b2"),
            note(1200, 3, 7, 16, beam="m10b2"),
            note(1440, 3, 5, 16, beam="m10b2"),
            note(1680, 3, 7, 16, beam="m10b2"),
            note(1920, 3, 7, 4, dots=1),
            note(3360, 3, 7, 16, beam="m10b3"),
            note(3600, 3, 9, 16, beam="m10b3"),
        ],
        "spanners": [],
    },
    {
        "number": 11,
        "source": [852, 657, 259, 66],
        "events": [
            note(0, 3, 7, 8, beam="m11b0"),
            note(480, 3, 5, 8, beam="m11b0"),
            note(960, 3, 4, 16, beam="m11b1"),
            note(1200, 3, 5, 16, beam="m11b1"),
            note(1440, 3, 4, 8, beam="m11b1"),
            note(1920, 4, 7, 4, dots=1),
            note(2880, 3, 5, 16, beam="m11b2"),
            note(3120, 3, 7, 16, beam="m11b2"),
        ],
        "spanners": [],
    },
]


def make_score() -> dict:
    score = {
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
        "systems": [
            {
                "id": "sys-1",
                "source": source_map([35, 294, 1078, 66], 0.9),
                "measureIds": ["m1", "m2", "m3", "m4"],
            },
            {
                "id": "sys-2",
                "source": source_map([35, 477, 1078, 66], 0.85),
                "measureIds": ["m5", "m6", "m7"],
            },
            {
                "id": "sys-3",
                "source": source_map([35, 657, 1078, 66], 0.85),
                "measureIds": ["m8", "m9", "m10", "m11"],
            },
        ],
        "measures": [],
    }

    deferred = []
    for measure_data in TRANSCRIPTION:
        measure, deferred_spanners = make_measure(measure_data)
        score["measures"].append(measure)
        deferred.extend(deferred_spanners)

    resolve_deferred_spanners(score, deferred)
    return score


def source_map(bbox: list[int], confidence: float) -> dict:
    return {
        "sourceId": SOURCE_ID,
        "page": 1,
        "frame": None,
        "bbox": bbox,
        "rawText": None,
        "confidence": confidence,
    }


def make_measure(data: dict) -> tuple[dict, list[dict]]:
    number = data["number"]
    measure_id = f"m{number}"
    note_counter = 1
    events = []
    note_aliases = {}
    event_aliases = {}

    for event_counter, item in enumerate(data["events"], start=1):
        event_id = f"{measure_id}e{event_counter}"
        event_aliases[f"e{event_counter}"] = event_id
        if item["kind"] == "rest":
            events.append(
                {
                    "id": event_id,
                    "type": "rest",
                    "tick": item["tick"],
                    "duration": item["duration"],
                    "voice": 1,
                    "display": item["display"],
                }
            )
            continue

        notes = []
        for raw_note in item["notes"]:
            note_id = f"{measure_id}n{note_counter}"
            note_aliases[f"n{note_counter}"] = note_id
            note_counter += 1
            full_note = {"id": note_id, **raw_note}
            notes.append(full_note)
        events.append(
            {
                "id": event_id,
                "type": "note",
                "tick": item["tick"],
                "duration": item["duration"],
                "voice": 1,
                "beamGroup": item.get("beamGroup"),
                "notes": notes,
                "articulations": [],
            }
        )

    spanners = []
    deferred = []
    for sp_counter, raw_spanner in enumerate(data.get("spanners", []), start=1):
        spanner = build_spanner(f"{measure_id}sp{sp_counter}", raw_spanner, note_aliases, event_aliases)
        if raw_spanner.get("defer"):
            deferred.append({"measureId": measure_id, "spanner": spanner})
        else:
            spanners.append(spanner)

    measure = {
        "id": measure_id,
        "number": number,
        "trackId": TRACK_ID,
        "startTick": (number - 1) * MEASURE_TICKS,
        "durationTicks": MEASURE_TICKS,
        "attributes": data.get("attributes", {}),
        "barline": {
            "left": "single",
            "right": "single",
            "repeatStart": False,
            "repeatEnd": False,
            "repeatCount": None,
            "ending": None,
        },
        "events": events,
        "spanners": spanners,
        "directions": [],
        "source": source_map(data["source"], 0.85),
    }
    return measure, deferred


def build_spanner(spanner_id: str, raw: dict, note_aliases: dict, event_aliases: dict) -> dict:
    spanner = {"id": spanner_id, "type": raw["type"]}
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
        spanner["slideKind"] = "shift"
    elif raw["type"] in {"hammer-on", "pull-off", "tie"}:
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


def bend_curve(kind: str) -> list[dict]:
    if kind == "release-from-full":
        return [{"at": 0, "alter": 2}, {"at": 0.5, "alter": 2}, {"at": 1, "alter": 0}]
    if kind in {"half", "1/2"}:
        return [{"at": 0, "alter": 0}, {"at": 0.7, "alter": 1}, {"at": 1, "alter": 1}]
    if kind in {"quarter", "1/4"}:
        return [{"at": 0, "alter": 0}, {"at": 0.7, "alter": 0.5}, {"at": 1, "alter": 0.5}]
    return [{"at": 0, "alter": 0}, {"at": 0.7, "alter": 2}, {"at": 1, "alter": 2}]


def resolve_deferred_spanners(score: dict, deferred: list[dict]) -> None:
    if not deferred:
        return
    first_note_by_measure = {}
    for measure in score["measures"]:
        for event in measure["events"]:
            if event["type"] == "note":
                first_note_by_measure[measure["id"]] = event["notes"][0]["id"]
                break

    measures = {measure["id"]: measure for measure in score["measures"]}
    for item in deferred:
        measure = measures[item["measureId"]]
        spanner = item["spanner"]
        target = spanner.get("to")
        if target == "n1_next":
            current_number = measure["number"]
            next_measure = f"m{current_number + 1}"
            if next_measure in first_note_by_measure:
                spanner["to"] = first_note_by_measure[next_measure]
                measure["spanners"].append(spanner)


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    out = root / "data" / "music" / "zhendeaini_intro.tab.json"
    score = make_score()
    out.write_text(json.dumps(score, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
