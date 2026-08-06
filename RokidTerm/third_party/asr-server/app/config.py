from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    host: str = os.getenv("ASR_HOST", "127.0.0.1")
    port: int = int(os.getenv("ASR_PORT", "8765"))
    model: str = os.getenv("ASR_MODEL", "iic/SenseVoiceSmall")
    hub: str = os.getenv("ASR_HUB", "ms")
    device: str = os.getenv("ASR_DEVICE", "cpu")
    token: str = os.getenv("ASR_TOKEN", "")
    max_audio_bytes: int = int(os.getenv("ASR_MAX_AUDIO_BYTES", str(4 * 1024 * 1024)))
    max_audio_seconds: float = float(os.getenv("ASR_MAX_AUDIO_SECONDS", "30"))
    queue_size: int = int(os.getenv("ASR_QUEUE_SIZE", "2"))
    log_transcript: bool = os.getenv("ASR_LOG_TRANSCRIPT", "0") == "1"
    # int8 dynamic quantization of Linear weights. Cuts model RSS roughly in
    # half (2.76 GiB -> ~1.4 GiB observed) at a small accuracy cost. Off by
    # default so the fp32 baseline stays reproducible.
    quantize: bool = os.getenv("ASR_QUANTIZE", "0") == "1"


settings = Settings()
