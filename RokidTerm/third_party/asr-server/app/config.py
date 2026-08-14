from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    host: str = os.getenv("ASR_HOST", "127.0.0.1")
    port: int = int(os.getenv("ASR_PORT", "8765"))
    # Inference backend: "sherpa" (default, int8 ONNX via sherpa-onnx, ~350 MB
    # RSS) or "funasr" (fp32 SenseVoiceSmall via funasr/torch, ~1.9 GiB RSS).
    # See CLAUDE.md -> Model strategy for the measured comparison.
    backend: str = os.getenv("ASR_BACKEND", "sherpa")
    # --- funasr backend settings (ASR_BACKEND=funasr) ---
    model: str = os.getenv("ASR_MODEL", "iic/SenseVoiceSmall")
    hub: str = os.getenv("ASR_HUB", "ms")
    device: str = os.getenv("ASR_DEVICE", "cpu")
    # PyTorch dynamic int8 quantization of Linear weights. Does NOT save
    # memory (fp32 copy is kept); ~20-25% faster inference at a small English
    # accuracy cost. Off by default.
    quantize: bool = os.getenv("ASR_QUANTIZE", "0") == "1"
    # --- sherpa backend settings (default) ---
    # Paths are relative to the deployment dir (/srv/RokidAsrServer); models/
    # is git-ignored (model weights never enter the repo).
    sherpa_model: str = os.getenv(
        "ASR_SHERPA_MODEL", "models/sense-voice-int8/model.int8.onnx"
    )
    sherpa_tokens: str = os.getenv(
        "ASR_SHERPA_TOKENS", "models/sense-voice-int8/tokens.txt"
    )
    # Language hint baked into the recognizer at construction: auto|zh|en|ja|ko|yue
    sherpa_language: str = os.getenv("ASR_SHERPA_LANGUAGE", "auto")
    threads: int = int(os.getenv("ASR_THREADS", "2"))
    # --- service limits ---
    token: str = os.getenv("ASR_TOKEN", "")
    max_audio_bytes: int = int(os.getenv("ASR_MAX_AUDIO_BYTES", str(4 * 1024 * 1024)))
    max_audio_seconds: float = float(os.getenv("ASR_MAX_AUDIO_SECONDS", "30"))
    queue_size: int = int(os.getenv("ASR_QUEUE_SIZE", "2"))
    log_transcript: bool = os.getenv("ASR_LOG_TRANSCRIPT", "0") == "1"


settings = Settings()
