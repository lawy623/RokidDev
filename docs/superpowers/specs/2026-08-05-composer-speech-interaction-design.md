# Composer Speech Interaction v2 — Design

Date: 2026-08-05
Status: Approved by user (interactive hardware session)

## Problem

Hardware test on 2026-08-05 exposed five defects in the composer speech flow:

1. **Auto-start records noise without control.** Opening the composer immediately
   starts recording (logcat: `openComposer` → `startSpeech` at 10:19:43). There is
   no visible manual record control, so background noise gets recorded and the
   user must know to click again to stop before any text appears.
2. **`AudioRecorder.stop()` hangs.** The drain loop reads while
   `recordingState == RECORDING`, but `record.stop()` is only called after the
   loop — with a live mic the loop never exits. Confirmed on hardware: after the
   stop click (10:23:47) the `asr-controller` thread blocked in `read()`
   indefinitely; the single-thread executor is then poisoned for all future ASR
   operations (connect/transcribe queue behind the hung task).
3. **Recording state leaks across composer close.** `cancelComposer()` cancels
   only the local `SpeechInput`, not the `AsrController` recorder —
   `asrListening` stayed true after closing the composer (verified in logcat:
   `asrListening=true` on the next open at 10:23:47).
4. **Send does not cancel an in-flight recording.** A late transcribe result
   could insert text into the draft after the user already sent it.
5. **Header shows composer state.** `cancelComposer("CANCELLED")` writes
   "CANCELLED" directly into the terminal state line via
   `terminalView.setState(status)`, bypassing `updateHeader()`.

## Goals

- Explicit, predictable recording control inside the composer: click to start,
  click to stop + transcribe; rounds repeat.
- Sending or discarding always works regardless of recording state; an in-flight
  recording is canceled and its audio dropped — never transcribed, never
  inserted later.
- Header shows only connection + ASR service state (`sshState / asrStatus`).
- `AudioRecorder.stop()` is bounded and cannot hang.

## Interaction design (composer open)

| Action | Not recording | Recording |
|---|---|---|
| Single click | Start recording; hint `RECORDING` | Stop + transcribe; hint `TRANSCRIBING`; text inserts at draft cursor; back to idle hint |
| Long press | Send draft | Cancel recording, drop audio, send draft |
| Double click | Discard draft + close | Cancel recording, drop audio, discard draft + close |
| Back | Discard draft + close | Cancel recording, drop audio, discard draft + close |

Idle hint after open: `CLICK TO RECORD / LONG TO SEND`.

Rounds: each click pair (start → stop + transcribe) is one recognition round;
results anchor at the current grapheme-aware cursor (existing
`SpeechDraftState` semantics). The server path (`AsrController`) inserts final
text only — no partial hypotheses on this path.

## Error handling

- Empty capture → hint `NO AUDIO / MIC MUTED`; draft untouched.
- ASR channel down → hints `ASR NOT CONNECTED` / `ASR NOT READY`; draft
  untouched.
- All hints are composer-local; the header never shows composer states.

## Code changes

- `AudioRecorder.stop()` — call `record.stop()` before draining; bounded drain
  (`read() <= 0` terminates the loop); `release()` always. Must also terminate
  promptly when the underlying stream is dead.
- `MainActivity`:
  - `openComposer` / `startSpeech` — no auto-start; the first click starts
    recording.
  - `sendComposer` — cancel any in-flight recording first (drop audio), then
    send the draft.
  - `cancelComposer` — call `asr.cancelRecording()`; header via `updateHeader()`
    instead of `terminalView.setState(status)`.
  - Composer status text updates (`RECORDING` / `TRANSCRIBING` / idle hint).
- `AsrController` — interface unchanged; `cancelRecording()` already exists and
  is reused by the new cancel/send paths.

## Model warm-up on connect (approved 2026-08-05)

"ASR READY" currently means only the asr-fwd SSH forward is up; the SenseVoice
model loads lazily on the first transcription (~30 s cold load, 24-90 s per
benchmark docs). Change: after the forward is established, the app immediately
posts a ~0.5 s silent WAV to `/v1/transcribe`, which triggers the model load.

- App status flow: `ASR CONNECTING` → `ASR MODEL LOADING` → `ASR READY`.
- `shortAsrStatus` passes `ASR MODEL LOADING` through (previously any unknown
  "ASR ..." value mapped to `ASR FAIL`).
- Warmup retries a few times (server boot race after a debounce stop + fresh
  login); failure → `ASR MODEL LOAD FAIL` → header shows `ASR FAIL`.
- No server-side change: the lazy load is triggered by the warmup request.
- Unchanged: on exit, uvicorn is killed ~60 s after the rokid session closes
  (~1.9 GiB freed) and the app closes both SSH channels.

## Non-goals

- Client VAD / trailing-silence auto-stop — deferred.
- Two-finger push-to-talk — deferred until the gesture is profiled on this
  device/firmware (no verified KeyEvent/MotionEvent mapping; a configured AI
  shortcut may consume the gesture first).
- Local `SpeechRecognizer` path — unchanged.

## Verification

Hardware (glasses + server via SSH):

1. Install new APK (also recovers the executor poisoned by the hang).
2. Connect → open composer → idle hint shown; no recording (no input stream in
   `dumpsys media.audio_flinger`).
3. Click → `RECORDING`; speak; click → `TRANSCRIBING`; text appears in the
   draft; server access log shows `POST /v1/transcribe`.
4. Long-press mid-recording → draft sends; no later draft insertion (no
   `ASR result` log after send).
5. Double-click / Back mid-recording → composer closes; next open logs
   `asrListening=false`.
6. Header shows `CONNECTED / ASR READY` after composer close — never
   `CANCELLED`.
7. Exit terminal → server journal: `scheduling ASR stop` → 60 s later
   `stopping ASR`.
8. Connect → header shows `ASR MODEL LOADING` then `ASR READY`; the first
   transcription completes in ~1 s (model already warm), server log shows one
   extra (silent) transcribe request for the warmup.
