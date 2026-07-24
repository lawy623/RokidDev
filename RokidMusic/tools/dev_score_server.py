#!/usr/bin/env python3
"""Static RokidMusic dev server with a narrow score-save endpoint."""

from __future__ import annotations

import argparse
import json
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote


class ScoreDevHandler(SimpleHTTPRequestHandler):
    root_dir: Path
    music_dir: Path

    def do_POST(self) -> None:
        if self.path != "/api/save-score":
            self.send_error(404, "Unknown endpoint")
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            target = self.safe_music_path(payload.get("path", ""))
            score = payload.get("score")
            if not isinstance(score, dict):
                raise ValueError("score must be an object")
            target.write_text(json.dumps(score, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        except Exception as exc:  # noqa: BLE001 - return concise API error to browser.
            body = str(exc).encode("utf-8")
            self.send_response(400)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        body = b'{"ok":true}\n'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def safe_music_path(self, raw_path: str) -> Path:
        if not raw_path:
            raise ValueError("path is required")
        clean = unquote(str(raw_path).split("?", 1)[0].split("#", 1)[0]).lstrip("/")
        if clean.startswith("./"):
            clean = clean[2:]
        if not clean.startswith("data/music/") or not clean.endswith(".json"):
            raise ValueError("path must be a JSON file under data/music/")
        target = (self.root_dir / clean).resolve()
        if self.music_dir not in target.parents and target != self.music_dir:
            raise ValueError("path escapes data/music/")
        # Allow creating new scores in data/music/.
        return target


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--root", default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()

    root = Path(args.root).resolve()
    handler = lambda *a, **kw: ScoreDevHandler(*a, directory=str(root), **kw)
    ScoreDevHandler.root_dir = root
    ScoreDevHandler.music_dir = (root / "data/music").resolve()
    server = ThreadingHTTPServer((args.host, args.port), handler)
    print(f"Serving {root} at http://{args.host}:{args.port}/tab_renderer.html")
    print("Score save endpoint: POST /api/save-score")
    server.serve_forever()


if __name__ == "__main__":
    main()
