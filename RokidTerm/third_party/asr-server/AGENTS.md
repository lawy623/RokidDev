# AGENTS.md — RokidAsrServer

Read and follow the project-specific guidance in `CLAUDE.md` in addition to the
repository root `AGENTS.md`. `CLAUDE.md` is the detailed shared specification
for architecture, test-server constraints, benchmarks, deployment, and security.

## Deployment is live (August 4, 2026)

The service runs on the test VM (`ubuntu@43.xx.xx.209`) at
`127.0.0.1:8765`, one worker, model cached from HuggingFace
(`FunAudioLLM/SenseVoiceSmall`, ~901 MiB). It survives reboots (venv + model
cache on disk) but must be restarted manually with the command documented in
`CLAUDE.md` → `## Deployment`.

## Rules that must not be broken

- Keep the ASR listener on `127.0.0.1`; never open port 8765 publicly.
- One model/worker and one inference at a time on the low-memory VM. Never load
  a second model instance while the service is resident — it OOMs the VM.
- Never commit credentials, PEMs, model weights, recordings, or transcripts.
- Benchmark with the real Rokid recordings
  (`test-artifacts/recordings/`), never with
  `test-device-latest.wav`.
- `process_rss_mb` in responses is current RSS from `/proc/self/statm`; do not
  regress `_rss_mb()` to `ru_maxrss` (peak value hides memory reductions).
- Do not integrate ASR text into shell execution without an editable draft and
  explicit confirmation.
- Dynamic int8 quantization (`ASR_QUANTIZE=1`) does **not** save memory on
  PyTorch CPU (fp32 copy is kept); it only speeds inference ~20–25%. True
  memory reduction requires ONNX/OpenVINO int8 — see `CLAUDE.md` → `## Model
  strategy` for the unverified candidates.
- ASR is on-demand: it starts via a PAM `pam_exec` hook when `rokid` logs in
  and stops ~60 s after the last `rokid` session closes. The glasses reach it
  through the `asr-fwd` account (permitopen restricted to `127.0.0.1:8765`).
  Never detect the ASR process with `pgrep -f 'uvicorn app.main'` (pattern
  self-match); use `/tmp/asr.pid` or a `/proc` scan. See `CLAUDE.md` →
  `## On-demand ASR lifecycle` and `## Glasses access via asr-fwd`.
- Sync discipline: this directory is the source of truth; every code change
  must be deployed to `/srv/RokidAsrServer`. Never edit the two copies in
  parallel.
