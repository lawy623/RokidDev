from __future__ import annotations

from pathlib import Path
from typing import Any

from .base import Transcription


class SherpaSenseVoiceBackend:
    """Lazy-loaded sherpa-onnx SenseVoiceSmall int8 backend (default since
    2026-08-14).

    Replaces the funasr/torch backend by default: the int8 ONNX weights keep
    process RSS at ~350 MB vs ~1.9 GiB for funasr fp32, load in ~1 s instead
    of ~30-60 s, and transcribe slightly faster (RTF ~0.10 vs 0.14-0.17).
    Verified against the real Rokid recordings: Chinese identical, English
    equal-or-better, minor ITN spacing/letter artifacts on rare command words
    (e.g. "settings" -> "settingss") that funasr's ITN does not produce.

    The language hint is fixed at construction (`ASR_SHERPA_LANGUAGE`,
    default "auto") because sherpa-onnx builds the recognizer with it; the
    per-request `language` parameter is accepted for API compatibility and
    the app always sends "auto" anyway.
    """

    model_name = "sensevoice-small-int8"

    def __init__(self, model: str, tokens: str, language: str = "auto", num_threads: int = 2) -> None:
        self.model_path = model
        self.tokens_path = tokens
        self.language = language
        self.num_threads = num_threads
        # OfflineRecognizer instance; healthz checks this field for model_loaded.
        self._model: Any | None = None

    def _load(self) -> None:
        if self._model is not None:
            return
        # Lazy import: /healthz must work before sherpa-onnx and the model
        # weights are installed (same pattern as the funasr backend).
        import sherpa_onnx

        self._model = sherpa_onnx.OfflineRecognizer.from_sense_voice(
            model=self.model_path,
            tokens=self.tokens_path,
            num_threads=self.num_threads,
            language=self.language,
            use_itn=True,
        )

    def warmup(self) -> None:
        self._load()

    def transcribe(self, audio_path: Path, language: str = "auto") -> Transcription:
        self._load()
        assert self._model is not None

        sample_rate, samples = _read_wav(audio_path)
        stream = self._model.create_stream()
        stream.accept_waveform(sample_rate, samples)
        self._model.decode_stream(stream)
        text = stream.result.text.strip()
        return Transcription(text=text, language=language, model=self.model_name)


def _read_wav(path: Path) -> tuple[int, Any]:
    """Read the service-contract WAV (16 kHz mono 16-bit PCM) to float32.

    The API layer already validated the format; this stays defensive.
    """
    import wave

    import numpy as np

    with wave.open(str(path), "rb") as w:
        assert w.getnchannels() == 1 and w.getframerate() == 16000 and w.getsampwidth() == 2, (
            "expected mono 16 kHz 16-bit PCM"
        )
        raw = w.readframes(w.getnframes())
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    return 16000, samples
