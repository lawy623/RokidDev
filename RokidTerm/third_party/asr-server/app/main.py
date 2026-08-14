from __future__ import annotations

import asyncio
import logging
import os
import resource
import tempfile
import time
import wave
from pathlib import Path
from typing import Annotated

from fastapi import FastAPI, File, Header, HTTPException, UploadFile
from fastapi.responses import JSONResponse

from .config import settings

logging.basicConfig(
    level=os.getenv("ASR_LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
log = logging.getLogger("rokid-asr")

app = FastAPI(title="Rokid ASR Server", version="0.1.0")
slot = asyncio.Semaphore(1)
queue_guard = asyncio.Lock()
queued = 0

_backend: object | None = None


def get_backend():
    """Lazily import + build the backend for `ASR_BACKEND`.

    Model imports are heavy on the test VM (sherpa ~1 s; funasr/torch ~60 s);
    done at module level they would keep uvicorn from listening. Deferred,
    the boot drops to ~3 s; the first (warmup) request then pays the import +
    model load in one call, which the app's "ASR MODEL LOADING" state covers.
    """
    global _backend
    if _backend is None:
        if settings.backend == "sherpa":
            from .backends.sherpa import SherpaSenseVoiceBackend

            _backend = SherpaSenseVoiceBackend(
                settings.sherpa_model, settings.sherpa_tokens, settings.sherpa_language, settings.threads
            )
        elif settings.backend == "funasr":
            from .backends.sensevoice import SenseVoiceBackend

            _backend = SenseVoiceBackend(settings.model, settings.hub, settings.device, settings.quantize)
        else:
            raise ValueError(f"unknown ASR_BACKEND {settings.backend!r} (expected sherpa|funasr)")
    return _backend


@app.get("/healthz")
def healthz() -> dict[str, object]:
    backend = get_backend()
    return {
        "ok": True,
        "backend": settings.backend,
        "model_loaded": backend._model is not None,
        "model": backend.model_name,
        "device": settings.device,
        "bind": f"{settings.host}:{settings.port}",
    }


@app.post("/v1/transcribe")
async def transcribe(
    file: Annotated[UploadFile, File(...)],
    x_asr_token: Annotated[str | None, Header()] = None,
    language: str = "auto",
) -> JSONResponse:
    if settings.token and x_asr_token != settings.token:
        raise HTTPException(status_code=401, detail="invalid ASR token")
    if language not in {"auto", "zh", "en", "yue", "ja", "ko"}:
        raise HTTPException(status_code=400, detail="unsupported language")
    if not file.filename or not file.filename.lower().endswith(".wav"):
        raise HTTPException(status_code=415, detail="only WAV uploads are supported")

    global queued
    async with queue_guard:
        if slot.locked() and queued >= settings.queue_size:
            raise HTTPException(status_code=429, detail="ASR queue is full")
        queued += 1

    temp_path: Path | None = None
    started = time.perf_counter()
    try:
        data = await file.read(settings.max_audio_bytes + 1)
        if len(data) > settings.max_audio_bytes:
            raise HTTPException(status_code=413, detail="audio file is too large")
        duration_ms, sample_rate, channels = _inspect_wav(data)
        if duration_ms <= 0 or duration_ms > settings.max_audio_seconds * 1000:
            raise HTTPException(status_code=400, detail="WAV duration is outside the allowed range")

        with tempfile.NamedTemporaryFile(prefix="rokid-asr-", suffix=".wav", delete=False) as tmp:
            tmp.write(data)
            temp_path = Path(tmp.name)

        wait_started = time.perf_counter()
        async with slot:
            queue_ms = (time.perf_counter() - wait_started) * 1000
            inference_started = time.perf_counter()
            result = await asyncio.to_thread(lambda: get_backend().transcribe(temp_path, language))
            inference_ms = (time.perf_counter() - inference_started) * 1000

        total_ms = (time.perf_counter() - started) * 1000
        rss_mb = _rss_mb()
        response = {
            "text": result.text,
            "language": result.language,
            "model": result.model,
            "audio_duration_ms": round(duration_ms, 1),
            "sample_rate": sample_rate,
            "channels": channels,
            "queue_ms": round(queue_ms, 1),
            "inference_ms": round(inference_ms, 1),
            "total_ms": round(total_ms, 1),
            "rtf": round(inference_ms / duration_ms, 3),
            "process_rss_mb": round(rss_mb, 1),
        }
        if settings.log_transcript:
            log.info("transcribed duration_ms=%.1f inference_ms=%.1f text=%r", duration_ms, inference_ms, result.text)
        else:
            log.info("transcribed duration_ms=%.1f inference_ms=%.1f rss_mb=%.1f", duration_ms, inference_ms, rss_mb)
        return JSONResponse(response)
    finally:
        async with queue_guard:
            queued = max(0, queued - 1)
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)


def _inspect_wav(data: bytes) -> tuple[float, int, int]:
    try:
        import io

        with wave.open(io.BytesIO(data), "rb") as wav:
            channels = wav.getnchannels()
            sample_rate = wav.getframerate()
            frames = wav.getnframes()
            sample_width = wav.getsampwidth()
    except (wave.Error, EOFError) as exc:
        # Diagnostic for the glasses warmup path: log what actually arrived so
        # a truncated/corrupted upload is visible without a transcript.
        log.warning(
            "invalid WAV rejected: len=%d head=%s tail=%s",
            len(data),
            data[:32].hex(),
            data[-16:].hex(),
        )
        raise HTTPException(status_code=400, detail="invalid WAV file") from exc
    if channels != 1 or sample_rate != 16000 or sample_width != 2:
        raise HTTPException(status_code=400, detail="WAV must be mono, 16 kHz, 16-bit PCM")
    return frames * 1000 / sample_rate, sample_rate, channels


def _rss_mb() -> float:
    # ru_maxrss reports the process lifetime peak, which stays high after the
    # one-time model load and hides any later memory reduction (e.g. int8
    # quantization). Read the current resident set from /proc instead.
    try:
        with open("/proc/self/statm", encoding="ascii") as f:
            fields = f.read().split()
        page_kb = os.sysconf("SC_PAGE_SIZE") // 1024
        return int(fields[1]) * page_kb / 1024
    except (OSError, IndexError, ValueError):
        # Fall back to peak RSS (ru_maxrss is KiB on Linux).
        return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024
