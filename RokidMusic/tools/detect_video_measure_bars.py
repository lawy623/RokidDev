#!/usr/bin/env python3
"""Detect tab staff lines and measure barlines in cropped video score frames."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


def cluster(values: np.ndarray, max_gap: int = 2) -> list[tuple[int, int]]:
    if len(values) == 0:
        return []
    out = []
    start = prev = int(values[0])
    for raw in values[1:]:
        value = int(raw)
        if value - prev <= max_gap:
            prev = value
            continue
        out.append((start, prev))
        start = prev = value
    out.append((start, prev))
    return out


def centers(clusters: list[tuple[int, int]]) -> list[float]:
    return [(start + end) / 2 for start, end in clusters]


def detect_tab_lines(gray: np.ndarray) -> list[float]:
    h, _ = gray.shape
    lower = gray[int(h * 0.34) :, :]
    dark = lower < 190
    density = dark.mean(axis=1)
    rows = np.where(density > 0.13)[0] + int(h * 0.34)
    candidates = centers([item for item in cluster(rows, 2) if item[1] - item[0] <= 7])
    best = []
    for i in range(0, max(0, len(candidates) - 5)):
        window = candidates[i : i + 6]
        gaps = np.diff(window)
        if 8 <= float(np.median(gaps)) <= 24 and float(gaps.max() - gaps.min()) <= 7:
            best = window
            break
    return [round(v, 2) for v in best]


def detect_barlines(gray: np.ndarray, lines: list[float]) -> list[float]:
    if len(lines) != 6:
        return []
    top = max(0, int(min(lines)) - 7)
    bottom = min(gray.shape[0], int(max(lines)) + 8)
    region = gray[top:bottom, :]
    dark = region < 185
    density = dark.mean(axis=0)
    cols = np.where(density > 0.28)[0]
    bars = []
    for start, end in cluster(cols, 2):
        width = end - start + 1
        if width <= 8:
            x = (start + end) / 2
            # Barlines should touch most string lines, unlike note stems.
            local = dark[:, max(0, start - 1) : min(dark.shape[1], end + 2)]
            if local.mean() > 0.24:
                bars.append(x)
    return merge_close([round(v, 2) for v in bars], 10)


def merge_close(values: list[float], tolerance: float) -> list[float]:
    if not values:
        return []
    groups = [[values[0]]]
    for value in sorted(values)[1:]:
        if value - groups[-1][-1] <= tolerance:
            groups[-1].append(value)
        else:
            groups.append([value])
    return [round(float(np.mean(group)), 2) for group in groups]


def analyze_image(path: Path) -> dict:
    gray = np.asarray(Image.open(path).convert("L"))
    lines = detect_tab_lines(gray)
    bars = detect_barlines(gray, lines)
    measures = []
    for index, (left, right) in enumerate(zip(bars, bars[1:]), start=1):
        if right - left >= 32:
            measures.append({"index": index, "bbox": [left, min(lines) - 28, right - left, max(lines) - min(lines) + 56]})
    return {
        "source": str(path),
        "imageSize": [int(gray.shape[1]), int(gray.shape[0])],
        "stringLines": [{"string": i + 1, "y": y} for i, y in enumerate(lines)],
        "barlines": bars,
        "measureBoxes": measures,
    }


def write_debug(path: Path, data: dict, out: Path) -> None:
    image = Image.open(path).convert("RGB")
    draw = ImageDraw.Draw(image)
    lines = data["stringLines"]
    if lines:
        x0, x1 = 0, image.width
        for line in lines:
            y = line["y"]
            draw.line([x0, y, x1, y], fill=(255, 0, 0), width=1)
            draw.text((4, y - 10), str(line["string"]), fill=(255, 0, 0))
    for x in data["barlines"]:
        draw.line([x, 0, x, image.height], fill=(255, 128, 0), width=2)
    for measure in data["measureBoxes"]:
        x, y, w, h = measure["bbox"]
        draw.rectangle([x, y, x + w, y + h], outline=(0, 180, 255), width=2)
        draw.text((x + 3, y + 3), str(measure["index"]), fill=(0, 180, 255))
    image.save(out)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("images", nargs="+", type=Path)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()

    args.out_dir.mkdir(parents=True, exist_ok=True)
    summary = []
    for image in args.images:
        data = analyze_image(image)
        out_json = args.out_dir / f"{image.stem}.bars.json"
        out_debug = args.out_dir / f"{image.stem}.bars.debug.jpg"
        out_json.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        write_debug(image, data, out_debug)
        summary.append({"image": image.name, "barlines": data["barlines"], "measureCount": len(data["measureBoxes"])})
        print(f"{image.name}: bars={len(data['barlines'])} measures={len(data['measureBoxes'])}")
    (args.out_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
