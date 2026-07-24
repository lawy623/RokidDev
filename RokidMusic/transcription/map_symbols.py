#!/usr/bin/env python3
"""Map detected symbol coordinates to tab string and measure positions."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def load_structure(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def cleaned_barlines(system: dict) -> list[float]:
    """Drop clef/time-signature helper lines before measure 1 when present."""
    xs = list(system.get("barlines", []))
    if len(xs) >= 3 and xs[1] - xs[0] < 60:
        xs.pop(1)
    return xs


def find_system(structure: dict, x: float, y: float) -> dict | None:
    for system in structure.get("systems", []):
        sx, sy, sw, sh = system["bbox"]
        if sx <= x <= sx + sw and sy <= y <= sy + sh:
            return system
    return None


def nearest_string(system: dict, y: float) -> tuple[int, float]:
    lines = system["stringLines"]
    nearest = min(lines, key=lambda line: abs(float(line["y"]) - y))
    return int(nearest["string"]), abs(float(nearest["y"]) - y)


def measure_index(system: dict, x: float) -> tuple[int | None, float | None, float | None]:
    bars = cleaned_barlines(system)
    if len(bars) < 2:
        return None, None, None
    for index, (left, right) in enumerate(zip(bars, bars[1:]), start=1):
        if left <= x <= right:
            local = (x - left) / max(1.0, right - left)
            return index, left, local
    return None, None, None


def map_point(structure: dict, x: float, y: float) -> dict:
    system = find_system(structure, x, y)
    if not system:
        return {"x": x, "y": y, "error": "point outside detected tab systems"}
    string, string_distance = nearest_string(system, y)
    measure, measure_left, measure_local = measure_index(system, x)
    return {
        "x": x,
        "y": y,
        "systemId": system["id"],
        "string": string,
        "stringDistancePx": round(string_distance, 2),
        "measureInSystem": measure,
        "measureLeftX": measure_left,
        "measureLocalX": None if measure_local is None else round(measure_local, 4),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("structure", type=Path)
    parser.add_argument("points", nargs="*", help="points as x,y")
    args = parser.parse_args()

    structure = load_structure(args.structure)
    mapped = []
    for point in args.points:
        x_raw, y_raw = point.split(",", 1)
        mapped.append(map_point(structure, float(x_raw), float(y_raw)))
    print(json.dumps(mapped, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
