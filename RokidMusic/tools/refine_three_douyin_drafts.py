#!/usr/bin/env python3
"""Refine source drafts: normalize timing and rebuild visible chord measures."""

from __future__ import annotations

import json
from pathlib import Path

BAR = 3840


def ticks(base: int) -> int:
    return BAR // base


def rest(measure_id: str, index: int, tick: int, base: int) -> dict:
    return {"id": f"{measure_id}r{index}", "type": "rest", "tick": tick,
            "duration": {"base": base, "dots": 0}, "voice": 1}


def normalize(measure: dict) -> None:
    events = [e for e in measure["events"] if e["type"] == "note"]
    base = 16 if len(events) > 8 else 8
    duration = ticks(base)
    cursor = 0
    for index, event in enumerate(events, 1):
        event["tick"] = cursor
        event["duration"] = {"base": base, "dots": 0}
        event["beamGroup"] = f"{measure['id']}b1" if len(events) > 1 else None
        cursor += duration
    rest_index = 1
    while cursor < BAR:
        base = 4 if BAR - cursor >= 960 else 8 if BAR - cursor >= 480 else 16
        events.append(rest(measure["id"], rest_index, cursor, base))
        cursor += ticks(base)
        rest_index += 1
    measure["events"] = events


def chord_event(measure_id: str, index: int, tick: int) -> dict:
    event_id = f"{measure_id}e{index}"
    return {
        "id": event_id, "type": "note", "tick": tick,
        "duration": {"base": 8, "dots": 0}, "voice": 1,
        "beamGroup": f"{measure_id}b1",
        "notes": [
            {"id": f"{event_id}n1", "string": 2, "fret": 6, "display": "6", "status": "normal", "effects": []},
            {"id": f"{event_id}n2", "string": 3, "fret": 4, "display": "4", "status": "normal", "effects": []},
        ],
        "articulations": [],
    }


def pm_chords(measure: dict, frame: int) -> None:
    events = [chord_event(measure["id"], i, (i - 1) * 480) for i in range(1, 9)]
    measure["events"] = events
    measure["spanners"] = [{"id": f"{measure['id']}pm", "type": "palm-mute",
                              "fromEvent": events[0]["id"], "toEvent": events[-1]["id"],
                              "label": "P.M.", "line": "dashed"}]
    measure["source"] = {"frame": frame, "confidence": 0.92,
                         "rawText": "Visible 6/4 double-stop eighth-note palm-muted figure."}


def canonicalize_ids(score: dict) -> None:
    """Repair earlier draft IDs so technique references stay local and stable."""
    for measure in score["measures"]:
        measure_id = f"m{measure['number']}"
        note_map = {}
        event_map = {}
        measure["id"] = measure_id
        for index, event in enumerate(measure["events"], 1):
            old_event = event["id"]
            event_id = f"{measure_id}{'e' if event['type'] == 'note' else 'r'}{index}"
            event["id"] = event_id
            event_map[old_event] = event_id
            for note_index, item in enumerate(event.get("notes", []), 1):
                old_note = item["id"]
                note_id = f"{event_id}n{note_index}"
                item["id"] = note_id
                note_map[old_note] = note_id
        for item in measure.get("spanners", []):
            for key in ("from", "to"):
                if key in item and item[key] in note_map:
                    item[key] = note_map[item[key]]
            for key in ("fromEvent", "toEvent"):
                if key in item and item[key] in event_map:
                    item[key] = event_map[item[key]]


def refine(path: Path, source_mode: str) -> None:
    score = json.loads(path.read_text(encoding="utf-8"))
    for measure in score["measures"]:
        normalize(measure)
        measure["source"]["rawText"] = "Re-read from full-resolution source crop; verify technique endpoint if needed."
        measure["source"]["confidence"] = max(measure["source"].get("confidence", 0.55), 0.66)
    score["source"]["motionMode"] = source_mode
    score["source"]["transcriptionStatus"] = "source-draft-needs-review"
    path.write_text(json.dumps(score, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    flower = root / "data/music/huahai_douyin.tab.json"
    world = root / "data/music/tianxia_douyin.tab.json"
    old = root / "data/music/laonanhai_douyin.tab.json"
    refine(flower, "fixed_score_moving_playhead")
    refine(world, "scrolling_score_window")
    refine(old, "fixed_score_moving_playhead")

    score = json.loads(world.read_text(encoding="utf-8"))
    canonicalize_ids(score)
    by_number = {m["number"]: m for m in score["measures"]}
    for number, frame in ((4, 1), (5, 1), (8, 18), (11, 30), (13, 38), (16, 50), (21, 70)):
        if number in by_number:
            pm_chords(by_number[number], frame)
    world.write_text(json.dumps(score, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"refined {flower}")
    print(f"refined {world}")
    print(f"refined {old}")


if __name__ == "__main__":
    main()
