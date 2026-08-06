# RokidTerm rules: Voice

Loaded on demand from `CLAUDE.md`. Speech input status, ASR paths, and privacy
rules. Server-side ASR service details live in
`third_party/asr-server/CLAUDE.md`; the two-account channel is in
`rules/architecture.md`.

## Voice status and next direction

- The first local Android `SpeechRecognizer` path is implemented. It declares Android 11+ visibility for `android.speech.RecognitionService`, prefers the configured secure-settings provider, falls back to the first exported provider, and requests `zh-CN` free-form partial results.
- `SpeechDraftState` anchors each recognition round at the current grapheme-aware cursor. Partial results replace one active span, final results commit it as ordinary editable draft text, and neither partial nor final text reaches the PTY until the user explicitly long-presses Send.
- Manual cursor movement, deletion, or keyboard insertion cancels the current recognition round and keeps the latest visible hypothesis as editable text. This conservative first slice prevents late callbacks from duplicating or corrupting the draft; single-click starts another round.
- A previous APK reported zero `RecognitionService` providers, but that probe occurred before the manifest package-visibility query and explicit-provider fallback were added. The new APK must be installed and probed on the glasses before claiming Android ASR support.
- If the new provider probe remains empty or the vendor service rejects arbitrary dictation, evaluate:
  - Native `AudioRecord` short capture, transfer over the authenticated channel, server-side ASR, draft confirmation.
  - Rokid AIUI/LocalSkill integration for system ASR, bridged to the SSH session.
- LocalSkill/Instruct SDKs are oriented toward wake-word skills and fixed commands; verify whether they can return arbitrary dictation before adopting them.
- Never send microphone audio to a new service without documenting transport, retention, credentials, and user confirmation. Never log partial/final transcript text.

## Verified server ASR path (2026-08-05)

- The glasses record 16 kHz mono signed-16-bit PCM in memory (never written to
  disk), upload it over the `asr-fwd` SSH forward to the server's
  `127.0.0.1:8765` `/v1/transcribe`, and insert the returned text into the
  composer draft at the cursor.
- Audio capture uses a continuous reader thread; without it AudioRecord's ring
  buffer keeps only the last ~0.6 s (measured 80 ms of a full sentence).
- The WAV header must be little-endian for its 16-bit fields
  (`DataOutputStream.writeShort` is big-endian — use `writeShortLe`).
- Server model loads lazily on the first request; the app sends a ~0.5 s silent
  WAV warm-up after the forward is established (`ASR MODEL LOADING` status),
  so the first real transcription is fast.
- Never log recognized text to logcat; the `ASR result` log line is
  sanitized. The server runs with `ASR_LOG_TRANSCRIPT=0` and deletes temporary
  WAVs after each request.

## Terminal and speech safety

- A speech transcript must be shown as a visible draft and explicitly sent
  before transmission; arbitrary speech must never become direct shell
  execution (the always-loaded invariants are in `CLAUDE.md`).
- Recording state must be visibly indicated; cancellation must discard
  unsent audio and transcript state.
- Any server-side ASR design must separately document transport,
  authentication, retention, deletion, and failure behavior.
