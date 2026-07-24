#!/usr/bin/env python3
"""Build a de-duplicated measure map from video score frames.

The identity of a score segment is its printed measure number above the start
barline, not the changing playback highlight or the raw frame timestamp.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import subprocess
import tempfile
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image, ImageEnhance, ImageFilter


def sharpness(image: Image.Image) -> float:
    arr = np.asarray(image.convert("L"), dtype=np.float32)
    if arr.shape[0] < 3 or arr.shape[1] < 3:
        return 0.0
    laplacian = -4 * arr[1:-1, 1:-1] + arr[:-2, 1:-1] + arr[2:, 1:-1] + arr[1:-1, :-2] + arr[1:-1, 2:]
    return round(float(np.var(laplacian)), 2)


def number_band(image: Image.Image) -> Image.Image:
    """Enhance the rail where measure numbers are printed above TAB barlines."""
    width, height = image.size
    # Most imported score videos put numbers in the upper 8-45% of a crop.
    band = image.crop((0, int(height * 0.04), width, int(height * 0.48))).convert("L")
    band = ImageEnhance.Contrast(band).enhance(2.8)
    band = band.resize((band.width * 3, band.height * 3), Image.Resampling.LANCZOS)
    arr = np.asarray(band)
    binary = np.where(arr < 175, 0, 255).astype(np.uint8)
    return Image.fromarray(binary).filter(ImageFilter.MedianFilter(3))


def tesseract_numbers(image: Image.Image) -> list[dict]:
    with tempfile.TemporaryDirectory(prefix="rokid-measure-ocr-") as tmp:
        source = Path(tmp) / "numbers.png"
        output = Path(tmp) / "out"
        image.save(source)
        cmd = [
            "tesseract", str(source), str(output), "--psm", "11",
            "-c", "tessedit_char_whitelist=0123456789",
            "tsv",
        ]
        subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        tsv = output.with_suffix(".tsv")
        if not tsv.exists():
            return []
        tokens = []
        with tsv.open(encoding="utf-8") as handle:
            for row in csv.DictReader(handle, delimiter="\t"):
                text = (row.get("text") or "").strip()
                if not re.fullmatch(r"\d{1,3}", text):
                    continue
                try:
                    confidence = float(row.get("conf", "-1"))
                except ValueError:
                    continue
                if confidence < 25:
                    continue
                tokens.append({
                    "number": int(text),
                    "confidence": round(confidence, 1),
                    "bbox": [int(row["left"]) // 3, int(row["top"]) // 3,
                             int(row["width"]) // 3, int(row["height"]) // 3],
                })
        return tokens


def candidates_for_frame(path: Path) -> dict:
    image = Image.open(path).convert("RGB")
    tokens = tesseract_numbers(number_band(image))
    # Fret numbers and tempo digits can also be OCR'd. Tiny tokens along the
    # lower TAB rail are rejected; repeated candidates are resolved later from
    # their sequence and source-frame overlap.
    h = image.height
    gray = np.asarray(image.convert("L"))
    for item in tokens:
        x, _, width, _ = item["bbox"]
        center = x + width // 2
        left = max(0, center - 24)
        right = min(gray.shape[1], center + 25)
        staff_band = gray[int(h * 0.38) : int(h * 0.9), left:right] < 170
        item["barlineInk"] = round(float(staff_band.mean()), 3)
    tokens = [
        item for item in tokens
        if item["bbox"][1] < h * 0.48
        and item["number"] < 200
        # Printed measure numbers are compact. Large OCR boxes are usually
        # grouped fret/technique symbols, especially at a scrolling edge.
        and item["bbox"][2] <= 42
        and item["bbox"][3] <= 35
    ]
    return {"frame": path.name, "sharpness": sharpness(image), "numbers": tokens}


def build_map(frames: list[Path]) -> dict:
    observations = [candidates_for_frame(path) for path in frames]
    occurrences: dict[int, list[dict]] = defaultdict(list)
    for observation in observations:
        for token in observation["numbers"]:
            # 0 is normally an open-string fret, not a printed measure number.
            if token["number"] > 0:
                occurrences[token["number"]].append({
                    "frame": observation["frame"],
                    "sharpness": observation["sharpness"],
                    "ocrConfidence": token["confidence"],
                    "bbox": token["bbox"],
                })
    raw_measures = []
    for number, seen in sorted(occurrences.items()):
        best = max(seen, key=lambda item: (item["ocrConfidence"], item["sharpness"]))
        raw_measures.append({"number": number, "best": best, "occurrences": seen})

    # Printed measure numbers should form one mostly-contiguous ascending chain.
    # This filters OCR of fret numbers such as a stray 71 while allowing a small
    # number of unreadable measures between otherwise consecutive labels.
    chains: list[list[dict]] = []
    for item in raw_measures:
        if not chains or item["number"] - chains[-1][-1]["number"] > 3:
            chains.append([item])
        else:
            chains[-1].append(item)
    accepted = max(chains, key=len, default=[])
    accepted_numbers = {item["number"] for item in accepted}
    outliers = [item for item in raw_measures if item["number"] not in accepted_numbers]
    return {
        "frameCount": len(frames),
        "measures": accepted,
        "outliers": outliers,
        "frames": observations,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("frames", nargs="+", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--stride", type=int, default=1,
                        help="Inspect every Nth frame; use 5 for an initial video pass.")
    args = parser.parse_args()
    if args.stride < 1:
        raise SystemExit("--stride must be at least 1")
    result = build_map(args.frames[::args.stride])
    result["warning"] = (
        "OCR numbers are candidates only. A measure is trusted only when its number, "
        "start barline, and neighboring sequence agree. Frames without a trusted number "
        "must stay available for manual review rather than becoming new measures."
    )
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"frames={result['frameCount']} numberedMeasures={len(result['measures'])}")
    print(args.out)


if __name__ == "__main__":
    main()
