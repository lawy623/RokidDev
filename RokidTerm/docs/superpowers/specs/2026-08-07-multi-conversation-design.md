# Multi-Conversation Management Design (RokidTerm)

Date: 2026-08-07 · Status: design (pending implementation plan) · Scope: RokidTerm

## 1. Background and goal

RokidTerm currently launches a single Claude Code process per endpoint and
persists scrollback per endpoint. The user wants conversations separated:

- Pick a project folder and a past conversation at connect time, or mid-session,
  and switch between them from the glasses.
- Glasses-side history keyed per conversation (requirement 2026-08-06:
  "different conversations get different history files").
- Guarantee the server-side Claude session and the local record always refer to
  the same conversation (sync guarantee, user requirement 2026-08-07).

User decisions (2026-08-07):

- Two-layer picker: project (folder) → conversation. The folder level lists
  the endpoint base dir (default `/srv`) and ALL its direct subdirectories —
  every folder under the base is freely accessible, even with no prior
  sessions (user requirement 2026-08-07: "srv 内的所有文件夹都应该可以自由
  访问"). One level deep; deeper navigation is future work.
- The picker is shown on every connect (default position = new conversation in
  the remembered project; moving down selects past conversations).
- In-session entry via the command palette — no new hardware keys
  (button budget is exhausted).
- Switch mechanism = kill + relaunch via a server helper (option A).
- Native `/resume` removed from palette defaults; a sync watcher is the safety
  net for any out-of-band `/resume` (typed/speech), so no conflict is possible.

## 2. Verified facts (Claude Code, 2026-08-07, via claude-code-guide)

- Session storage: `~/.claude/projects/<cwd-encoded>/<session-id>.jsonl`;
  encoding replaces non-alphanumeric chars with `-` (e.g. `/srv` → `-srv`).
  Transcript retention defaults to 30 days.
- Non-interactive CLI flags: `claude --continue` (most recent session),
  `claude --resume <session-id|name>` (direct resume, no picker; cross-project
  ID search only v2.1.223+), `--session-id <uuid>` (force the ID of a new
  session). There is **no** plain-text session-listing flag.
- `/resume` in-TUI lists the current worktree by default; `Ctrl+A` lists all
  projects but selecting another project's session only copies a `cd`+resume
  command to the clipboard (useless headless). No `/project` or `/workspace`
  switch exists.
- `/cd <path>` (v2.1.169+) MOVES a session to another folder — not a switcher;
  not used here.
- CLAUDE.md loading walks UP from cwd at launch; subdirectory CLAUDE.md files
  load on demand when files there are read — the reason per-project launches
  give better context than starting from `/srv` only.
- No parent-process env var exposes the running session ID. Active-session
  discovery = newest JSONL by mtime in the active project dir
  (active project = the Claude process's cwd).
- History is per-user: `~` is the launching user's home. `ubuntu` and `rokid`
  never share conversation stores even for the same cwd; the app only ever sees
  `rokid`'s store.

## 3. Architecture

### 3.1 Server helper `rokid-sessions` (new, /home/rokid/bin/rokid-sessions)

Same deployment model as `rokid-commands`. Verbs, tab-separated structured
output (message bodies are NEVER printed). The endpoint's `baseDir` (default
`/srv`) is passed as the base argument to `list` and `switch`.

- `list <base-dir>` → folders and sessions:
  - `F\t<real-path>\t<encoded-dir>` for the base dir itself and each DIRECT
    subdirectory (dot-directories excluded) — the folder level is
    filesystem-based, so folders with no prior sessions are still selectable.
  - `S\t<encoded-dir>\t<session-id>\t<epoch>\t<title>` (per folder, newest 30;
    folders without sessions simply have no S lines)
  - Title = first user message, control chars stripped, truncated ≤40 chars.
  - Real path for session-backed folders comes from the JSONL `cwd` field
    (path encoding is lossy for paths containing dashes).
- `status` → `pid\t<cwd>\t<newest-session-id|->` or `none` when Claude is not
  running. The Claude process is located via the tmux pane process tree —
  never a bare `pkill -f claude`.
- `switch <tmux-session> <base-dir> <real-dir> <resume:<id>|new:<uuid>>`:
  1. Validate `<real-dir>` against the helper's own `list` output for
     `<base-dir>` (resolve and require the path to be under the base) —
     arbitrary paths are rejected.
  2. Ensure the tmux session exists (create detached if missing).
  2. Kill the current Claude process (SIGTERM, exact process match).
  3. Wait ~1–2 s for the shell prompt.
  4. `tmux send-keys "cd <dir> && /home/rokid/bin/rokid-claude
     [--resume <id> | --session-id <uuid>]" Enter`.
  5. Verify, polling ≤15 s: a Claude process exists with cwd == `<dir>`;
     for `resume:` the expected JSONL exists (it was just appended to);
     for `new:` pid+cwd suffice (the JSONL may appear only after the first
     message). Print `ok\t<encoded>\t<id>` or `error\t<msg>`.

The tmux session and the app's SSH attach stay alive throughout — only the
Claude process inside the pane is replaced. Switching interrupts any in-flight
Claude task (accepted: the session JSONL is appended per event, so the
conversation is preserved; only the running tool call is lost).

### 3.2 App changes

- `ServerSessionFetcher` (mirrors `ServerCommandFetcher`): runs
  `rokid-sessions list/status/switch` over short-lived exec channels on the
  existing SSH connection.
- `SessionPickerState` (pure JVM, like `CommandPaletteState`): two levels
  (projects → conversations), a `＋ 新对话` first item, `▶` on the current
  conversation, pre-selection from the remembered last project/session.
  Unit-tested.
- Rendering (`TerminalView`): reuse the palette list renderer for both levels;
  same strict-isolation interaction contract (navigation/confirm/cancel per
  device: TP single click / Ring touchpad single / COIDEA left knob confirm;
  Back / right knob / Ring GO double cancel).
- Entry points:
  - **Connect**: endpoint click → SSH connects → fetch list → picker
    (folders → conversations; level 1 = base dir + its direct subdirectories;
    default position = new conversation in the remembered folder, falling back
    to the base dir if the remembered folder no longer exists) →
    `switch`-style launch → terminal. The launch command is now parameterized
    (dir + resume-id or app-generated session-id), replacing the fixed
    launcher invocation. The fixed binary `/home/rokid/bin/rokid-claude`
    remains; args are whitelisted only.
  - **In-session**: command palette gains a local action item `[切换对话]`
    right after the bare `/` (local action — selecting it closes the
    palette/composer and opens the session picker; nothing is sent to the
    PTY until the final switch executes).
  - Palette defaults drop `/resume` and `/continue` (superseded by the local
    picker; `/continue` ≈ picker's default position).
- Scrollback re-keying: files become
  `scrollback_<endpoint>_<projectKey>_<sessionId>.txt` (projectKey = server
  encoded dir). Bounds: ≤1000 rows/file; ≤30 files, LRU by mtime. On bind,
  import the target file into the in-memory scrollback (instant browsable
  history); on switch, persist the current scrollback to its file first, then
  rebind.
- New-session IDs are app-generated UUIDs passed via `--session-id` — the
  local filename and the server session ID match by construction.
- **Sync watcher**: while connected, every 30 s run `status`; if the active
  session changed (e.g. manual `/resume` through the native picker), persist
  to the old file, rebind to the new one, and toast `已切换到 <title>`.
  Local files are caches; the server JSONL is authoritative — never bind
  local history unless the watcher reconciles.
- Remember per endpoint (SharedPreferences): last project dir + last session
  id, for picker pre-selection.

### 3.3 Sync guarantee (user requirement)

1. Launch always pins the target: explicit `cd` + `--resume <id>` /
   `--session-id <uuid>` — no reliance on "most recent" heuristics.
2. Post-switch verification loop inside the helper + failure banner in the
   app on timeout.
3. 30 s sync watcher reconciles any out-of-band session change.

## 4. Security and privacy

- Only the fixed launcher binary is executed. The app's args are whitelisted
  flags with server-derived IDs (`--resume <uuid-from-list>`,
  `--session-id <app-generated-uuid>`). Endpoint profiles still cannot supply
  arbitrary commands.
- The helper runs as the unprivileged `rokid` user and only reads
  `~/.claude/projects/*/` and lists the base dir — file enumeration, never
  pixel scraping. `switch` rejects any target outside the base dir.
- Never print message bodies; titles are truncated and control-stripped.
  The app must not log titles or session IDs to logcat (existing invariant:
  no conversation content in logs).

## 5. Edge cases

- Switch while Claude is busy → task interrupted, conversation preserved;
  the app shows a switch-in-progress banner.
- Claude version prerequisites: verify `--resume <id>` and `--session-id`
  support (`claude --version`) at implementation start; fallback = post-launch
  `status` probing to discover the active session ID.
- `status` with no Claude running → keep the current binding, retry next tick.
- A folder with no prior sessions shows only `＋ 新对话` on the second level
  (no history; the JSONL store for that cwd appears only after first use).
- Switch failure → rebind back to the old file, error banner, terminal stays
  usable.
- Concurrent conversations (one tmux session per chat) are explicitly out of
  scope — server cost and complexity for no real need.

## 6. Testing

- JVM: `SessionPickerState` (two levels, pre-selection, new-item, current
  marker), helper output parsing (titles with odd characters, lossy paths,
  empty folders, dot-directories excluded), scrollback file keying + LRU
  prune.
- Device checklist: connect picker flow on glasses; in-session switch with all
  three input devices; manual `/resume` convergence via the watcher; storage
  prune; failure path.
- Regression: `ScrollCaptureRegressionTest` must stay green (no capture
  changes expected — full redraws already re-baseline).

## 7. Requirements traceability

- User 2026-08-06: different conversations get different history files →
  §3.2 scrollback re-keying.
- User 2026-08-07: two layers; pick at connect; in-session via palette (no new
  keys); server+local sync guaranteed → §3.
