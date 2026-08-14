# Rokid ASR Server

CPU-only ASR service for Rokid Glass microphone recordings. Multilingual
(zh/en/ja/ko/yue), non-autoregressive, designed for CPU/edge inference.

**Default backend (2026-08-14): sherpa-onnx SenseVoiceSmall int8** — ~350 MB
RSS, ~1 s model load, RTF ~0.10. The original funasr fp32 backend remains
available via `ASR_BACKEND=funasr` (~1.9 GiB RSS). See `CLAUDE.md` →
`## Model strategy` for the measured comparison and quality notes.

The service is deliberately small and private-by-default:

- binds to `127.0.0.1:8765` only;
- accepts one in-flight transcription and queues at most two more;
- accepts WAV files up to 30 seconds and 4 MiB;
- deletes temporary audio after each request;
- does not log audio or full transcripts by default;
- exposes timing and process RSS in the JSON response for benchmarking.

## Local development

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
# Model weights for the default backend (git-ignored):
mkdir -p models/sense-voice-int8
#   place model.int8.onnx + tokens.txt here (sherpa-onnx release tarball
#   "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"), or set
#   ASR_SHERPA_MODEL / ASR_SHERPA_TOKENS to point elsewhere.
uvicorn app.main:app --host 127.0.0.1 --port 8765 --workers 1
```

Keep one worker: multiple workers would load a separate copy of the model and
waste the server's RAM.

## API

```bash
curl -F 'file=@sample.wav' http://127.0.0.1:8765/v1/transcribe
curl http://127.0.0.1:8765/healthz   # reports backend, model, model_loaded
```

Set `ASR_TOKEN` to require `X-ASR-Token` on `/v1/transcribe`:

```bash
ASR_TOKEN='a-long-random-token' uvicorn app.main:app --host 127.0.0.1 --port 8765 --workers 1
curl -H "X-ASR-Token: a-long-random-token" -F 'file=@sample.wav' http://127.0.0.1:8765/v1/transcribe
```

### Environment

| Variable | Default | Meaning |
|---|---|---|
| `ASR_BACKEND` | `sherpa` | `sherpa` (int8 ONNX, ~350 MB) or `funasr` (fp32, ~1.9 GiB) |
| `ASR_SHERPA_MODEL` | `models/sense-voice-int8/model.int8.onnx` | sherpa model path |
| `ASR_SHERPA_TOKENS` | `models/sense-voice-int8/tokens.txt` | sherpa tokenizer path |
| `ASR_SHERPA_LANGUAGE` | `auto` | language hint baked at construction: `auto\|zh\|en\|ja\|ko\|yue` |
| `ASR_THREADS` | `2` | onnxruntime threads (match the vCPU count) |
| `ASR_MODEL` / `ASR_HUB` / `ASR_DEVICE` / `ASR_QUANTIZE` | — | funasr backend only (`ASR_BACKEND=funasr`) |

## Benchmark

```bash
python benchmark/run_benchmark.py --url http://127.0.0.1:8765/v1/transcribe \
  test-artifacts/recordings/*.wav
```

For the first server test, an SSH local-forward can expose the private server
port on the development Mac without opening a public ASR port:

```bash
ssh -i /path/to/device-key.pem -o IdentitiesOnly=yes \
  -o ExitOnForwardFailure=yes -N \
  -L 17865:127.0.0.1:8765 ubuntu@server
```

Then call `http://127.0.0.1:17865/v1/transcribe` from the Mac. A normal SSH
login does not create this port forward automatically.
