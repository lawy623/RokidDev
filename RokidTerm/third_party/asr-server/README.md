# Rokid ASR Server

CPU-only ASR service for testing Rokid Glass microphone recordings. The first
backend is FunASR `iic/SenseVoiceSmall`, chosen because it is multilingual,
non-autoregressive, and designed to run on CPU/edge hardware.

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
uvicorn app.main:app --host 127.0.0.1 --port 8765 --workers 1
```

The first request downloads and loads the model. Keep one worker: multiple
workers would load a separate copy of the model and waste the server's RAM.

## API

```bash
curl -F 'file=@sample.wav' http://127.0.0.1:8765/v1/transcribe
curl http://127.0.0.1:8765/healthz
```

Set `ASR_TOKEN` to require `X-ASR-Token` on `/v1/transcribe`:

```bash
ASR_TOKEN='a-long-random-token' uvicorn app.main:app --host 127.0.0.1 --port 8765 --workers 1
curl -H "X-ASR-Token: a-long-random-token" -F 'file=@sample.wav' http://127.0.0.1:8765/v1/transcribe
```

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
