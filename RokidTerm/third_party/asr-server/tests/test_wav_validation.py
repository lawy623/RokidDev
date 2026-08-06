from __future__ import annotations

import io
import wave

import pytest
from fastapi import HTTPException

from app.main import _inspect_wav


def wav(sample_rate: int = 16000, channels: int = 1, width: int = 2) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as out:
        out.setnchannels(channels)
        out.setsampwidth(width)
        out.setframerate(sample_rate)
        out.writeframes(b"\0" * sample_rate * channels * width // 10)
    return buf.getvalue()


def test_valid_wav() -> None:
    duration, rate, channels = _inspect_wav(wav())
    assert duration == 100
    assert rate == 16000
    assert channels == 1


@pytest.mark.parametrize("kwargs", [{"sample_rate": 8000}, {"channels": 2}, {"width": 1}])
def test_rejects_non_rokid_format(kwargs: dict[str, int]) -> None:
    with pytest.raises(HTTPException):
        _inspect_wav(wav(**kwargs))
