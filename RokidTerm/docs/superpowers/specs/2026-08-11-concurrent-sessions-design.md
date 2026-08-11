# Concurrent Sessions Design (RokidTerm)

Date: 2026-08-11 · Status: design (pending implementation plan) · Scope: RokidTerm

## 1. Background and goal

Since 2026-08-07 every conversation switch KILLS the previous Claude process
(`rokid-sessions switch` respawns Claude in the single tmux pane) and only ONE
conversation can be alive per endpoint at a time. The user now wants concurrent
sessions (option B, deferred 2026-08-10, user message 2026-08-11):

- When the user switches conversations or exits the app, the remote Claude
  keeps running; re-selecting the conversation resumes the UNINTERRUPTED
  process (a running task continues in the background while the user works in
  another conversation).
- Deleting a conversation ends its remote tmux container (no resource leak);
  non-deleted conversations stay alive in the background for the next pick.
- 2-3 concurrent conversations is the expected normal; NO enforcement — the
  user manages it themselves ("正常情况下你应该不用干涉用户").
- Existing front-end interaction, per-conversation input history, and
  per-conversation scrollback/transcript isolation must be preserved.

User decisions (2026-08-11, confirmed one by one):

- **Approach B**: shared tmux session + one tmux WINDOW per conversation
  (window name `rokid-<conversation-id>`). Chosen over per-conversation tmux
  SESSIONS because the app's SSH attach, all helper protocol strings, and the
  state machine stay byte-identical — the change lives almost entirely in the
  server helper, minimizing regression risk on the mature app.
- **Switch never restarts**: switching (or re-selecting the current
  conversation) only re-attaches; the Claude process is untouched.
- **Dead process**: when a conversation's Claude dies (crash or explicit
  `/exit`) the window stays at a shell — no auto-respawn; re-selecting the
  conversation respawns it with `--resume` (full transcript restored).
- **No concurrency cap**: documentation-only recommendation (2-3).

## 2. Current architecture (facts)

- One tmux session `endpoint.sessionName` (default `rokid-claude`). The app's
  SSH shell channel runs `exec tmux attach-session -t <session>` after
  ensuring the session exists as a plain shell.
- `rokid-sessions switch <session> <base> <dir> <resume:id|new:uuid>`:
  `tmux respawn-pane -k` KILLS the previous Claude and launches the new one in
  the same pane; fallback kill+send-keys; verifies pid+cwd (≤15 s); for
  `resume:` additionally verifies the JSONL appears (≤5 s).
- `rokid-sessions status <session>`: pane pid → first claude descendant →
  cwd + "newest session id in cwd" (`ls -t <dir>/*.jsonl | head -1`).
- `rokid-sessions delete <session> <base> <dir> <id>`: refuses when the pane's
  claude has cwd == dir and newest == id; then `rm` the JSONL.
- The app polls `status` every 30 s (`pollSessionSync`) and rebinds local
  scrollback/input-history when cwd or session id changed (manual `/resume`,
  `/cd`). Fresh new chats hold the watcher off (`newSessionPending`) until
  `discoverNewSessionId` converges the real id (the JSONL appears on the first
  message; the server MAY ignore `--session-id`).
- Local isolation is already per conversation: scrollback
  `scrollback_<endpoint>_<folderKey>_<sessionId>`, input history
  `input_history_<folderKey>_<sessionId>.txt`.

**Key property already true today**: app exit/SSH disconnect only detaches the
tmux client; the Claude process keeps running. The missing pieces are:
switching must not kill, multiple conversations must coexist, and re-select
must find the right running process.

## 3. Architecture (approach B)

### 3.1 tmux model

One tmux session (name unchanged) containing one **window per conversation**.
Window name = `rokid-<conversation-id>` (ids are unique, charset
`[A-Za-z0-9_-]`). The session's ACTIVE window is what the attached app shows;
detach/reattach preserves the active window.

- The helper creates the session with its first window already running Claude:
  `tmux new-session -d -s <session> -n rokid-<id> -c <dir> <launch>`.
- `EndpointProfile.remoteCommand`'s ensure-session remains (plain shell) — it
  only fires when the helper never ran (legacy path); harmless because the
  helper-created session makes it a no-op, and the conversation window is
  always selected before the app attaches.
- All app-side protocol arguments (`switch`/`status`/`delete` pass
  `endpoint.sessionName`) are UNCHANGED.

### 3.2 Conversation identification (the core problem)

"Which conversation is this window's Claude process in?" cannot use the
dir-newest-JSONL heuristic anymore: with multiple concurrent processes in the
same folder it is global per directory (a background conversation finishing a
task would re-order newest and the watcher would rebind the user to the wrong
conversation). Per-process identification, in priority order:

1. **Open-JSONL fd scan** (primary, CURRENT): `readlink /proc/<pid>/fd/*`,
   basename without `.jsonl` = the transcript the process is actively writing.
   Survives in-session `/resume`. Scan the claude descendant AND its children
   (the launcher wrapper may not exec).
2. **Launch cmdline** (fallback, STALE after in-session `/resume`):
   `ps -o args=` and extract `--resume <id>` / `--session-id <id>`.

Used by `status`, `switch` (find), and `delete` (find). `/proc` is the
deployment host's primary path; lsof fallback mirrors the existing `pid_cwd`
pattern so macOS test hosts exercise the same code.

### 3.3 Helper protocol changes (`server/rokid-sessions`)

| Verb | Behavior (new) |
|---|---|
| `switch <session> <base> <dir> <resume:id\|new:uuid>` | **Attach semantics**: (a) no session → `new-session -n rokid-<id>` running Claude; (b) a window whose process identification == `id` exists → select it, rename-window to `rokid-<id>` if its name is stale; (c) window named `rokid-<id>` exists → if Claude dead, respawn in place (`-c dir`, kill+send-keys fallback), select; (d) else `new-window -n rokid-<id>` + launch, select. Verify pid+cwd (≤15 s) and `resume:` JSONL (≤5 s) unchanged. `ok\t<enc>\t<id>` unchanged. |
| `status <session>` | Active window only: pane pid → claude descendant → cwd → process identification. **Self-healing**: if identified id != window name, rename-window to `rokid-<id>`. Output `pid\t<cwd>\t<id>` unchanged (id `-` when unidentified). Dead claude → `none` (unchanged). |
| `delete <session> <base> <dir> <id>` | Find the window for `id` (identification; else named-window fallback: kill when its Claude is dead or was launched for `id`, SKIP when it is alive but runs another conversation). Refuse `error\tactive session` only when the window is the session's active window AND a client is attached. Then `tmux kill-window` (kills the process) + `rm` JSONL. `ok\t…` unchanged. |
| `adopt <session> <dir> <new-id>` | NEW. Rename the ACTIVE window to `rokid-<new-id>` — guarded: only when the active window's claude is alive and its cwd == dir. `ok\t<enc>\t<id>` output. Used by the app's new-chat discover convergence. |

`list` and `export` unchanged.

### 3.4 Self-healing scenarios

- **In-session `/resume` to Y** (window `rokid-<X>` now runs Y): `status`
  identifies Y → renames the window to `rokid-<Y>`; the app's existing watcher
  rebinds local history and toasts. A later switch to Y selects the SAME
  process (no duplicate). Residual race: switch to Y within the fd-closed +
  cmdline-stale window (idle) → duplicate process possible (see §5).
- **`--session-id` ignored by the server** (window `rokid-<uuid>` runs Z):
  the app's discover convergence (re-list based, §3.5) calls `adopt` → window
  renamed `rokid-<Z>` → later switches find it by name. Best-effort: an adopt
  failure leaves the window stale-named, and a subsequent switch still finds
  the process by identification when it is active.
- **Legacy upgrade**: the old session's first window (un-named or `rokid-<old
  id>`) keeps running its conversation; the next switch/status identifies and
  renames it. No data loss.
- **Window named `rokid-<X>` running something else** (stale): never killed by
  delete of X; it is reclaimed when its REAL conversation is deleted
  (identification finds it) or renamed at the next switch.

### 3.5 App-side changes (minimal)

1. `EndpointProfile.remoteCommand` — `TMUX_STATUS_OPTIONS` status-left:
   `[#{session_name}]` → `[#{window_name}]` (cosmetic: shows the conversation
   window in the status bar).
2. `discoverNewSessionId` — replace the status-poll data source with a folder
   **re-list poll** (newest session file != previous id), which is independent
   of fd state; on convergence, best-effort `adopt` call (new fetcher method).
3. Everything else is untouched: `switchToTarget`, `pollSessionSync`,
   `reconcileBindingFromStatus`, `runDeleteConversation`, picker, composer,
   ask panel, input history, scrollback.

## 4. Behavior contract (user-confirmed 2026-08-11)

- Switching = attach; the running Claude is never restarted by a switch.
- Re-selecting the CURRENT conversation is a no-op switch (today it restarts).
- A conversation's dead Claude stays dead at its shell; re-selecting respawns
  with `--resume` (transcript restored from JSONL).
- Deleting a conversation ends its window + Claude process; the running
  conversation (▶) is not deletable (app blocks; helper refuses when the
  active window has an attached client).
- No concurrency enforcement; docs recommend 2-3 concurrent conversations.
- Local isolation is unchanged: scrollback and input history stay keyed per
  conversation; the server JSONL stays authoritative.

## 5. Known limitations (accepted)

- **Residual duplicate race**: in-session `/resume` to Y, then switching to Y
  while Y's process holds no open JSONL fd AND its cmdline is stale (idle)
  → the helper cannot identify it and creates a second process for Y; both
  append the same JSONL. Rare (requires picking the resumed conversation
  within the fd-closed idle window); the identification scan re-selects the
  original process in every other case.
- **Idle-after-`/resume` misidentification**: with no open fd, cmdline reports
  the launch id — `status` reports the stale id until the process writes
  again; the app's binding then self-corrects on next activity (fd opens).
- **Status bar length**: `status-left-length` 20 truncates long window names —
  cosmetic.

## 6. Testing plan

1. Helper harness on macOS (tmux + fake `rokid-claude` script; `/proc` absent
   → lsof fallback path): create/switch-attach/respawn/kill/delete, window
   rename self-healing, stale-window delete skip, active-window refusal.
2. JVM unit tests: discover re-list logic, adopt parser, fetcher method.
3. Server-side smoke over SSH (deploy helper + fake claude against real
   tmux): two concurrent conversations, background task survival across
   switches, delete kills the process.
4. On-glasses checklist delivered with the implementation (user packages and
   tests).
