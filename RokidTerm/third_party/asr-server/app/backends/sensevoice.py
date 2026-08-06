from __future__ import annotations

from pathlib import Path
from typing import Any

from .base import Transcription


class SenseVoiceBackend:
    """Lazy-loaded SenseVoiceSmall backend for short WAV utterances."""

    model_name = "sensevoice-small"

    def __init__(self, model_id: str, hub: str, device: str, quantize: bool = False) -> None:
        self.model_id = model_id
        self.hub = hub
        self.device = device
        self.quantize = quantize
        self._model: Any | None = None
        self._postprocess: Any | None = None

    def _load(self) -> None:
        if self._model is not None:
            return
        # Imports are intentionally lazy so /healthz works before the model
        # dependencies and weights have been downloaded.
        import torch
        from funasr import AutoModel
        from funasr.utils.postprocess_utils import rich_transcription_postprocess

        self._model = AutoModel(
            model=self.model_id,
            device=self.device,
            hub=self.hub,
        )
        if self.quantize:
            # Dynamic int8 quantization of Linear weights keeps activations in
            # fp32, so no calibration set is needed. On the 3.6 GiB test VM it
            # cuts process RSS from ~2.76 GiB to ~1.4 GiB at a small accuracy
            # cost. Only Linear layers are quantized; unsupported modules stay
            # fp32 (a RuntimeWarning is expected).
            import warnings

            with warnings.catch_warnings():
                warnings.simplefilter("ignore")
                self._model.model = torch.ao.quantization.quantize_dynamic(
                    self._model.model, {torch.nn.Linear}, dtype=torch.qint8
                )
        self._postprocess = rich_transcription_postprocess

    def warmup(self) -> None:
        self._load()

    def transcribe(self, audio_path: Path, language: str = "auto") -> Transcription:
        self._load()
        assert self._model is not None
        assert self._postprocess is not None

        # SenseVoice's direct inference path is appropriate for short utterances
        # and avoids loading an additional VAD model on this low-memory VM.
        result = self._model.generate(
            input=str(audio_path),
            cache={},
            language=language,
            use_itn=True,
            batch_size_s=60,
        )
        raw_text = _extract_text(result)
        text = self._postprocess(raw_text).strip()
        return Transcription(text=text, language=language, model=self.model_name)


def _extract_text(result: Any) -> str:
    """Handle the result shapes used by different FunASR releases."""
    if isinstance(result, list) and result:
        item = result[0]
    else:
        item = result
    if isinstance(item, dict):
        return str(item.get("text", ""))
    if isinstance(item, list) and item and isinstance(item[0], dict):
        return str(item[0].get("text", ""))
    return str(item or "")
