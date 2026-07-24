#!/usr/bin/env python3
"""Detect candidate fret-number blobs inside tab staffs.

This is the pre-OCR stage. It removes staff lines, extracts connected
components, groups nearby digit components, maps each candidate to a string and
measure, and writes a debug overlay. Recognition is intentionally left as
`rawText: null` until a reliable classifier/OCR layer is added.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from skimage import measure, morphology

from map_symbols import cleaned_barlines, map_point


def load_structure(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def remove_staff_lines(mask: np.ndarray, system: dict, y0: int) -> np.ndarray:
    cleaned = mask.copy()
    for line in system["stringLines"]:
        y = int(round(line["y"])) - y0
        cleaned[max(0, y - 2) : min(cleaned.shape[0], y + 3), :] = False
    for x in cleaned_barlines(system):
        xi = int(round(x))
        # Remove vertical barlines in local crop coordinates later by caller offset.
    return cleaned


def system_crop_mask(gray: np.ndarray, system: dict) -> tuple[np.ndarray, tuple[int, int]]:
    x, y, w, h = system["bbox"]
    top = max(0, y - 16)
    bottom = min(gray.shape[0], y + h - 6)
    left = max(0, x)
    right = min(gray.shape[1], x + w)
    crop = gray[top:bottom, left:right]
    mask = crop < 150
    remove_long_runs(mask, axis="horizontal", min_len=70)
    remove_long_runs(mask, axis="vertical", min_len=45)
    mask = morphology.remove_small_objects(mask, min_size=6)
    return mask, (left, top)


def remove_long_runs(mask: np.ndarray, axis: str, min_len: int) -> None:
    """Remove long staff/bar lines without deleting short digit strokes."""
    if axis == "vertical":
        work = mask.T
    else:
        work = mask
    for row in work:
        starts = np.where(np.diff(np.r_[False, row, False].astype(np.int8)) == 1)[0]
        ends = np.where(np.diff(np.r_[False, row, False].astype(np.int8)) == -1)[0]
        for start, end in zip(starts, ends):
            if end - start >= min_len:
                row[start:end] = False


def components(mask: np.ndarray, offset: tuple[int, int]) -> list[dict]:
    labels = measure.label(mask, connectivity=2)
    props = measure.regionprops(labels)
    out = []
    ox, oy = offset
    for prop in props:
        y1, x1, y2, x2 = prop.bbox
        w = x2 - x1
        h = y2 - y1
        area = prop.area
        if not (3 <= w <= 28 and 7 <= h <= 34 and area >= 8):
            continue
        out.append(
            {
                "bbox": [x1 + ox, y1 + oy, w, h],
                "center": [x1 + ox + w / 2, y1 + oy + h / 2],
                "area": int(area),
            }
        )
    return out


def group_digit_components(comps: list[dict]) -> list[dict]:
    comps = sorted(comps, key=lambda c: (c["center"][1], c["center"][0]))
    groups: list[list[dict]] = []
    for comp in comps:
        cx, cy = comp["center"]
        placed = False
        for group in groups:
            gx1, gy1, gx2, gy2 = group_bbox(group)
            gyc = (gy1 + gy2) / 2
            if abs(cy - gyc) <= 8 and 0 <= cx - gx2 <= 10:
                group.append(comp)
                placed = True
                break
        if not placed:
            groups.append([comp])

    candidates = []
    for group in groups:
        x1, y1, x2, y2 = group_bbox(group)
        w = x2 - x1
        h = y2 - y1
        if 5 <= w <= 44 and 8 <= h <= 34:
            candidates.append(
                {
                    "bbox": [round(x1, 2), round(y1, 2), round(w, 2), round(h, 2)],
                    "center": [round((x1 + x2) / 2, 2), round((y1 + y2) / 2, 2)],
                    "componentCount": len(group),
                    "rawText": None,
                    "confidence": None,
                }
            )
    return candidates


def group_bbox(group: list[dict]) -> tuple[float, float, float, float]:
    xs = [c["bbox"][0] for c in group]
    ys = [c["bbox"][1] for c in group]
    x2s = [c["bbox"][0] + c["bbox"][2] for c in group]
    y2s = [c["bbox"][1] + c["bbox"][3] for c in group]
    return min(xs), min(ys), max(x2s), max(y2s)


def detect(image_path: Path, structure_path: Path) -> dict:
    structure = load_structure(structure_path)
    gray = np.asarray(Image.open(image_path).convert("L"))
    all_candidates = []
    for system in structure["systems"]:
        mask, offset = system_crop_mask(gray, system)
        comps = components(mask, offset)
        candidates = group_digit_components(comps)
        for candidate in candidates:
            cx, cy = candidate["center"]
            mapped = map_point(structure, cx, cy)
            if "error" in mapped:
                continue
            if mapped["stringDistancePx"] > 9:
                continue
            candidate.update(
                {
                    "systemId": mapped["systemId"],
                    "string": mapped["string"],
                    "measureInSystem": mapped["measureInSystem"],
                    "measureLocalX": mapped["measureLocalX"],
                }
            )
            all_candidates.append(candidate)

    return {
        "source": str(image_path),
        "structure": str(structure_path),
        "candidateCount": len(all_candidates),
        "candidates": all_candidates,
    }


def write_debug(image_path: Path, result: dict, out: Path) -> None:
    image = Image.open(image_path).convert("RGB")
    draw = ImageDraw.Draw(image)
    for index, candidate in enumerate(result["candidates"], start=1):
        x, y, w, h = candidate["bbox"]
        draw.rectangle([x, y, x + w, y + h], outline=(255, 0, 0), width=2)
        label = f"{index}:{candidate['systemId']}/m{candidate['measureInSystem']}/s{candidate['string']}"
        draw.text((x, y - 10), label, fill=(255, 0, 0))
    image.save(out)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("image", type=Path)
    parser.add_argument("--structure", type=Path)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--debug", type=Path)
    args = parser.parse_args()

    structure = args.structure or args.image.with_suffix(".structure.json")
    result = detect(args.image, structure)
    out = args.out or args.image.with_suffix(".fret_candidates.json")
    debug = args.debug or args.image.with_suffix(".fret_candidates.debug.png")
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_debug(args.image, result, debug)
    print(f"candidates: {result['candidateCount']}")
    print(f"wrote {out}")
    print(f"wrote {debug}")


if __name__ == "__main__":
    main()
