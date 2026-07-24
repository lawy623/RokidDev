#!/usr/bin/env python3
"""Build a review draft tab-score JSON from the Douyin score-frame set.

The first eleven measures reuse the calibrated transcription from
`zhendeaini_intro.tab.json`. Later measures are intentionally marked as
pending so they can be corrected measure by measure without losing the source
frame references.
"""

from __future__ import annotations

import json
from copy import deepcopy
from pathlib import Path


PPQ = 960
MEASURE_TICKS = PPQ * 4
TRACK_ID = "gtr1"
SOURCE_ID = "src-douyin-7379895619067858213"
TOTAL_MEASURES = 34


PENDING_SOURCE_BY_MEASURE = {
    12: ("score_0005_frame_00070.jpg", 70),
    13: ("score_0005_frame_00070.jpg", 70),
    14: ("score_0006_frame_00076.jpg", 76),
    15: ("score_0006_frame_00076.jpg", 76),
    16: ("score_0007_frame_00095.jpg", 95),
    17: ("score_0007_frame_00095.jpg", 95),
    18: ("score_0007_frame_00095.jpg", 95),
    19: ("score_0008_frame_00114.jpg", 114),
    20: ("score_0008_frame_00114.jpg", 114),
    21: ("score_0008_frame_00114.jpg", 114),
    22: ("score_0009_frame_00132.jpg", 132),
    23: ("score_0009_frame_00132.jpg", 132),
    24: ("score_0009_frame_00132.jpg", 132),
    25: ("score_0010_frame_00152.jpg", 152),
    26: ("score_0010_frame_00152.jpg", 152),
    27: ("score_0010_frame_00152.jpg", 152),
    28: ("score_0010_frame_00152.jpg", 152),
    29: ("score_0011_frame_00189.jpg", 189),
    30: ("score_0011_frame_00189.jpg", 189),
    31: ("score_0011_frame_00189.jpg", 189),
    32: ("score_0011_frame_00189.jpg", 189),
    33: ("score_0012_frame_00208.jpg", 208),
    34: ("score_0012_frame_00208.jpg", 208),
}


def source_map(raw_text: str, frame: int | None = None, confidence: float = 0.2) -> dict:
    return {
        "sourceId": SOURCE_ID,
        "page": None,
        "frame": frame,
        "bbox": [0, 4, 1280, 266],
        "rawText": raw_text,
        "confidence": confidence,
    }


def pending_measure(number: int) -> dict:
    filename, frame = PENDING_SOURCE_BY_MEASURE.get(number, ("unassigned", None))
    measure_id = f"m{number}"
    return {
        "id": measure_id,
        "number": number,
        "trackId": TRACK_ID,
        "startTick": (number - 1) * MEASURE_TICKS,
        "durationTicks": MEASURE_TICKS,
        "attributes": {},
        "barline": {
            "left": "single",
            "right": "single" if number != TOTAL_MEASURES else "final",
            "repeatStart": False,
            "repeatEnd": False,
            "repeatCount": None,
            "ending": None,
        },
        "events": [
            {
                "id": f"{measure_id}e1",
                "type": "rest",
                "tick": 0,
                "duration": {"base": 1, "dots": 0},
                "voice": 1,
                "display": None,
                "source": source_map(f"pending transcription from {filename}", frame, 0.1),
            }
        ],
        "spanners": [],
        "directions": [
            {
                "id": f"{measure_id}d1",
                "tick": 0,
                "type": "instruction",
                "text": f"待转录: {filename}",
                "placement": "above",
                "source": source_map(f"pending transcription from {filename}", frame, 0.1),
            }
        ],
        "source": source_map(f"pending transcription from {filename}", frame, 0.1),
    }


def renumber_existing_measure(measure: dict) -> dict:
    out = deepcopy(measure)
    number = out["number"]
    out["startTick"] = (number - 1) * MEASURE_TICKS
    return out


def rebuild_systems(measures: list[dict]) -> list[dict]:
    systems = []
    for index in range(0, len(measures), 4):
        chunk = measures[index : index + 4]
        system_number = len(systems) + 1
        first = chunk[0]["number"]
        last = chunk[-1]["number"]
        systems.append(
            {
                "id": f"sys-{system_number}",
                "source": source_map(f"review system measures {first}-{last}", None, 0.2),
                "measureIds": [measure["id"] for measure in chunk],
            }
        )
    return systems


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    intro_path = root / "data" / "music" / "zhendeaini_intro.tab.json"
    out_path = root / "data" / "music" / "zhendeaini_douyin_draft.tab.json"

    score = json.loads(intro_path.read_text(encoding="utf-8"))
    score["metadata"] = {"title": "真的爱你（抖音转录草稿）", "artist": "BEYOND"}
    score["defaults"]["tempo"] = {"bpm": 75, "beatUnit": 4, "text": "Moderate"}

    measures = [renumber_existing_measure(measure) for measure in score["measures"]]
    existing_numbers = {measure["number"] for measure in measures}
    for number in range(1, TOTAL_MEASURES + 1):
        if number not in existing_numbers:
            measures.append(pending_measure(number))
    measures.sort(key=lambda measure: measure["number"])

    score["measures"] = measures
    score["systems"] = rebuild_systems(measures)

    out_path.write_text(json.dumps(score, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out_path}")


if __name__ == "__main__":
    main()
