from __future__ import annotations

import argparse
import json
import pathlib
import statistics
import sys
import time

import requests


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("files", nargs="+")
    parser.add_argument("--url", default="http://127.0.0.1:8765/v1/transcribe")
    parser.add_argument("--token", default="")
    parser.add_argument("--repeat", type=int, default=1)
    args = parser.parse_args()

    rows: list[dict[str, object]] = []
    headers = {"X-ASR-Token": args.token} if args.token else {}
    for file_name in args.files:
        path = pathlib.Path(file_name)
        for repeat in range(args.repeat):
            started = time.perf_counter()
            with path.open("rb") as audio:
                response = requests.post(
                    args.url,
                    headers=headers,
                    files={"file": (path.name, audio, "audio/wav")},
                    timeout=180,
                )
            elapsed_ms = (time.perf_counter() - started) * 1000
            try:
                payload = response.json()
            except ValueError:
                payload = {"body": response.text}
            row = {"file": str(path), "repeat": repeat + 1, "http_ms": round(elapsed_ms, 1), **payload}
            rows.append(row)
            print(json.dumps(row, ensure_ascii=False))
            if response.status_code >= 400:
                return 1

    timings = [float(row["inference_ms"]) for row in rows if "inference_ms" in row]
    if timings:
        print(json.dumps({
            "summary": {
                "samples": len(timings),
                "median_inference_ms": round(statistics.median(timings), 1),
                "mean_inference_ms": round(statistics.mean(timings), 1),
            }
        }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
