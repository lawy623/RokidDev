from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol


@dataclass(frozen=True)
class Transcription:
    text: str
    language: str
    model: str


class AsrBackend(Protocol):
    model_name: str

    def transcribe(self, audio_path: Path, language: str = "auto") -> Transcription:
        ...
