#!/usr/bin/env python3
"""Detect tab systems, string lines, and barline candidates in a score image."""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


@dataclass(frozen=True)
class Cluster:
    start: int
    end: int
    score: float

    @property
    def center(self) -> float:
        return (self.start + self.end) / 2


def load_grayscale(path: Path) -> np.ndarray:
    image = Image.open(path).convert("L")
    return np.asarray(image)


def dark_mask(gray: np.ndarray, threshold: int = 150) -> np.ndarray:
    return gray < threshold


def cluster_indices(indices: np.ndarray, values: np.ndarray | None = None, max_gap: int = 1) -> list[Cluster]:
    if len(indices) == 0:
        return []
    clusters: list[Cluster] = []
    start = int(indices[0])
    prev = int(indices[0])
    scores: list[float] = []
    if values is not None:
        scores.append(float(values[indices[0]]))

    for raw_idx in indices[1:]:
        idx = int(raw_idx)
        if idx - prev <= max_gap:
            prev = idx
            if values is not None:
                scores.append(float(values[idx]))
            continue
        score = float(np.mean(scores)) if scores else 0.0
        clusters.append(Cluster(start, prev, score))
        start = prev = idx
        scores = [float(values[idx])] if values is not None else []

    score = float(np.mean(scores)) if scores else 0.0
    clusters.append(Cluster(start, prev, score))
    return clusters


def detect_horizontal_lines(mask: np.ndarray) -> list[Cluster]:
    h, w = mask.shape
    row_density = mask.mean(axis=1)
    candidates = np.where(row_density > 0.34)[0]
    clusters = cluster_indices(candidates, row_density, max_gap=1)
    lines = [cluster for cluster in clusters if cluster.end - cluster.start <= 4 and cluster.score > 0.34]
    return lines


def group_systems(lines: list[Cluster]) -> list[list[Cluster]]:
    systems: list[list[Cluster]] = []
    i = 0
    while i <= len(lines) - 6:
        window = lines[i : i + 6]
        centers = np.array([line.center for line in window])
        gaps = np.diff(centers)
        if 8 <= float(np.median(gaps)) <= 18 and float(np.max(gaps) - np.min(gaps)) <= 5:
            systems.append(window)
            i += 6
        else:
            i += 1
    return systems


def detect_barlines(mask: np.ndarray, system: list[Cluster]) -> list[float]:
    top = max(0, int(system[0].center) - 6)
    bottom = min(mask.shape[0], int(system[-1].center) + 7)
    region = mask[top:bottom, :]
    col_density = region.mean(axis=0)
    candidates = np.where(col_density > 0.45)[0]
    clusters = cluster_indices(candidates, col_density, max_gap=2)
    xs = []
    for cluster in clusters:
        width = cluster.end - cluster.start + 1
        if width <= 7 and cluster.score > 0.45:
            xs.append(cluster.center)
    return merge_close(xs, tolerance=8)


def merge_close(values: list[float], tolerance: float) -> list[float]:
    if not values:
        return []
    values = sorted(values)
    groups: list[list[float]] = [[values[0]]]
    for value in values[1:]:
        if value - groups[-1][-1] <= tolerance:
            groups[-1].append(value)
        else:
            groups.append([value])
    return [float(np.mean(group)) for group in groups]


def system_bbox(system: list[Cluster], barlines: list[float], image_width: int) -> list[int]:
    left = int(max(0, min(barlines) if barlines else 0))
    right = int(min(image_width - 1, max(barlines) if barlines else image_width - 1))
    top = int(max(0, system[0].center - 10))
    bottom = int(system[-1].center + 10)
    return [left, top, right - left, bottom - top]


def build_structure(path: Path) -> dict:
    gray = load_grayscale(path)
    mask = dark_mask(gray)
    lines = detect_horizontal_lines(mask)
    systems = group_systems(lines)

    result = {
        "source": str(path),
        "imageSize": [int(gray.shape[1]), int(gray.shape[0])],
        "systems": [],
    }

    for sys_index, system in enumerate(systems, start=1):
        barlines = detect_barlines(mask, system)
        result["systems"].append(
            {
                "id": f"sys-{sys_index}",
                "bbox": system_bbox(system, barlines, gray.shape[1]),
                "stringLines": [
                    {"string": string, "y": round(float(line.center), 2)}
                    for string, line in enumerate(system, start=1)
                ],
                "barlines": [round(x, 2) for x in barlines],
            }
        )

    return result


def write_debug_image(source: Path, structure: dict, out: Path) -> None:
    image = Image.open(source).convert("RGB")
    draw = ImageDraw.Draw(image)
    colors = [(255, 0, 0), (0, 140, 255), (0, 180, 0), (180, 0, 255)]

    for index, system in enumerate(structure["systems"]):
        color = colors[index % len(colors)]
        x, y, w, h = system["bbox"]
        draw.rectangle([x, y, x + w, y + h], outline=color, width=2)
        for line in system["stringLines"]:
            ly = line["y"]
            draw.line([x, ly, x + w, ly], fill=color, width=1)
            draw.text((x + 4, ly - 10), str(line["string"]), fill=color)
        for bx in system["barlines"]:
            draw.line([bx, y, bx, y + h], fill=(255, 128, 0), width=1)

    image.save(out)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("image", type=Path)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--debug", type=Path)
    args = parser.parse_args()

    structure = build_structure(args.image)
    out = args.out or args.image.with_suffix(".structure.json")
    debug = args.debug or args.image.with_suffix(".structure.debug.png")
    out.write_text(json.dumps(structure, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_debug_image(args.image, structure, debug)
    print(f"systems: {len(structure['systems'])}")
    print(f"wrote {out}")
    print(f"wrote {debug}")


if __name__ == "__main__":
    main()
