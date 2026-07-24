#!/usr/bin/env python3
"""Extract score frames from a video and deduplicate near-identical tabs.

This is a first-pass helper for Douyin/Youtube guitar-tab videos where the
score is a mostly white strip at the top of the frame and a colored play
highlight moves across it. Deduplication intentionally hashes only dark ink so
the moving yellow highlight does not make every frame look unique.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


@dataclass(frozen=True)
class Crop:
    x: int
    y: int
    w: int
    h: int

    def as_box(self) -> tuple[int, int, int, int]:
        return (self.x, self.y, self.x + self.w, self.y + self.h)

    def as_list(self) -> list[int]:
        return [self.x, self.y, self.w, self.h]


def run_ffmpeg(video: Path, frames_dir: Path, fps: float) -> None:
    frames_dir.mkdir(parents=True, exist_ok=True)
    if any(frames_dir.glob("frame_*.jpg")):
        return
    cmd = [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        str(video),
        "-vf",
        f"fps={fps}",
        str(frames_dir / "frame_%05d.jpg"),
    ]
    subprocess.run(cmd, check=True)


def detect_score_crop(image: Image.Image) -> Crop:
    gray = np.asarray(image.convert("L"))
    h, w = gray.shape
    top_limit = int(h * 0.48)
    dark = gray[:top_limit, :] < 185
    row_density = dark.mean(axis=1)

    # Long staff/string lines create strong, repeated horizontal dark rows.
    line_rows = np.where(row_density > 0.18)[0]
    clusters = [cluster for cluster in cluster_rows(line_rows) if cluster[1] - cluster[0] <= 8]
    centers = [(start + end) / 2 for start, end in clusters]
    for i in range(0, max(0, len(centers) - 5)):
        window = centers[i : i + 6]
        gaps = np.diff(window)
        if 8 <= float(np.median(gaps)) <= 22 and float(gaps.max() - gaps.min()) <= 6:
            first_line = int(round(window[0]))
            last_line = int(round(window[-1]))
            y0 = max(0, first_line - 150)
            y1 = min(h, last_line + 42)
            return Crop(0, y0, w, y1 - y0)

    arr = np.asarray(image.convert("RGB"))
    channel_spread = arr.max(axis=2) - arr.min(axis=2)
    paper = (arr[:, :, 0] > 210) & (arr[:, :, 1] > 210) & (arr[:, :, 2] > 210) & (channel_spread < 38)
    paper_ratio = paper.mean(axis=1)
    candidates = np.where(paper_ratio[:top_limit] > 0.36)[0]
    if len(candidates) == 0:
        return Crop(0, 0, w, int(h * 0.34))
    return Crop(0, 0, w, min(h, int(candidates.max()) + 10))


def cluster_rows(rows: np.ndarray) -> list[tuple[int, int]]:
    if len(rows) == 0:
        return []
    clusters: list[tuple[int, int]] = []
    start = prev = int(rows[0])
    for raw in rows[1:]:
        row = int(raw)
        if row - prev <= 2:
            prev = row
            continue
        clusters.append((start, prev))
        start = prev = row
    clusters.append((start, prev))
    return clusters


def ink_hash(image: Image.Image, crop: Crop, width: int = 128, height: int = 32) -> int:
    cropped = image.crop(crop.as_box()).convert("L")
    small = cropped.resize((width, height), Image.Resampling.BILINEAR)
    arr = np.asarray(small)
    ink = arr < 165
    bits = ink.reshape(-1)
    value = 0
    for bit in bits:
        value = (value << 1) | int(bool(bit))
    return value


def hamming(a: int, b: int) -> int:
    return (a ^ b).bit_count()


def copy_crop(src: Path, crop: Crop, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    image = Image.open(src).convert("RGB")
    image.crop(crop.as_box()).save(dst, quality=94)


def write_debug_crop(src: Path, crop: Crop, dst: Path) -> None:
    image = Image.open(src).convert("RGB")
    draw = ImageDraw.Draw(image)
    x, y, w, h = crop.as_list()
    draw.rectangle([x, y, x + w, y + h], outline=(255, 0, 0), width=3)
    image.save(dst, quality=92)


def dedup_frames(frames: list[Path], out_dir: Path, threshold: int, fps: float) -> dict:
    unique_dir = out_dir / "dedup"
    crop_dir = out_dir / "score_crops"
    if unique_dir.exists():
        shutil.rmtree(unique_dir)
    if crop_dir.exists():
        shutil.rmtree(crop_dir)
    unique_dir.mkdir(parents=True, exist_ok=True)
    crop_dir.mkdir(parents=True, exist_ok=True)

    kept: list[dict] = []
    seen_hashes: list[int] = []
    frame_infos: list[dict] = []

    first_crop = detect_score_crop(Image.open(frames[0]).convert("RGB"))
    write_debug_crop(frames[0], first_crop, out_dir / "detected_score_crop.jpg")

    for index, frame in enumerate(frames, start=1):
        image = Image.open(frame).convert("RGB")
        crop = first_crop
        digest = ink_hash(image, crop)
        distances = [hamming(digest, existing) for existing in seen_hashes]
        best_distance = min(distances) if distances else None
        duplicate_of = None
        if best_distance is not None and best_distance <= threshold:
            duplicate_of = int(np.argmin(distances)) + 1
        else:
            seen_hashes.append(digest)
            keep_name = f"score_{len(kept) + 1:04d}_{frame.stem}.jpg"
            copy_crop(frame, crop, unique_dir / keep_name)
            kept.append(
                {
                    "uniqueIndex": len(kept) + 1,
                    "frame": frame.name,
                    "timeSec": round((index - 1) / fps, 3),
                    "file": str((unique_dir / keep_name).relative_to(out_dir)),
                    "hash": f"{digest:x}",
                }
            )

        frame_infos.append(
            {
                "frame": frame.name,
                "timeSec": round((index - 1) / fps, 3),
                "duplicateOf": duplicate_of,
                "bestDistance": best_distance,
            }
        )

    return {
        "frameCount": len(frames),
        "uniqueCount": len(kept),
        "hammingThreshold": threshold,
        "crop": first_crop.as_list(),
        "uniqueFrames": kept,
        "frames": frame_infos,
    }


def make_contact_sheet(images: list[Path], out: Path, cols: int = 5, thumb_width: int = 320) -> None:
    if not images:
        return
    thumbs = []
    for path in images:
        image = Image.open(path).convert("RGB")
        ratio = thumb_width / image.width
        thumbs.append(image.resize((thumb_width, max(1, int(image.height * ratio)))))

    rows = (len(thumbs) + cols - 1) // cols
    thumb_height = max(img.height for img in thumbs)
    sheet = Image.new("RGB", (cols * thumb_width, rows * thumb_height), "white")
    for i, thumb in enumerate(thumbs):
        x = (i % cols) * thumb_width
        y = (i // cols) * thumb_height
        sheet.paste(thumb, (x, y))
    sheet.save(out, quality=92)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("video", type=Path)
    parser.add_argument("--out-dir", type=Path)
    parser.add_argument("--fps", type=float, default=2.0)
    parser.add_argument("--threshold", type=int, default=28)
    args = parser.parse_args()

    stem = args.video.stem
    project_root = Path(__file__).resolve().parents[1]
    default_out_dir = project_root / "data" / "tmp" / stem
    out_dir = args.out_dir or default_out_dir
    frames_dir = out_dir / "frames"
    if frames_dir.exists() and not any(frames_dir.glob("frame_*.jpg")):
        shutil.rmtree(frames_dir)
    run_ffmpeg(args.video, frames_dir, args.fps)

    frames = sorted(frames_dir.glob("frame_*.jpg"))
    if not frames:
        raise SystemExit("no frames extracted")

    result = dedup_frames(frames, out_dir, args.threshold, args.fps)
    (out_dir / "dedup_result.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    crop_images = sorted((out_dir / "dedup").glob("*.jpg"))
    make_contact_sheet(crop_images[:30], out_dir / "dedup_overview.jpg")

    print(f"frames: {result['frameCount']}")
    print(f"unique: {result['uniqueCount']}")
    print(f"crop: {result['crop']}")
    print(f"wrote: {out_dir / 'dedup_result.json'}")
    print(f"wrote: {out_dir / 'dedup_overview.jpg'}")


if __name__ == "__main__":
    main()
