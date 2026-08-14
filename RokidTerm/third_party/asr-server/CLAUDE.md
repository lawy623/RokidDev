# CLAUDE.md — RokidAsrServer

## Project purpose

`RokidAsrServer` is the server-side speech-to-text component for Rokid Glass.
The glasses can record good-quality speech, but the on-device multilingual
Whisper tiny model is too inaccurate and too slow for practical Chinese/English
dictation. This project moves inference to a CPU-only server while keeping the
microphone capture and final text UI on the glasses.

The service is deliberately small and private-by-default:

- binds to `127.0.0.1:8765` only;
- accepts one in-flight transcription and queues at most two more;
- accepts WAV files up to 30 seconds and 4 MiB;
- deletes temporary audio after each request;
- does not log audio or full transcripts by default (`ASR_LOG_TRANSCRIPT=0`);
- exposes timing and current RSS in the JSON response for benchmarking.

## Current status (August 14, 2026) — DEPLOYED AND BENCHMARKED

The service is **fully deployed and verified** on the test VM. Real Rokid
recordings transcribe correctly with good latency. See
`## Deployment` and `## Benchmark results` below.

**Default backend is sherpa-onnx int8 (2026-08-14)** — see `## Model strategy`.
The funasr fp32 backend remains available via `ASR_BACKEND=funasr`.

Remaining work: server hardening (systemd unit) and live on-glasses re-test
of the int8 backend — documented at the end of this file.

## Test server constraints

The current server connection is `ubuntu@43.xx.xx.209` (Tencent Cloud, hostname
`rokid-vm`), reachable with the PEM at
`~/Desktop/<your-key>.pem`. The VM observed on August 4, 2026 has:

- Ubuntu 24.04.4 LTS, x86_64;
- AMD EPYC 7K62, 2 vCPU, AVX2/FMA (no AVX512-FP16);
- 3.6 GiB RAM and 1.9 GiB swap (`/swap.img`, persisted in `/etc/fstab`);
- no GPU;
- Python 3.12.3 and working `venv` support (`python3-venv` was installed);
- no Docker, system `pip3`, or `ffmpeg`;
- 74 GiB free disk.

Treat memory as the primary constraint. Run Uvicorn with exactly one worker and
allow only one inference at a time. Never load one model per HTTP worker.

**Critical operational lesson:** the VM OOM-killed the SSH session and became
unreachable when a second model instance was loaded while the service was
already resident (~2.7 GiB). Always stop the running ASR service before running
any memory experiment on this VM, and always re-verify via `free -h` before
proceeding. Recover with a console reboot; venv and model cache survive reboot.

Do not add the server IP address, PEM path, private key, password, or token to
this repository. Use the developer's SSH configuration or environment variables
for connection details.

## Audio contract

The Android client currently records:

- RIFF/WAV;
- 16,000 Hz;
- mono;
- signed 16-bit PCM.

The initial API accepts only that format. It rejects audio over 30 seconds or
4 MiB. Avoid adding server-side resampling until the base benchmark is stable;
resampling can hide client recording mistakes and adds dependencies.

Real Rokid recordings for benchmarks are in:

```text
test-artifacts/recordings/
```

The four existing files (`live-20260804-1451{41,57,11,27}-manual.wav`) are
approximately 4.0–7.4 seconds each. They are actual Rokid microphone recordings
of mixed Chinese/English speech, and are the ground-truth set for quality
evaluation. `../../../RokidLocalAsr/test-artifacts/test-device-latest.wav` is an old
sample and must not be used as evidence of microphone or ASR quality.

## API contract

Initial non-streaming endpoint:

```http
POST /v1/transcribe
Content-Type: multipart/form-data
X-ASR-Token: optional-token

file=<16 kHz mono 16-bit PCM WAV>
language=auto
```

Representative response (default sherpa int8 backend):

```json
{
  "text": "识别文本",
  "language": "auto",
  "model": "sensevoice-small-int8",
  "audio_duration_ms": 4224.0,
  "sample_rate": 16000,
  "channels": 1,
  "queue_ms": 0.0,
  "inference_ms": 445.0,
  "total_ms": 450.0,
  "rtf": 0.105,
  "process_rss_mb": 353.0
}
```

`model` reports `sensevoice-small-int8` (sherpa) or `sensevoice-small`
(funasr). The glasses client reads only the `text` field — the switch is
client-invisible. `process_rss_mb` reports **current** RSS from
`/proc/self/statm`, NOT the process-lifetime peak (`ru_maxrss`). The peak
reading stays high after the one-time model load and hides any later memory
behavior — it must not be used to compare configurations. The `_rss_mb()`
helper in `app/main.py` implements this correctly; do not regress it to
`ru_maxrss`.

Also expose `GET /healthz`. A healthy process is not proof that the model is
loaded; inspect `model_loaded` (and `quantize`) and perform a real
transcription.

Do not switch to WebSocket streaming yet. First establish model quality and
whole-utterance latency with the simple HTTP API. Streaming can be added later
without changing the backend abstraction.

## Model strategy

Two interchangeable backends (`ASR_BACKEND` env, default `sherpa`):

| | **sherpa (default)** | **funasr (fallback)** |
|---|---|---|
| Runtime | sherpa-onnx (`OfflineRecognizer.from_sense_voice`) | funasr `AutoModel(SenseVoiceSmall)` + torch |
| Weights | **int8 ONNX** `model.int8.onnx` (239 MB) | fp32 HF weights (~901 MB cache) |
| Current RSS (measured) | **~350 MB** | ~1.9 GiB |
| Model load (warm, measured) | **~1 s** | ~30 s (torch import + load; cold ~60 s) |
| Inference RTF (measured) | **0.10–0.11** | 0.14–0.17 (fp32) / 0.12–0.13 (dyn int8) |
| Quality (4 real recordings) | Chinese identical; English equal-or-better on 2/4, minor ITN artifacts on 1 command word | baseline (has its own English errors) |
| ITN / punctuation | sherpa rule-based ITN (`use_itn=True`) | funasr `use_itn=True` + rich postprocess |
| Deps | sherpa-onnx + numpy only | funasr + torch + torchaudio (~2 GiB install) |

Backend selection: `ASR_BACKEND=sherpa` (default) or `ASR_BACKEND=funasr`;
`/healthz` reports `backend` and `model` (`sensevoice-small-int8` vs
`sensevoice-small`). The HTTP API contract is identical for both — the
glasses client reads only the `text` field, so switching backends is
client-invisible.

### Quantization findings (important, verified 2026-08-14)

- **sherpa-onnx int8 is the shipped default (2026-08-14).** Verified on the
  VM against the 4 real Rokid recordings (mixed Chinese/English): RSS ~353 MB
  (5.4× below funasr fp32), RTF 0.10–0.11, model load ~1 s. Chinese text is
  identical on all 4 recordings; 2 English tokens came out *more* accurate
  than the funasr baseline (`tlor swift` vs `tlor swiftt`,
  `whisper local` vs `whisperloc`). The one defect class: sherpa's rule-based
  ITN occasionally double-letters or drops spaces around English command words
  (`请打开 settingss，查看wifi状态` — raw decoding is correct
  `请打开 settings 查看 wifi 状态`, the artifact is ITN-only). This was
  accepted by the user (Chinese-first; minor English drift acceptable).
- **PyTorch dynamic int8 (`ASR_QUANTIZE=1`, funasr backend only) does NOT save
  memory.** CPU dynamic quantization keeps an fp32 copy of the weights; the
  int8 packed weights are additional. Measured RSS ~1.9 GiB both ways. What it
  *does* give is ~20–25% faster inference at a small accuracy cost on English
  tokens (`youtube`→`youtu`) — off by default.
- **`model.half()` (fp16) on the whole model fails on CPU** with
  `Input type (FloatTensor) and weight type (HalfTensor)` because the speech
  features stay fp32. Do not attempt it.
- **SenseVoiceSmall has no large embedding table** (only a small 16×560
  embedding), so "quantize the embedding" is not an available lever.
- Rejected alternatives (no longer worth pursuing): self-exported ONNX int8
  via onnxruntime (~1.3 GiB — funasr frontend still carries torch) and
  OpenVINO `apinge/sensevoice-small-int8-asym-ov` (~1.2–1.4 GiB, extra
  ~400 MB runtime dep). Both miss the sub-700 MB goal that sherpa-onnx hits.

**Memory lessons:** `process_rss_mb` is current RSS from `/proc/self/statm`
(see API contract) — the only honest metric for comparing configurations.
`ru_maxrss` hides post-load reductions and must not be used.

## Deployment

Deployment state (all on the test VM):

- `/srv/RokidAsrServer` is the deployment copy; `.venv` is installed and
  contains `sherpa-onnx==1.13.5`, `numpy`, `fastapi`, `uvicorn`,
  `python-multipart`, `requests`, plus `funasr==1.4.1`,
  `torch==2.13.0+cpu`, `torchaudio==2.11.0+cpu` (funasr fallback backend —
  imported only when `ASR_BACKEND=funasr`; dormant torch costs no RSS).
  `torchaudio` was a missing runtime dependency discovered during the first
  live request — install it explicitly whenever creating the env.
- The default backend's int8 weights live in
  `/srv/RokidAsrServer/models/sense-voice-int8/` (`model.int8.onnx` 239 MB +
  `tokens.txt`; copied from the sherpa-onnx release tarball
  `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`). `models/` is
  git-ignored; deploy model files separately, never through git. The funasr
  fp32 weights stay cached at
  `~/.cache/huggingface/hub/models--FunAudioLLM--SenseVoiceSmall`
  (~901 MiB) for the fallback backend.
- The service is started **manually** with nohup (not yet a systemd unit):
  `ASR_BACKEND=sherpa nohup python -m uvicorn app.main:app --host 127.0.0.1 --port 8765 --workers 1 ...`
  (funasr fallback: `ASR_BACKEND=funasr ASR_HUB=hf ASR_MODEL=FunAudioLLM/SenseVoiceSmall`)
- A persistent systemd unit is **deferred** until glasses integration of the
  int8 backend is re-verified (see `## Next steps`).

Deploying updated code from the Mac:

```bash
# deploy.sh uses the SSH host alias "rokid-server" from ~/.ssh/config, which
# may route through a local proxy and fail with "Connection closed by
# 127.0.0.1:7890". The reliable path is a direct IP + explicit key:
tar -C . --exclude='.venv' --exclude='__pycache__' -czf /tmp/rokid-asr-server.tar.gz .
scp -i ~/Desktop/<your-key>.pem -o IdentitiesOnly=yes /tmp/rokid-asr-server.tar.gz ubuntu@43.xx.xx.209:/tmp/
ssh -i ~/Desktop/<your-key>.pem -o IdentitiesOnly=yes ubuntu@43.xx.xx.209 \
  "rm -rf /srv/RokidAsrServer/{app,benchmark,tests,README.md,requirements.txt,deploy.sh} && tar -xzf /tmp/rokid-asr-server.tar.gz -C /srv/RokidAsrServer && rm /tmp/rokid-asr-server.tar.gz"
```

From the development Mac, reach the private port through an SSH local forward:

```bash
nohup ssh -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
  -i ~/Desktop/<your-key>.pem -o IdentitiesOnly=yes \
  -N -L 127.0.0.1:17865:127.0.0.1:8765 ubuntu@43.xx.xx.209 &
```

The forward dies when the SSH session dies (e.g. after a VM reboot) — restart
it before benchmarking. Then:

```bash
cd RokidAsrServer
python benchmark/run_benchmark.py \
  --url http://127.0.0.1:17865/v1/transcribe \
  test-artifacts/recordings/*.wav
```

## Benchmark results (warm requests, 2 vCPU EPYC 7K62, one worker)

**Default: sherpa-onnx int8 (verified 2026-08-14):**

| file | text | audio_ms | inference_ms | rtf | rss_mb |
|---|---|---|---|---|---|
| 145141 | 你好你好你好，我是乐奇。 | 4224 | 441 | 0.104 | ~353 |
| 145157 | 请打开youtube，然后搜索tlor swift的最新MV。 | 6144 | 660 | 0.107 | ~353 |
| 145211 | 今天我们测试whisper local ASR重点是中文和english混合识别。 | 7424 | 794 | 0.107 | ~353 |
| 145227 | 请打开 settingss，查看wifi状态。 | 3968 | 445 | 0.112 | ~353 |

Median inference 553 ms; model load ~1 s; current RSS **~350 MB**.

**funasr fp32 baseline (August 4, 2026):**

| file | text | audio_ms | inference_ms | rtf | rss_mb |
|---|---|---|---|---|---|
| 145141 | 你好你好你好，我是乐奇。 | 4224 | 718 | 0.170 | ~1900 |
| 145157 | 请打开youtube，然后搜索tlor swiftt的最新MV。 | 6144 | 979 | 0.159 | ~1900 |
| 145211 | 今天我们测试whisperloc ASR重点是中文和english混合识别。 | 7424 | 1044 | 0.141 | ~1900 |
| 145227 | 请打开 settings ，查看 wifi 状态。 | 3968 | 667 | 0.168 | ~1900 |

funasr + dynamic int8 (Linear): RTF 0.12–0.13, ~24% faster than fp32, same
~1.9 GiB RSS, tiny English-token errors.

Quality of the int8 backend is judged **usable** by the user for the mixed
Chinese/English test set; Chinese is identical to the fp32 baseline on all 4
recordings, English is equal-or-better on 2 of 4, and the only defect is a
sherpa-ITN artifact on one command word (settingss / missing spaces). See
`## Model strategy`.

## Benchmark methodology notes

- Run one cold request, then repeat warm requests. Never average the cold
  model-download/load time into normal inference latency.
- `process_rss_mb` is current RSS (see API contract) — the correct metric.
- Check swap usage (`free -h`) after several requests; a stable ~1900 MB RSS
  with ~1.7 GiB free swap indicates no sustained thrashing.
- The 4 recordings are the quality ground truth; record the exact text each
  time for user judgment, never infer accuracy from latency alone.

## Security invariants

The ASR service handles private microphone audio. Preserve these rules:

- Bind the ASR service to `127.0.0.1:8765`, never `0.0.0.0:8765`.
- Do not open port 8765 in a cloud firewall, Caddy, Nginx, or a public security
  group.
- Reach it through an authenticated SSH local port forward during development;
  the production route (glasses → server) is still to be designed
  (see `## Glasses integration`).
- `ASR_TOKEN` is optional defense in depth, not a replacement for private
  network reachability and SSH authentication.
- Do not put the developer/admin PEM currently used for deployment into the APK.
- A production glasses client must use its own device key and an unprivileged
  account restricted to forwarding only `127.0.0.1:8765`.
- Do not log raw audio, complete transcripts, SSH credentials, tokens, or
  request bodies. `ASR_LOG_TRANSCRIPT=0` is the safe default.
- Delete temporary WAV files after success and after failure.
- Keep request-size, duration, concurrency, and queue limits.
- Do not let ASR text execute shell commands automatically. RokidTerm must show
  the transcription as an editable draft and require explicit confirmation.

The likely production route is:

```text
Rokid microphone
  -> Android AudioRecord WAV
  -> device-authenticated SSH local forward
  -> server 127.0.0.1:8765
  -> ASR text response
  -> editable RokidTerm draft
  -> explicit user confirmation
```

## Local/server source-of-truth rule

The monorepo copy under `RokidDev/RokidAsrServer` is currently the source of
truth. `/srv/RokidAsrServer` is a deployment copy and may contain a large venv
and model cache that must not be committed.

If future development is done directly with Claude Code on the server, first
choose a single Git-backed source directory (prefer
`/srv/RokidDev/RokidAsrServer`) and commit/push changes there. Do not edit the
Mac copy and a disconnected server copy in parallel. Keep `.venv`, model
weights, caches, recordings, secrets, benchmark outputs containing transcripts,
and service logs out of Git.

## On-demand ASR lifecycle (PAM hook) — DEPLOYED AND VERIFIED

The ASR service is **not** always-on. It starts when the `rokid` user opens an
SSH session and stops ~60 s after the last `rokid` session closes. This keeps
the ~1.9 GiB resident model from occupying memory when the glasses are not
using the terminal. Implemented with a PAM `pam_exec` hook, plus a sshd
dead-connection watchdog for abnormal disconnects:

- `/etc/ssh/sshd_config.d/98-clientalive.conf` — `ClientAliveInterval 60`,
  `ClientAliveCountMax 10`: sshd probes every 60 s and forcibly closes a
  session after 10 unanswered probes (**~10 min**). This covers abnormal
  disconnects (cable pull, glasses off, network drop) that never fire
  `close_session`. The counters are **per-session**, so reconnecting within
  the window starts a fresh session with a fresh 10-minute budget, and ASR
  stays warm because a live `rokid` session exists. Tune these two values
  together: total dead-session detection ≈ `Interval × CountMax`.
- `/etc/pam.d/sshd` gains one line:
  `session optional pam_exec.so /usr/local/sbin/asr-lifecycle.sh`
- `/usr/local/sbin/asr-lifecycle.sh` — fail-open; only acts when
  `PAM_USER=rokid`. `open_session` → start ASR if not running; `close_session`
  → schedule a detached 60 s-debounced stop.
- `/usr/local/sbin/start-asr.sh` — runuser to `ubuntu`, `cd`s to
  `/srv/RokidAsrServer`, starts uvicorn detached with `ASR_QUANTIZE=0`.
- `/usr/local/sbin/stop-asr.sh` — waits 60 s, then kills uvicorn via /proc scan
  only if no `rokid` sshd session remains (quick reconnect does not
  cold-restart the model).

- `/etc/pam.d/sshd` gains one line:
  `session optional pam_exec.so /usr/local/sbin/asr-lifecycle.sh`
- `/usr/local/sbin/asr-lifecycle.sh` — fail-open; only acts when
  `PAM_USER=rokid`. `open_session` → start ASR if not running; `close_session`
  → schedule a detached 60 s-debounced stop.
- `/usr/local/sbin/start-asr.sh` — runuser to `ubuntu`, `cd`s to
  `/srv/RokidAsrServer`, starts uvicorn detached with `ASR_QUANTIZE=0`.
- `/usr/local/sbin/stop-asr.sh` — waits 60 s, then kills uvicorn via /proc scan
  only if no `rokid` sshd session remains (quick reconnect does not
  cold-restart the model).

Verification notes:

- Never detect the ASR process with `pgrep -f 'uvicorn app.main'` inside the
  scripts: the pattern matches any shell whose command line contains that text
  (including an admin SSH session that is also running the same command) and
  produces false "already running" results. Use the PID file (`/tmp/asr.pid`
  written by start-asr.sh, verified via `kill -0` + `/proc/<pid>/cmdline`) or a
  `/proc` scan for `*uvicorn*app.main*`.
- The close-session stop must be detached as a **transient service**, NOT
  `systemd-run --scope`: scope mode is synchronous, so the hook blocks for the
  full debounce (~60 s) and the closing sshd session stays alive until it
  returns — `stop-asr.sh`'s `pgrep -u rokid sshd` then always finds its own
  session and aborts, so ASR is never stopped on a real session close
  (verified failing 2026-08-05, fixed with
  `systemd-run --unit=asr-stop-delayed --collect`, hook returns in ~26 ms).
  A plain background job inside the hook also fails: pam_exec reaps children
  when the hook exits, killing the debounce before it fires.
- `runuser -u ubuntu -- <start-script>` with the script itself doing `cd` works;
  an inline `runuser ... bash -c "cd ... && nohup ... &"` suffers from quote
  nesting and the `cd` is lost, causing `ModuleNotFoundError: No module named
  'app'`.
- A 60 s debounce on stop means an ASR service that was just stopped needs a
  fresh ~30 s model load on the next login. This is accepted; a quick reconnect
  is the common case and the debounce preserves the warm model.

To test the lifecycle manually (as the `ubuntu` admin):

```bash
# Start (open_session)
sudo bash -c 'PAM_USER=rokid PAM_TYPE=open_session PAM_SERVICE=sshd /usr/local/sbin/asr-lifecycle.sh'
# Stop (close_session) — schedules detached debounced stop
sudo bash -c 'PAM_USER=rokid PAM_TYPE=close_session PAM_SERVICE=sshd /usr/local/sbin/asr-lifecycle.sh'
```

`journalctl -t asr-lifecycle` shows "rokid login detected; starting ASR" and
"no active rokid sessions; stopping ASR".

## Glasses access via `asr-fwd` — DEPLOYED AND VERIFIED

The glasses' `rokid` user has `no-port-forwarding` (see
`/etc/ssh/sshd_config.d/99-rokid.conf`), so it cannot forward to the ASR port.
A dedicated unprivileged account provides the ASR-only channel:

- User `asr-fwd` (uid 996), `/usr/sbin/nologin` shell, no groups, home
  `/home/asr-fwd` (0700).
- `~asr-fwd/.ssh/authorized_keys` (0600) for the dedicated ed25519 key
  `rokid-asr-forward` with restrictions:
  `no-agent-forwarding,no-X11-forwarding,no-user-rc,no-pty,permitopen="127.0.0.1:8765"`.
- `permitopen` blocks every forward target except `127.0.0.1:8765` — verified:
  forwarding to port 3000 fails with `administratively prohibited` in the ssh
  client log, while forwarding to 8765 works end-to-end (healthz + a real
  transcription). `AllowTcpForwarding` remains `yes` globally (the default),
  which is what makes the per-key `permitopen` the effective control.
- The private key lives only in the glasses app-private storage (provisioned
  like the `rokid` identity) and in this repo's **uncommitted** local copy;
  never commit it.
- `no-pty` is intentional: the account is used purely with `-N -L` port
  forwarding and must not be a usable shell.

Glasses access pattern (from the APK, after the `rokid` session is up):

```text
JSch connect to asr-fwd@server (publickey, dedicated key)
  -> JSch.getSession().setPortForwardingL("127.0.0.1", 18765, "127.0.0.1", 8765)
  -> HTTP POST http://127.0.0.1:18765/v1/transcribe with the WAV
```

The forward dies when the SSH session dies; the app must recreate it on
reconnect. The ASR service itself is bound to `127.0.0.1:8765` and remains
unreachable from the public Internet.

## Next steps

1. **Re-verify int8 on glasses (in progress, 2026-08-14)** — the sherpa int8
   backend is deployed and benchmarked; the user re-tests speech input
   end-to-end on the glasses (record → upload → transcribe → draft → confirm)
   against the new backend before the code/doc changes are committed.
2. **Sync discipline** — this `third_party/asr-server` directory is the source
   of truth; every code change must be deployed to `/srv/RokidAsrServer` (see
   `## Deployment`). Never edit the Mac copy and the server copy in parallel.
3. **systemd unit** — deferred: the PAM hook already provides on-demand
   lifecycle; a systemd unit adds restart-on-crash only if desired later.
4. **Quantization — done (2026-08-14).** Default backend is sherpa-onnx int8
   (~350 MB RSS, verified). funasr fp32 stays as `ASR_BACKEND=funasr`.
   `ASR_QUANTIZE=1` (PyTorch dynamic int8) remains off; it does not reduce
   memory.
