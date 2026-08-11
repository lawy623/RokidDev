# Concurrent Sessions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** RokidTerm conversations become concurrent — each conversation runs its own Claude process in its own tmux window of the shared session; switching re-attaches instead of restarting, deleting ends the window, and idle background conversations are swept.

**Architecture:** All protocol strings and the app's SSH attach stay byte-identical. The server helper `server/rokid-sessions` is rewritten around per-conversation tmux WINDOWS (`rokid-<id>`), with per-process conversation identification (open-JSONL fd scan, cmdline fallback) replacing the dir-newest heuristic. The app gains two small call sites (discover convergence → `adopt`; watcher → `sweep`) plus one cosmetic status-bar change. See `docs/superpowers/specs/2026-08-11-concurrent-sessions-design.md`.

**Tech Stack:** bash (helper + harness), tmux, /proc + lsof, Kotlin (app), JUnit4 (app unit tests), Android Gradle.

## Global Constraints

- App-side protocol arguments are UNCHANGED: `switch`/`status`/`delete` still take `endpoint.sessionName` as the tmux-session argument; output formats stay `pid\t<cwd>\t<id>`, `ok\t<enc>\t<id>`, `error\t…`, `none`.
- tmux window name = `rokid-<conversation-id>`; ids match `[A-Za-z0-9_-]`; window names the helper creates never contain spaces.
- Never parse terminal pixels; structured tab-separated output only; never print conversation ids, titles, or message bodies in the helper or app logs (privacy).
- The helper is pure bash 4, `set -u`, no external deps beyond `tmux`, `ps`, `pgrep`, `readlink`/`lsof`, `stat`, `python3` (unchanged).
- Idle sweep: default 180 min; the ACTIVE window is never swept; kill only via `tmux kill-window`; the conversation's JSONL is never deleted by sweep; a window is killed only when ALL THREE idle signals say idle.
- Concurrency is NOT enforced anywhere.
- App-side JSch quirks (from project CLAUDE.md): exec channels never deliver EOF on this firmware — readers return on a quiet period (750 ms), never discard bytes on timeout; `ByteArrayOutputStream.toString(Charset)` does NOT exist on the firmware — use `toString("UTF-8")`; never put `|| true` before appended helper verbs.
- Helper env hooks for testability: `ROKID_SESSIONS_PROJECTS_DIR` (default `$HOME/.claude/projects`) and `ROKID_SESSIONS_LAUNCHER` (default `/home/rokid/bin/rokid-claude`); sweep sample sleep `SWEEP_SAMPLE_SLEEP` (default 120).
- App unit tests run with `cd RokidTerm && ./gradlew :app:testDebugUnitTest` (JAVA_HOME export per project CLAUDE.md).
- User's commit convention (2026-08-10): per-task commits are fine; at PUSH time the batch is consolidated into one commit.

## File Structure

- `server/rokid-sessions` (modify) — all helper behavior; the bulk of the change.
- `server/test/fake-rokid-claude` (create) — fake Claude binary for harness tests.
- `server/test/helper_test.sh` (create) — harness runner + scenarios.
- `app/src/main/java/com/rokid/terminal/ServerSessionFetcher.kt` (modify) — `adoptConversation`, `sweepIdle`, `parseSweepResult`, `newestUnboundSession`.
- `app/src/main/java/com/rokid/terminal/MainActivity.kt` (modify) — discover rework (re-list + adopt), sweep runnable, constants.
- `app/src/main/java/com/rokid/terminal/EndpointProfile.kt` (modify) — status-left cosmetic.
- `app/src/test/java/com/rokid/terminal/ServerSessionFetcherParseTest.kt` (modify) — new parser/helper tests.
- `app/src/test/java/com/rokid/terminal/EndpointProfileTest.kt` (modify) — status-left assertion update.
- `RokidTerm/CLAUDE.md` (modify) — Open/pending update.

---

### Task 1: Helper env hooks, identification primitives, harness infrastructure

**Files:**
- Modify: `server/rokid-sessions` (top constants + new functions after `pid_cwd`, before `list_base`)
- Create: `server/test/fake-rokid-claude` (executable)
- Create: `server/test/helper_test.sh` (executable)
- Test: `server/test/helper_test.sh` scenarios `test_identification_*`

**Interfaces:**
- Produces (used by Tasks 2-5):
  - `open_transcript_id <pid>` → prints a conversation id (basename of an open `.jsonl` fd, no extension) or nothing
  - `window_conversation_id <session> <window>` → prints the id the window's claude currently runs (fd scan primary, cmdline fallback) or nothing
  - `window_has_claude <session> <window>` → exit 0 if the window's claude descendant exists
  - `active_window <session>` → prints the session's active window name
  - `session_attached <session>` → exit 0 if any client is attached
  - `find_window_for <session> <id>` → prints the first window whose `window_conversation_id` == id, else nothing
  - `cpu_ticks <pid>` → prints utime+stime for the process
  - Env hooks: `ROKID_SESSIONS_PROJECTS_DIR`, `ROKID_SESSIONS_LAUNCHER`, `SWEEP_SAMPLE_SLEEP`

- [ ] **Step 1: Write the harness infrastructure + fake claude**

Create `server/test/fake-rokid-claude` (executable, `chmod +x`):

```bash
#!/usr/bin/env bash
# Fake /home/rokid/bin/rokid-claude for helper harness tests. The pane runs
# this script, so its cmdline contains "claude" (findable by
# first_claude_descendant). Env controls:
#   ROKID_FAKE_JSONL=<path>  hold an fd open to this transcript file
#   ROKID_FAKE_CHILD=1       spawn a sleeping child (busy-descendant signal)
#   ROKID_FAKE_CPU=<secs>    burn that many CPU-seconds in a loop
#   ROKID_FAKE_LOG=<path>    append one "start" line per launch
id=""
while [ $# -gt 0 ]; do
  case "$1" in
    --resume) id="$2"; shift 2 ;;
    --session-id) id="$2"; shift 2 ;;
    *) shift ;;
  esac
done
echo "start id=${id:-}" >> "${ROKID_FAKE_LOG:-/dev/null}"
if [ -n "${ROKID_FAKE_JSONL:-}" ]; then exec 3>>"$ROKID_FAKE_JSONL"; fi
if [ "${ROKID_FAKE_CHILD:-0}" = "1" ]; then sleep 300 & fi
if [ "${ROKID_FAKE_CPU:-0}" -gt 0 ]; then
  end=$(( $(date +%s) + ROKID_FAKE_CPU ))
  while [ "$(date +%s)" -lt "$end" ]; do :; done
fi
sleep 300
```

Create `server/test/helper_test.sh` (executable) — the harness. It must:

```bash
#!/usr/bin/env bash
# RokidTerm rokid-sessions harness. Requires tmux. Run: bash helper_test.sh [filter]
# Each scenario creates its own tmux session (killed on teardown) and its own
# throwaway project/workspace dirs, so scenarios never interfere.
set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
HELPER="$ROOT/../rokid-sessions"
FAKE="$ROOT/fake-rokid-claude"
SESSION="rokid-harness-$$"
PASS=0; FAIL=0; FAILED_CASES=""
FILTER="${1:-}"

setup() {   # per-scenario: fresh tmp dirs + a session named $SESSION
  TEST_TMP="$(mktemp -d)"
  BASE="$TEST_TMP/base"; PROJECTS="$TEST_TMP/projects"
  mkdir -p "$BASE/proj" "$PROJECTS"
  export ROKID_SESSIONS_PROJECTS_DIR="$PROJECTS"
  export ROKID_SESSIONS_LAUNCHER="$FAKE"
  export ROKID_FAKE_LOG="$TEST_TMP/launch.log"
  tmux kill-session -t "$SESSION" 2>/dev/null
}
teardown() {
  tmux kill-session -t "$SESSION" 2>/dev/null
  rm -rf "$TEST_TMP"
}
# Run a scenario function named by $1 under setup/teardown; FAIL on error.
run_case() {
  local name="$1"
  if [ -n "$FILTER" ] && [[ "$name" != *"$FILTER"* ]]; then return 0; fi
  setup || { echo "setup failed"; exit 1; }
  if "$name"; then PASS=$((PASS+1)); echo "PASS $name"; else FAIL=$((FAIL+1)); echo "FAIL $name"; FAILED_CASES="$FAILED_CASES $name"; fi
  teardown
}
# Assertions: assert_eq <expected> <actual>
assert_eq() { [ "$1" = "$2" ] || { echo "  assert_eq failed: expected [$1] got [$2]"; return 1; }; }
# Encode a path the same way the helper does (tr -c 'A-Za-z0-9' '-').
enc() { printf '%s' "$1" | tr -c 'A-Za-z0-9' '-'; }

main() {
  # scenario functions are defined below main() in each task's step
  : # run_case calls are appended per task
}
main
```

- [ ] **Step 2: Add the identification scenarios to the harness**

Append to `helper_test.sh` (before the harness's own `main()`), one function per scenario:

```bash
# A fake claude's launch cmdline identifies the conversation (cmdline fallback).
test_identification_cmdline() {
  local dir="$BASE/proj"
  tmux new-session -d -s "$SESSION" -n "rokid-abc123" -c "$dir" \
    "$FAKE" --effort max --dangerously-skip-permissions --resume abc123
  sleep 0.5
  assert_eq "abc123" "$(window_conversation_id "$SESSION" "rokid-abc123")" || return 1
}

# The open transcript fd identifies the CURRENT conversation (beats a stale
# cmdline — the in-session /resume case).
test_identification_fd_beats_cmdline() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  echo '{"type":"user"}' > "$PROJECTS/$enc/newer.jsonl"
  tmux new-session -d -s "$SESSION" -n "rokid-abc123" -c "$dir" \
    ROKID_FAKE_JSONL="$PROJECTS/$enc/newer.jsonl" \
    "$FAKE" --effort max --dangerously-skip-permissions --resume abc123
  sleep 0.5
  assert_eq "newer" "$(window_conversation_id "$SESSION" "rokid-abc123")" || return 1
}

# No launch args and no open fd -> unidentified (empty).
test_identification_unidentified() {
  local dir="$BASE/proj"
  tmux new-session -d -s "$SESSION" -n "rokid-abc123" -c "$dir" "$FAKE"
  sleep 0.5
  assert_eq "" "$(window_conversation_id "$SESSION" "rokid-abc123")" || return 1
}

# A window without a claude descendant -> unidentified (empty).
test_identification_no_claude() {
  local dir="$BASE/proj"
  tmux new-session -d -s "$SESSION" -n "rokid-abc123" -c "$dir" "sleep 300"
  assert_eq "" "$(window_conversation_id "$SESSION" "rokid-abc123")" || return 1
}
```

Note: `tmux new-session -c "$dir" ENV=... CMD` — pass env by prefixing the command (`tmux ... ENV=val cmd args`); tmux runs the command through the pane's shell, so `ROKID_FAKE_JSONL=path "$FAKE" ...` works.

The harness sources the helper so it can call the internal functions directly — the helper gains a source guard (Step 4). Update `helper_test.sh`'s header section: after `FILTER=...`, add `export ROKID_SESSIONS_SOURCE=1` and `. "$HELPER"` BEFORE the harness defines its own `main()` (the helper's `main` definition is harmless — the harness's `main` defined after sourcing wins; the guard prevents the helper's `main "$@"` from RUNNING at source time). The harness's `main()` then calls the scenarios with `run_case`, and `run_helper_status_id` (used from Task 3 on) is:

```bash
run_helper_status_id() {
  "$HELPER" status "$SESSION" 2>/dev/null | awk -F '\t' '$1=="pid"{print $3}'
}
```

- [ ] **Step 3: Run the harness to verify the scenarios fail**

Run: `cd RokidTerm/server/test && bash helper_test.sh identification`
Expected: FAIL on the identification scenarios (the helper has no `window_conversation_id` yet — sourcing errors or the function is missing).

- [ ] **Step 4: Add the env hooks, identification functions, and source guard to the helper**

In `server/rokid-sessions`, change the two top constants:

```bash
PROJECTS_DIR="${ROKID_SESSIONS_PROJECTS_DIR:-$HOME/.claude/projects}"
CLAUDE_LAUNCHER="${ROKID_SESSIONS_LAUNCHER:-/home/rokid/bin/rokid-claude}"
```

After `pid_cwd()` (which ends before `list_base()`), add:

```bash
# Transcript file currently open by a claude process: /proc fd-scan on the
# deployment host (Linux), lsof fallback for macOS test hosts. Prints the
# conversation id (basename without .jsonl) or nothing.
open_transcript_id() {
  local pid="$1" t
  if [ -d "/proc/$pid/fd" ]; then
    t="$(for f in /proc/$pid/fd/*; do readlink "$f" 2>/dev/null; done | grep -m1 '\.jsonl$')"
  else
    t="$(lsof -a -p "$pid" -Fn 2>/dev/null | sed -n 's/^n//p' | grep -m1 '\.jsonl$')"
  fi
  [ -n "$t" ] || return 0
  basename "$t" .jsonl
}

# The conversation id a window's claude currently runs. Primary: an open
# transcript fd on the claude process (or any claude descendant — the
# launcher wrapper may not exec), which is CURRENT across in-session
# /resume. Fallback: the launch cmdline (--resume <id> / --session-id
# <id>), which is STALE after an in-session /resume. "" when unidentified.
window_conversation_id() {
  local session="$1" win="$2" pane_pid id pid
  pane_pid="$(tmux list-panes -t "$session:$win" -F '#{pane_pid}' 2>/dev/null | head -1)"
  [ -n "$pane_pid" ] || return 0
  for pid in $(descendants "$pane_pid"); do
    ps -p "$pid" -o args= 2>/dev/null | grep -q '[c]laude' || continue
    id="$(open_transcript_id "$pid")"
    [ -n "$id" ] && { echo "$id"; return 0; }
  done
  local claude args
  claude="$(first_claude_descendant "$pane_pid")"
  [ -n "$claude" ] || return 0
  args="$(ps -p "$claude" -o args= 2>/dev/null)"
  id="$(printf '%s' "$args" | grep -oE -- '--(resume|session-id) [A-Za-z0-9_-]+' | head -1 | awk '{print $2}')"
  [ -n "$id" ] && echo "$id"
  return 0
}

# Exit 0 when the window has a live claude descendant.
window_has_claude() {
  local session="$1" win="$2" pane_pid
  pane_pid="$(tmux list-panes -t "$session:$win" -F '#{pane_pid}' 2>/dev/null | head -1)"
  [ -n "$pane_pid" ] || return 1
  [ -n "$(first_claude_descendant "$pane_pid")" ]
}

# Name of the session's active (selected) window.
active_window() {
  local session="$1"
  tmux display-message -p -t "$session" '#{window_name}' 2>/dev/null
}

# Exit 0 when the session has at least one attached client.
session_attached() {
  local session="$1"
  [ -n "$(tmux list-clients -t "$session" 2>/dev/null)" ]
}

# First window whose claude currently runs conversation $2; "" when none.
find_window_for() {
  local session="$1" id="$2" win
  for win in $(tmux list-windows -t "$session" -F '#{window_name}' 2>/dev/null); do
    [ "$(window_conversation_id "$session" "$win")" = "$id" ] && { echo "$win"; return 0; }
  done
  return 0
}

# Cumulative CPU ticks (utime+stime) of a process; 0 when gone.
cpu_ticks() {
  local pid="$1" u s
  u="$(ps -o utime= -p "$pid" 2>/dev/null | tr -d ' ')"
  s="$(ps -o stime= -p "$pid" 2>/dev/null | tr -d ' ')"
  echo $(( ${u:-0} + ${s:-0} ))
}
```

And change the last line of the file from `main "$@"` to the source guard:

```bash
# Testability hook (design 2026-08-11): sourcing with ROKID_SESSIONS_SOURCE=1
# defines the functions without running main() — the harness
# (server/test/helper_test.sh) exercises internals directly.
[ "${ROKID_SESSIONS_SOURCE:-0}" = "1" ] || main "$@"
```

- [ ] **Step 5: Run the harness to verify the scenarios pass**

Run: `cd RokidTerm/server/test && bash helper_test.sh identification`
Expected: PASS on `test_identification_cmdline`, `test_identification_fd_beats_cmdline`, `test_identification_unidentified`, `test_identification_no_claude`.

- [ ] **Step 6: Commit**

```bash
git add server/rokid-sessions server/test/
git commit -m "feat(server): per-window conversation identification + test harness"
```

---

### Task 2: Helper `switch` rewrite (attach semantics)

**Files:**
- Modify: `server/rokid-sessions` (`cmd_switch`, `main` dispatch unchanged for switch)
- Test: `server/test/helper_test.sh` scenarios `test_switch_*`

**Interfaces:**
- Consumes: `find_window_for`, `window_has_claude`, `active_window`, `first_claude_descendant`, `pid_cwd`, `encode` (all from Task 1 or existing)
- Produces: `cmd_switch` with attach semantics; output contract unchanged (`ok\t<enc>\t<id>`)

- [ ] **Step 1: Write the switch scenarios (failing)**

Append to `helper_test.sh`:

```bash
# First switch on a fresh server: session + window created, claude running.
test_switch_creates() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  local out
  out="$("$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111")" || return 1
  assert_eq "ok	$enc	aaa111" "$out" || return 1
  sleep 0.5
  tmux has-session -t "$SESSION" 2>/dev/null || { echo "  no session"; return 1; }
  assert_eq "rokid-aaa111" "$(tmux list-windows -t "$SESSION" -F '#{window_name}')" || return 1
  assert_eq "aaa111" "$(run_helper_status_id)" || return 1
}

# Switching to a conversation whose window ALREADY runs it: no restart —
# the fake's launch log gains no new "start" line.
test_switch_selects_without_restart() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111" >/dev/null || return 1
  sleep 0.5
  local before after
  before="$(wc -l < "$ROKID_FAKE_LOG")"
  local out
  out="$("$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111")" || return 1
  assert_eq "ok	$enc	aaa111" "$out" || return 1
  sleep 0.5
  after="$(wc -l < "$ROKID_FAKE_LOG")"
  assert_eq "$before" "$after" || return 1
  assert_eq "1" "$(tmux list-windows -t "$SESSION" -F '#{window_name}' | wc -l | tr -d ' ')" || return 1
}

# A dead conversation's window is respawned in place (one new launch line).
test_switch_respawns_dead() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111" >/dev/null || return 1
  sleep 0.5
  # Kill the fake claude process ONLY — the window remains with a dead pane.
  pkill -f "fake-rokid-claude" 2>/dev/null
  sleep 0.5
  [ -n "$(tmux list-windows -t "$SESSION" -F '#{window_name}' | grep 'rokid-aaa111')" ] \
    || { echo "  window was destroyed"; return 1; }
  local before after
  before="$(wc -l < "$ROKID_FAKE_LOG")"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111" >/dev/null || return 1
  sleep 0.5
  after="$(wc -l < "$ROKID_FAKE_LOG")"
  [ $(( after - before )) -ge 1 ] || { echo "  no respawn launch"; return 1; }
  assert_eq "aaa111" "$(run_helper_status_id)" || return 1
}

# Second conversation: a SECOND window, first one untouched (still running).
test_switch_two_conversations() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111" >/dev/null || return 1
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:bbb222" >/dev/null || return 1
  sleep 0.5
  local names
  names="$(tmux list-windows -t "$SESSION" -F '#{window_name}' | sort | tr '\n' ' ')"
  assert_eq "rokid-aaa111 rokid-bbb222 " "$names" || return 1
  assert_eq "bbb222" "$(run_helper_status_id)" || return 1   # active = last selected
}

# new:uuid creates a pending window; resume: verifies the JSONL exists.
test_switch_new_and_resume_jsonl() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "new:cccc333" >/dev/null || return 1
  sleep 0.5
  assert_eq "cccc333" "$(run_helper_status_id)" || return 1
  assert_eq "error	bad session id" "$("$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:bad id!")" || return 1
}
```

Note: `test_switch_respawns_dead` uses `tmux respawn-pane -t ... -k` with no command to replace the pane with a bare shell (a dead window). If the installed tmux's `respawn-pane` requires a command, use `tmux respawn-pane -t "$SESSION:rokid-aaa111.0" -k 'sleep 300'` instead. The assertion is: after switching, the fake launched again (the respawn path ran) and status reports `aaa111`.

- [ ] **Step 2: Run the harness — the switch scenarios fail**

Run: `bash helper_test.sh switch`
Expected: FAIL (old `switch` respawns/kills, no windows).

- [ ] **Step 3: Rewrite `cmd_switch` in the helper**

Replace the body of `cmd_switch` (from `local launch_cmd=` through the pre-verify tmux manipulation) with attach semantics. Keep the existing validation (base/dir resolution, path-under-base check, id charset) and the verification poll VERBATIM, except the pane lookup target (see below). New body:

```bash
  local launch_cmd="$CLAUDE_LAUNCHER --effort max --dangerously-skip-permissions $launch_args"

  # Attach semantics (design 2026-08-11 §3.3): a conversation's window is
  # SELECTED, never restarted, when its claude is alive; dead windows are
  # respawned in place; missing windows are created. Only the session-level
  # first launch (no session at all) creates the session.
  local win
  if ! tmux has-session -t "$session" 2>/dev/null; then
    tmux new-session -d -s "$session" -n "rokid-$id" -c "$dir_resolved" "$launch_cmd" \
      || { echo "error\ttmux create failed"; return 1; }
  else
    win="$(find_window_for "$session" "$id")"
    if [ -n "$win" ]; then
      # Found by identification: sync the name (in-session /resume or a
      # legacy window left a stale name) and select.
      [ "$win" = "rokid-$id" ] || tmux rename-window -t "$session:$win" "rokid-$id" 2>/dev/null
      tmux select-window -t "$session:$win"
    elif tmux list-windows -t "$session" -F '#{window_name}' 2>/dev/null | grep -qx "rokid-$id"; then
      # Named window exists; respawn its claude when dead (kill + relaunch),
      # else select as-is.
      local pane_pid claude_pid
      pane_pid="$(tmux list-panes -t "$session:rokid-$id" -F '#{pane_pid}' 2>/dev/null | head -1)"
      [ -n "$pane_pid" ] || { echo "error\tno tmux pane"; return 1; }
      claude_pid="$(first_claude_descendant "$pane_pid")"
      if [ -n "$claude_pid" ]; then
        kill "$claude_pid" 2>/dev/null
        sleep 1
        kill -0 "$claude_pid" 2>/dev/null && kill -9 "$claude_pid" 2>/dev/null
        sleep 1
      fi
      if ! tmux respawn-pane -t "$session:rokid-$id" -k -c "$dir_resolved" "$launch_cmd" 2>/dev/null; then
        local qdir
        qdir="$(printf '%q' "$dir_resolved")"
        tmux send-keys -t "$session:rokid-$id" "cd $qdir && $launch_cmd" Enter
      fi
      tmux select-window -t "$session:rokid-$id"
    else
      tmux new-window -d -n "rokid-$id" -c "$dir_resolved" "$launch_cmd" \
        || { echo "error\ttmux create failed"; return 1; }
      tmux select-window -t "$session:rokid-$id"
    fi
  fi
```

Then fix the verification poll (currently `tmux list-panes -t "$session" -F '#{pane_pid}' | head -1` — with multiple windows `head -1` is wrong): target the ACTIVE window instead:

```bash
  # Verify: a Claude process whose cwd is the target dir (poll <= 15 s).
  local i ok=""
  for i in $(seq 1 30); do
    local pane_pid2 claude_pid2 cwd2
    pane_pid2="$(tmux list-panes -t "$session:" -F '#{pane_pid}' 2>/dev/null | head -1)"
    [ -n "$pane_pid2" ] || break
    claude_pid2="$(first_claude_descendant "$pane_pid2")"
    if [ -n "$claude_pid2" ]; then
      cwd2="$(pid_cwd "$claude_pid2")"
      if [ "$cwd2" = "$dir_resolved" ]; then
        ok="$claude_pid2"
        break
      fi
    fi
    sleep 0.5
  done
  [ -n "$ok" ] || { echo "error\tclaude did not start"; return 1; }
```

The `resume:` JSONL verification and the final `printf 'ok\t%s\t%s\n' "$(encode "$dir_resolved")" "$id"` stay as-is.

- [ ] **Step 4: Run the harness — switch scenarios pass**

Run: `bash helper_test.sh switch`
Expected: PASS on all `test_switch_*` scenarios.

- [ ] **Step 5: Commit**

```bash
git add server/rokid-sessions server/test/helper_test.sh
git commit -m "feat(server): switch gains attach semantics (select window, respawn only when dead)"
```

---

### Task 3: Helper `status` rewrite (active window + self-healing rename)

**Files:**
- Modify: `server/rokid-sessions` (`cmd_status`)
- Test: `server/test/helper_test.sh` scenarios `test_status_*`

**Interfaces:**
- Consumes: `active_window`, `window_conversation_id`, `first_claude_descendant`, `pid_cwd`, `newest_session_id` (existing)
- Produces: `cmd_status` reporting the ACTIVE window, self-healing rename; output contract unchanged (`pid\t<cwd>\t<id>` / `none`)

- [ ] **Step 1: Write the status scenarios (failing)**

Append to `helper_test.sh`:

```bash
# status reports the ACTIVE window only: after two conversations, the last
# selected is reported; after selecting the other window, THAT one is.
test_status_active_window_only() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111" >/dev/null || return 1
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:bbb222" >/dev/null || return 1
  sleep 0.5
  assert_eq "bbb222" "$(run_helper_status_id)" || return 1
  tmux select-window -t "$SESSION:rokid-aaa111"
  sleep 0.5
  assert_eq "aaa111" "$(run_helper_status_id)" || return 1
}

# A window whose claude runs a DIFFERENT conversation than its name (the
# in-session /resume case) is renamed by status: name syncs to the running
# conversation id.
test_status_renames_stale_window() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  # Window named rokid-aaa111 but running a claude for conversation zzz999.
  tmux new-session -d -s "$SESSION" -n "rokid-aaa111" -c "$dir" \
    "$FAKE" --effort max --dangerously-skip-permissions --resume zzz999
  sleep 0.5
  "$HELPER" status "$SESSION" >/dev/null 2>&1
  assert_eq "rokid-zzz999" "$(tmux display-message -p -t "$SESSION" '#{window_name}')" || return 1
  assert_eq "zzz999" "$(run_helper_status_id)" || return 1
}

# No claude in the active window -> "none" (unchanged contract).
test_status_none() {
  local dir="$BASE/proj"
  tmux new-session -d -s "$SESSION" -c "$dir" "sleep 300"
  assert_eq "none" "$("$HELPER" status "$SESSION" 2>/dev/null)" || return 1
}
```

- [ ] **Step 2: Run the harness — status scenarios fail**

Run: `bash helper_test.sh status`
Expected: FAIL.

- [ ] **Step 3: Rewrite `cmd_status`**

```bash
cmd_status() {
  local session="$1" win pane_pid claude_pid cwd id
  tmux has-session -t "$session" 2>/dev/null || { echo "none"; return 0; }
  win="$(active_window "$session")"
  [ -n "$win" ] || { echo "none"; return 0; }
  pane_pid="$(tmux list-panes -t "$session:$win" -F '#{pane_pid}' 2>/dev/null | head -1)"
  [ -n "$pane_pid" ] || { echo "none"; return 0; }
  claude_pid="$(first_claude_descendant "$pane_pid")"
  [ -n "$claude_pid" ] || { echo "none"; return 0; }
  cwd="$(pid_cwd "$claude_pid")"
  [ -n "$cwd" ] || { echo "none"; return 0; }
  id="$(window_conversation_id "$session" "$win")"
  # Self-healing (design §3.4): keep the window name in sync with the
  # conversation it actually runs (in-session /resume leaves a stale name).
  if [ -n "$id" ] && [ "$win" != "rokid-$id" ]; then
    tmux rename-window -t "$session:$win" "rokid-$id" 2>/dev/null
  fi
  printf 'pid\t%s\t%s\t%s\n' "$claude_pid" "$cwd" "${id:--}"
}
```

- [ ] **Step 4: Run the harness — status scenarios pass**

Run: `bash helper_test.sh status`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/rokid-sessions server/test/helper_test.sh
git commit -m "feat(server): status reports the active window and self-heals stale window names"
```

---

### Task 4: Helper `delete` rewrite (kill-window) + `adopt` verb

**Files:**
- Modify: `server/rokid-sessions` (`cmd_delete`, `main` dispatch)
- Test: `server/test/helper_test.sh` scenarios `test_delete_*`, `test_adopt_*`

**Interfaces:**
- Consumes: `find_window_for`, `window_has_claude`, `window_conversation_id`, `active_window`, `session_attached`
- Produces: `cmd_delete` (kill-window + rm JSONL, refusal only when active window is attached), `cmd_adopt <session> <dir> <new-id>` (rename active window)

- [ ] **Step 1: Write the delete/adopt scenarios (failing)**

Append to `helper_test.sh`:

```bash
# delete ends the conversation's window (its claude dies with it) and the
# JSONL; the other window is untouched.
test_delete_kills_window() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111" >/dev/null || return 1
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:bbb222" >/dev/null || return 1
  sleep 0.5
  echo '{"type":"user"}' > "$PROJECTS/$enc/aaa111.jsonl"
  echo '{"type":"user"}' > "$PROJECTS/$enc/bbb222.jsonl"
  local out
  out="$("$HELPER" delete "$SESSION" "$BASE" "$dir" "aaa111")" || return 1
  assert_eq "ok	$enc	aaa111" "$out" || return 1
  assert_eq "" "$(tmux list-windows -t "$SESSION" -F '#{window_name}' | grep 'rokid-aaa111')" || return 1
  [ -f "$PROJECTS/$enc/aaa111.jsonl" ] && { echo "  jsonl not deleted"; return 1; }
  [ -f "$PROJECTS/$enc/bbb222.jsonl" ] || { echo "  other jsonl deleted"; return 1; }
  assert_eq "bbb222" "$(run_helper_status_id)" || return 1
}

# A window NAMED rokid-<id> that actually runs another conversation is never
# killed by delete of <id> (stale name — the conversation it runs is the
# target of a later delete).
test_delete_skips_stale_window() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  tmux new-session -d -s "$SESSION" -n "rokid-aaa111" -c "$dir" \
    "$FAKE" --effort max --dangerously-skip-permissions --resume zzz999
  sleep 0.5
  echo '{"type":"user"}' > "$PROJECTS/$enc/aaa111.jsonl"
  "$HELPER" delete "$SESSION" "$BASE" "$dir" "aaa111" >/dev/null || return 1
  assert_eq "rokid-aaa111" "$(tmux display-message -p -t "$SESSION" '#{window_name}')" || return 1
  [ -f "$PROJECTS/$enc/aaa111.jsonl" ] && { echo "  jsonl not deleted"; return 1; }
}

# delete of the ACTIVE window while a client is attached is refused.
test_delete_refuses_active_attached() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111" >/dev/null || return 1
  sleep 0.5
  echo '{"type":"user"}' > "$PROJECTS/$enc/aaa111.jsonl"
  # Simulate the app's attached client: tmux refuses a non-tty attach, so
  # run it inside a pty. macOS: `script -q /dev/null CMD`; Linux:
  # `script -qec CMD /dev/null`.
  if [ "$(uname)" = "Darwin" ]; then
    script -q /dev/null tmux attach-session -t "$SESSION" >/dev/null 2>&1 &
  else
    script -qec "tmux attach-session -t $SESSION" /dev/null >/dev/null 2>&1 &
  fi
  local attach_pid=$!
  sleep 0.5
  [ -n "$(tmux list-clients -t "$SESSION" 2>/dev/null)" ] || { echo "  no client attached"; return 1; }
  local out
  out="$("$HELPER" delete "$SESSION" "$BASE" "$dir" "aaa111")"
  assert_eq "error	active session" "$out" || return 1
  kill "$attach_pid" 2>/dev/null
  [ -f "$PROJECTS/$enc/aaa111.jsonl" ] || { echo "  jsonl deleted despite refusal"; return 1; }
}

# adopt renames the ACTIVE window to the new id when its claude's cwd
# matches; guards reject mismatched dirs.
test_adopt_renames() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "new:cccc333" >/dev/null || return 1
  sleep 0.5
  local out
  out="$("$HELPER" adopt "$SESSION" "$dir" "zzz999")" || return 1
  assert_eq "ok	$enc	zzz999" "$out" || return 1
  assert_eq "rokid-zzz999" "$(tmux display-message -p -t "$SESSION" '#{window_name}')" || return 1
  assert_eq "error" "$("$HELPER" adopt "$SESSION" "$BASE/other" "yyy888" 2>/dev/null | cut -f1)" || return 1
}
```

- [ ] **Step 2: Run the harness — delete/adopt scenarios fail**

Run: `bash helper_test.sh delete` and `bash helper_test.sh adopt`
Expected: FAIL.

- [ ] **Step 3: Rewrite `cmd_delete` and add `cmd_adopt`**

Replace the body of `cmd_delete` from `local enc target` through the `rm` (keep the dir/base validation). New body:

```bash
  local enc target win
  enc="$(encode "$dir_resolved")"
  target="$PROJECTS_DIR/$enc/$id.jsonl"
  # The conversation's window: found by identification first (it may live
  # under a stale name), else by name when its claude is dead. A window that
  # is named rokid-<id> but ALIVE with a different conversation is stale —
  # it belongs to that other conversation and is never killed here.
  win="$(find_window_for "$session" "$id")"
  if [ -z "$win" ] && tmux list-windows -t "$session" -F '#{window_name}' 2>/dev/null | grep -qx "rokid-$id"; then
    local pane_pid claude_pid
    pane_pid="$(tmux list-panes -t "$session:rokid-$id" -F '#{pane_pid}' 2>/dev/null | head -1)"
    claude_pid="$(first_claude_descendant "$pane_pid")"
    [ -z "$claude_pid" ] && win="rokid-$id"   # dead window is the conversation's
  fi
  if [ -n "$win" ]; then
    # Refuse the ATTACHED conversation (defense in depth; the app also
    # blocks the ▶ row). Refusal only when the window is the session's
    # active window AND a client is attached.
    if [ "$win" = "$(active_window "$session")" ] && session_attached "$session"; then
      echo "error\tactive session"
      return 1
    fi
    tmux kill-window -t "$session:$win" 2>/dev/null
  fi
  [ -f "$target" ] || { echo "error\tnot found"; return 1; }
  rm -f "$target" || { echo "error\tdelete failed"; return 1; }
  printf 'ok\t%s\t%s\n' "$enc" "$id"
```

Add `cmd_adopt` after `cmd_delete`:

```bash
# Rename the ACTIVE window to rokid-<new-id> (design §3.4: the app's
# new-chat discover convergence, when the server ignored --session-id and
# generated its own real id). Guarded: only when the active window's claude
# is alive in the given dir — a wrong target must never be renamed.
cmd_adopt() {
  local session="$1" dir="$2" new_id="$3"
  case "$new_id" in
    *[!A-Za-z0-9_-]*) echo "error\tbad session id"; return 1 ;;
  esac
  local win pane_pid claude_pid cwd
  win="$(active_window "$session")"
  [ -n "$win" ] || { echo "error\tno active window"; return 1; }
  pane_pid="$(tmux list-panes -t "$session:$win" -F '#{pane_pid}' 2>/dev/null | head -1)"
  [ -n "$pane_pid" ] || { echo "error\tno tmux pane"; return 1; }
  claude_pid="$(first_claude_descendant "$pane_pid")"
  [ -n "$claude_pid" ] || { echo "error\tno claude"; return 1; }
  cwd="$(pid_cwd "$claude_pid")"
  [ "$cwd" = "$dir" ] || { echo "error\tcwd mismatch"; return 1; }
  if [ "$win" != "rokid-$new_id" ]; then
    tmux rename-window -t "$session:$win" "rokid-$new_id" 2>/dev/null \
      || { echo "error\trename failed"; return 1; }
  fi
  printf 'ok\t%s\t%s\n' "$(encode "$dir")" "$new_id"
}
```

Add to `main()` dispatch (before the `*)` catch-all):

```bash
    adopt)
      [ $# -ge 4 ] || { echo "error\tusage: adopt <tmux-session> <real-dir> <new-id>"; return 1; }
      cmd_adopt "$2" "$3" "$4"
      ;;
```

- [ ] **Step 4: Run the harness — delete/adopt scenarios pass**

Run: `bash helper_test.sh delete` and `bash helper_test.sh adopt`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/rokid-sessions server/test/helper_test.sh
git commit -m "feat(server): delete kills the conversation window; adopt renames on id convergence"
```

---

### Task 5: Helper `sweep` verb (idle auto-exit)

**Files:**
- Modify: `server/rokid-sessions` (`cmd_sweep`, `window_idle`, `main` dispatch)
- Test: `server/test/helper_test.sh` scenarios `test_sweep_*`

**Interfaces:**
- Consumes: `active_window`, `window_has_claude`, `window_conversation_id`, `pid_cwd`, `encode`, `cpu_ticks`, `descendants`
- Produces: `cmd_sweep <session> <base> [idle-minutes]` → `swept\t<count>`; env `SWEEP_SAMPLE_SLEEP` (default 120) for tests

- [ ] **Step 1: Write the sweep scenarios (failing)**

Append to `helper_test.sh`:

```bash
# An idle background conversation is swept; the ACTIVE one is never swept.
test_sweep_kills_idle_keeps_active() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111" >/dev/null || return 1
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:bbb222" >/dev/null || return 1
  sleep 0.5
  # Make both transcripts old (idle); aaa111 is now the ACTIVE window.
  echo '{"type":"user"}' > "$PROJECTS/$enc/aaa111.jsonl"
  echo '{"type":"user"}' > "$PROJECTS/$enc/bbb222.jsonl"
  touch -t 200001010000 "$PROJECTS/$enc/aaa111.jsonl" "$PROJECTS/$enc/bbb222.jsonl"
  local out
  out="$(SWEEP_SAMPLE_SLEEP=1 "$HELPER" sweep "$SESSION" "$BASE" 1)"
  assert_eq "swept	1" "$out" || return 1
  assert_eq "" "$(tmux list-windows -t "$SESSION" -F '#{window_name}' | grep 'rokid-bbb222')" || return 1
  assert_eq "rokid-aaa111" "$(tmux display-message -p -t "$SESSION" '#{window_name}')" || return 1
}

# A background conversation with RECENT transcript activity is NOT swept.
test_sweep_keeps_recent() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111" >/dev/null || return 1
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:bbb222" >/dev/null || return 1
  sleep 0.5
  echo '{"type":"user"}' > "$PROJECTS/$enc/bbb222.jsonl"   # recent (now)
  local out
  out="$(SWEEP_SAMPLE_SLEEP=1 "$HELPER" sweep "$SESSION" "$BASE" 1)"
  assert_eq "swept	0" "$out" || return 1
  assert_eq "rokid-bbb222" "$(tmux display-message -p -t "$SESSION" '#{window_name}')" || return 1
}

# A background conversation with a live child (running tool) is NOT swept.
test_sweep_keeps_busy_child() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  tmux new-session -d -s "$SESSION" -n "rokid-bbb222" -c "$dir" \
    ROKID_FAKE_CHILD=1 "$FAKE" --effort max --dangerously-skip-permissions --resume bbb222
  sleep 0.5
  echo '{"type":"user"}' > "$PROJECTS/$enc/bbb222.jsonl"
  touch -t 200001010000 "$PROJECTS/$enc/bbb222.jsonl"
  local out
  out="$(SWEEP_SAMPLE_SLEEP=1 "$HELPER" sweep "$SESSION" "$BASE" 1)"
  assert_eq "swept	0" "$out" || return 1
}

# A background conversation whose claude burns CPU (long thinking) is NOT swept.
test_sweep_keeps_busy_cpu() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  tmux new-session -d -s "$SESSION" -n "rokid-bbb222" -c "$dir" \
    ROKID_FAKE_CPU=4 "$FAKE" --effort max --dangerously-skip-permissions --resume bbb222
  sleep 0.5
  echo '{"type":"user"}' > "$PROJECTS/$enc/bbb222.jsonl"
  touch -t 200001010000 "$PROJECTS/$enc/bbb222.jsonl"
  local out
  out="$(SWEEP_SAMPLE_SLEEP=1 "$HELPER" sweep "$SESSION" "$BASE" 1)"
  assert_eq "swept	0" "$out" || return 1
}
```

Note: `SWEEP_SAMPLE_SLEEP=1` (prefix env on the helper invocation) shortens the CPU sample window for tests.

- [ ] **Step 2: Run the harness — sweep scenarios fail**

Run: `bash helper_test.sh sweep`
Expected: FAIL ("unknown verb" for sweep).

- [ ] **Step 3: Implement `cmd_sweep` + `window_idle`**

Add after `cmd_adopt`:

```bash
# Idle check (design §3.6): a window is idle ONLY when ALL THREE signals
# say idle. 1) its JSONL has not been written for idle_secs; 2) its claude
# has no descendant children; 3) its claude accumulated < 0.5% CPU over the
# sample window. A window with no claude (dead) is idle (nothing runs).
# Unknowns are treated as BUSY (never kill what we cannot prove idle).
window_idle() {
  local session="$1" win="$2" idle_secs="$3" pane_pid claude_pid cwd id jsonl
  pane_pid="$(tmux list-panes -t "$session:$win" -F '#{pane_pid}' 2>/dev/null | head -1)"
  [ -n "$pane_pid" ] || return 0
  claude_pid="$(first_claude_descendant "$pane_pid")"
  [ -n "$claude_pid" ] || return 0
  cwd="$(pid_cwd "$claude_pid")"
  id="$(window_conversation_id "$session" "$win")"
  if [ -n "$cwd" ] && [ -n "$id" ]; then
    jsonl="$PROJECTS_DIR/$(encode "$cwd")/$id.jsonl"
    if [ -f "$jsonl" ]; then
      local mtime age
      mtime="$(stat -c %Y "$jsonl" 2>/dev/null || stat -f %m "$jsonl" 2>/dev/null)"
      now="$(date +%s)"
      age=$(( now - mtime ))
      [ "$age" -lt "$idle_secs" ] && return 1   # recently active
    fi
  fi
  # Signal 2: any descendant child of the claude process = busy.
  local pid
  for pid in $(descendants "$claude_pid"); do
    [ "$pid" = "$claude_pid" ] && continue
    return 1
  done
  # Signal 3: CPU accumulation over two samples. Linux utime = jiffies
  # (1/100 s); macOS = whole seconds — normalize on /proc presence.
  local t1 t2 s1 s2 wall cpus
  t1="$(date +%s)"; s1="$(cpu_ticks "$claude_pid")"
  sleep "${SWEEP_SAMPLE_SLEEP:-120}"
  t2="$(date +%s)"; s2="$(cpu_ticks "$claude_pid")"
  wall=$(( t2 - t1 )); [ "$wall" -lt 1 ] && wall=1
  if [ -d "/proc/$claude_pid" ]; then cpus=$(( (s2 - s1) / 100 )); else cpus=$(( s2 - s1 )); fi
  [ "$cpus" -ge $(( wall * 5 / 1000 )) ] && return 1   # >= 0.5% CPU
  return 0
}

# End idle background conversations (design §3.6). Two phases so the CPU
# sample sleeps ONCE for all candidates. Never touches JSONL; never sweeps
# the ACTIVE window.
cmd_sweep() {
  local session="$1" base="$2" idle_min="${3:-180}" idle_secs=$(( idle_min * 60 ))
  tmux has-session -t "$session" 2>/dev/null || { echo "swept	0"; return 0; }
  local active win count=0 candidate
  active="$(active_window "$session")"
  for win in $(tmux list-windows -t "$session" -F '#{window_name}' 2>/dev/null); do
    [ "$win" = "$active" ] && continue
    window_idle "$session" "$win" "$idle_secs" && { count=$((count+1)); continue; }
  done
  printf 'swept\t%d\n' "$count"
}
```

Correction to the loop above: `window_idle` performs the 120 s sleep per candidate. To sleep ONCE, restructure `cmd_sweep` as two phases: (a) cheap pre-filter (claude present, not recently active, no children) collecting candidates into `CANDIDATES`; (b) one `sleep` then CPU-recheck each candidate; kill the idle ones. Implement it as:

```bash
cmd_sweep() {
  local session="$1" base="$2" idle_min="${3:-180}" idle_secs=$(( idle_min * 60 ))
  tmux has-session -t "$session" 2>/dev/null || { echo "swept	0"; return 0; }
  local active win count=0
  active="$(active_window "$session")"
  local -a candidates=()
  for win in $(tmux list-windows -t "$session" -F '#{window_name}' 2>/dev/null); do
    [ "$win" = "$active" ] && continue
    if idle_fast "$session" "$win" "$idle_secs"; then
      candidates+=("$win")
    fi
  done
  if [ ${#candidates[@]} -gt 0 ]; then
    sleep "${SWEEP_SAMPLE_SLEEP:-120}"
    local w t1 t2 s1 s2 wall cpus pane_pid claude_pid
    for w in "${candidates[@]}"; do
      pane_pid="$(tmux list-panes -t "$session:$w" -F '#{pane_pid}' 2>/dev/null | head -1)"
      claude_pid="$(first_claude_descendant "$pane_pid")"
      [ -n "$claude_pid" ] || { count=$((count+1)); tmux kill-window -t "$session:$w" 2>/dev/null; continue; }
      t1="$(date +%s)"; s1="$(cpu_ticks "$claude_pid")"
      sleep 2
      t2="$(date +%s)"; s2="$(cpu_ticks "$claude_pid")"
      wall=$(( t2 - t1 )); [ "$wall" -lt 1 ] && wall=1
      if [ -d "/proc/$claude_pid" ]; then cpus=$(( (s2 - s1) / 100 )); else cpus=$(( s2 - s1 )); fi
      if [ "$cpus" -lt $(( wall * 5 / 1000 )) ]; then
        tmux kill-window -t "$session:$w" 2>/dev/null && count=$((count+1))
      fi
    done
  fi
  printf 'swept\t%d\n' "$count"
}
```

`idle_fast` is the cheap phase (signals 1-2 only, returns 0 = idle-eligible):

```bash
# Cheap idle pre-filter: claude dead, or JSONL old AND no children.
idle_fast() {
  local session="$1" win="$2" idle_secs="$3" pane_pid claude_pid cwd id jsonl
  pane_pid="$(tmux list-panes -t "$session:$win" -F '#{pane_pid}' 2>/dev/null | head -1)"
  [ -n "$pane_pid" ] || return 0
  claude_pid="$(first_claude_descendant "$pane_pid")"
  [ -n "$claude_pid" ] || return 0
  local pid
  for pid in $(descendants "$claude_pid"); do
    [ "$pid" = "$claude_pid" ] && continue
    return 1   # child present -> busy, not even a candidate
  done
  cwd="$(pid_cwd "$claude_pid")"
  id="$(window_conversation_id "$session" "$win")"
  if [ -n "$cwd" ] && [ -n "$id" ]; then
    jsonl="$PROJECTS_DIR/$(encode "$cwd")/$id.jsonl"
    if [ -f "$jsonl" ]; then
      local mtime age
      mtime="$(stat -c %Y "$jsonl" 2>/dev/null || stat -f %m "$jsonl" 2>/dev/null)"
      age=$(( $(date +%s) - mtime ))
      [ "$age" -lt "$idle_secs" ] && return 1   # recently active
    fi
  fi
  return 0
}
```

(Note: drop the standalone `window_idle` from this task — the two-phase `idle_fast` + CPU recheck IS the implementation. The scenario `test_sweep_keeps_busy_cpu` needs `ROKID_FAKE_CPU=4` with `SWEEP_SAMPLE_SLEEP=1` plus the 2 s per-candidate recheck sleep: total per-candidate ≈ 2-4 s; the fake burns 4 CPU-seconds, so `cpus` (macOS seconds) ≥ 4 > 0.005×wall → busy → survives. On Linux `/proc` present, `(s2-s1)/100` ≈ 4 ≥ threshold → busy. Good.)

Add to `main()` dispatch:

```bash
    sweep)
      [ $# -ge 3 ] || { echo "error\tusage: sweep <tmux-session> <base-dir> [idle-minutes]"; return 1; }
      cmd_sweep "$2" "$3" "${4:-180}"
      ;;
```

- [ ] **Step 4: Run the harness — sweep scenarios pass**

Run: `bash helper_test.sh sweep`
Expected: PASS on all four scenarios. (Note: `test_sweep_kills_idle_keeps_active` asserts `swept	1` — with two windows, the active (aaa111) skipped, bbb222 idle → killed.)

- [ ] **Step 5: Commit**

```bash
git add server/rokid-sessions server/test/helper_test.sh
git commit -m "feat(server): sweep verb ends idle background conversations (triple-signal guard)"
```

---

### Task 6: App — fetcher methods + pure helpers + JVM tests

**Files:**
- Modify: `app/src/main/java/com/rokid/terminal/ServerSessionFetcher.kt`
- Test: `app/src/test/java/com/rokid/terminal/ServerSessionFetcherParseTest.kt`

**Interfaces:**
- Consumes: existing `run(...)`, `HELPER`, `shellQuote`, `parseSwitchResult`
- Produces:
  - `fun adoptConversation(tmuxSession: String, folderPath: String, newSessionId: String): String?`
  - `fun sweepIdle(tmuxSession: String, baseDir: String): String?`
  - `fun parseSweepResult(text: String): Int?` (companion)
  - `fun newestUnboundSession(folder: RemoteFolder, tempId: String, previousId: String?): RemoteSession?` (companion)

- [ ] **Step 1: Write the failing JVM tests**

Add to `ServerSessionFetcherParseTest.kt`:

```kotlin
@Test
fun parseSweepResultCounts() {
    assertEquals(3, ServerSessionFetcher.parseSweepResult("swept\t3"))
    assertEquals(0, ServerSessionFetcher.parseSweepResult("swept\t0"))
    assertNull(ServerSessionFetcher.parseSweepResult("error\tsomething"))
    assertNull(ServerSessionFetcher.parseSweepResult(""))
}

@Test
fun newestUnboundSessionSkipsTempAndPrevious() {
    val folder = RemoteFolder(
        "/srv/proj", "-srv-proj", listOf(
            RemoteSession("prev-id", "old", 1000L),
            RemoteSession("temp-id", "new chat", 2000L),
            RemoteSession("real-id", "title", 3000L),
        ),
    )
    val result = ServerSessionFetcher.newestUnboundSession(folder, "temp-id", "prev-id")
    assertEquals("real-id", result?.id)
}

@Test
fun newestUnboundSessionNullWhenOnlyTempOrPrevious() {
    val folder = RemoteFolder(
        "/srv/proj", "-srv-proj", listOf(
            RemoteSession("temp-id", "new chat", 2000L),
        ),
    )
    assertNull(ServerSessionFetcher.newestUnboundSession(folder, "temp-id", "prev-id"))
    val onlyPrev = RemoteFolder("/srv/proj", "-srv-proj", listOf(RemoteSession("prev-id", "old", 1000L)))
    assertNull(ServerSessionFetcher.newestUnboundSession(onlyPrev, "temp-id", "prev-id"))
}

@Test
fun newestUnboundSessionPicksNewestOfSeveral() {
    val folder = RemoteFolder(
        "/srv/proj", "-srv-proj", listOf(
            RemoteSession("a-id", "a", 1000L),
            RemoteSession("b-id", "b", 4000L),
        ),
    )
    assertEquals("b-id", ServerSessionFetcher.newestUnboundSession(folder, "temp-id", null)?.id)
}
```

Check `RemoteSession`/`RemoteFolder` constructor signatures in the source (`ServerSessionFetcher.kt` uses `RemoteSession(id, title, epochMillis)` and `RemoteFolder(path, encodedDir, sessions)` — verify the actual data class definitions before compiling; adjust the test constructors to match).

- [ ] **Step 2: Run the tests — fail**

Run: `cd RokidTerm && ./gradlew :app:testDebugUnitTest --tests "com.rokid.terminal.ServerSessionFetcherParseTest"` — needs `JAVA_HOME` per project CLAUDE.md.
Expected: FAIL (methods don't exist).

- [ ] **Step 3: Implement in `ServerSessionFetcher`**

Add methods (public, alongside the existing `deleteConversation`/`exportConversation`):

```kotlin
    /**
     * Renames the ACTIVE window to the conversation's real id after new-chat
     * discover convergence (the server may have ignored --session-id;
     * design 2026-08-11 §3.3). Best-effort: the caller logs, never blocks.
     */
    fun adoptConversation(
        tmuxSession: String,
        folderPath: String,
        newSessionId: String,
    ): String? = run(
        "$HELPER adopt ${shellQuote(tmuxSession)} ${shellQuote(folderPath)} ${shellQuote(newSessionId)}",
    )

    /**
     * Ends idle background conversations server-side (design 2026-08-11
     * §3.6). Takes minutes (CPU sampling); the caller runs it on its own
     * thread. Returns the count of ended conversations.
     */
    fun sweepIdle(tmuxSession: String, baseDir: String): String? = run(
        "$HELPER sweep ${shellQuote(tmuxSession)} ${shellQuote(baseDir)}",
        timeoutMs = SWEEP_TIMEOUT_MS,
    )
```

Add to the companion:

```kotlin
        /** Count from a `swept\t<count>` line; null when the sweep failed. */
        fun parseSweepResult(text: String): Int? {
            val line = text.lineSequence().firstOrNull { it.startsWith("swept\t") } ?: return null
            return line.substringAfter('\t').toIntOrNull()
        }

        /**
         * The newly-created conversation's real session from a re-listed
         * folder: the newest session whose id is neither the app-generated
         * placeholder (id honored -> nothing to converge) nor the previous
         * conversation. Null when no such session exists yet.
         */
        fun newestUnboundSession(folder: RemoteFolder, tempId: String, previousId: String?): RemoteSession? =
            folder.sessions
                .filter { it.id != tempId && it.id != previousId }
                .maxByOrNull { it.epochMillis }
```

Add to the companion constants: `private const val SWEEP_TIMEOUT_MS = 180_000`.

- [ ] **Step 4: Run the tests — pass**

Run: the same gradle command.
Expected: PASS (all existing tests too).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rokid/terminal/ServerSessionFetcher.kt app/src/test/java/com/rokid/terminal/ServerSessionFetcherParseTest.kt
git commit -m "feat(app): adopt + sweep fetcher methods, newestUnboundSession helper"
```

---

### Task 7: App — new-chat discover convergence rework

**Files:**
- Modify: `app/src/main/java/com/rokid/terminal/MainActivity.kt` (`discoverNewSessionId`, around lines 2604-2651)

**Interfaces:**
- Consumes: `ServerSessionFetcher.newestUnboundSession`, `fetcher.listSessions(workspace)`, `fetcher.adoptConversation(...)`
- Produces: discover converges via folder re-list (independent of process fd state) and best-effort `adopt`s the window name

- [ ] **Step 1: Replace the status-poll with a re-list poll**

Replace the whole `discoverNewSessionId` body (keep the doc comment, update it) with:

```kotlin
    /**
     * Polls the folder LIST for a few seconds after a NEW conversation
     * switch and, when a real session file appears (the JSONL is written on
     * the first message), corrects the binding/remembered target/cache to
     * the REAL id and renames the server window via `adopt`. The
     * app-generated placeholder id may differ from the server's file
     * (--session-id can be ignored), which caused duplicate-looking rows,
     * failed resumes, and a wrong ▶ marker (bug 1, 2026-08-07). Re-list
     * polling (2026-08-11) is independent of the process's open-file state,
     * unlike `status`-based discovery. Session ids are never logged.
     */
    private fun discoverNewSessionId(folderPath: String, tempSessionId: String, previousSessionId: String?) {
        val endpoint = activeEndpoint ?: return
        val fetcher = sessionFetcher ?: return
        val folderKey = ServerSessionFetcher.encodeDir(folderPath)
        Thread {
            for (attempt in 0 until 6) {
                Thread.sleep(2000)
                val folders = fetcher.listSessions(endpoint.workspace) ?: continue
                val folder = folders.firstOrNull { it.encodedDir == folderKey } ?: continue
                val real = ServerSessionFetcher.newestUnboundSession(folder, tempSessionId, previousSessionId) ?: continue
                val realId = real.id
                if (realId.isEmpty()) continue
                // Before the new chat's first message the folder's newest is
                // still the previous conversation — never "correct" back to
                // it (bug 1, 2026-08-07).
                if (realId == previousSessionId) continue
                runOnUiThread {
                    if (scrollbackSessionId == tempSessionId) {
                        persistScrollback()
                        scrollbackSessionId = realId
                        rememberTarget(folderPath, realId)
                        sessionPicker.markCurrent(folderPath, realId)
                        // Drafts sent under the placeholder key must follow
                        // the conversation into the real-id file, or the
                        // recall keys read nothing (user 2026-08-07).
                        InputHistory.migrate(filesDir, "$folderKey/$tempSessionId", "$folderKey/$realId")
                        inputHistory = InputHistory(filesDir, "$folderKey/$realId")
                        // Real id converged: the sync watcher may resume
                        // (a non-converged NEW chat must keep it off, or it
                        // imports the previous conversation's scrollback).
                        newSessionPending = false
                        // Replace the placeholder id in the cached list.
                        cachedFolders = cachedFolders?.map { folder ->
                            if (folder.path == folderPath) {
                                val sessions = folder.sessions.map {
                                    if (it.id == tempSessionId) it.copy(id = realId) else it
                                }
                                folder.copy(sessions = sessions)
                            } else {
                                folder
                            }
                        }
                    }
                }
                // Best-effort: keep the server window name in sync with the
                // real id (design 2026-08-11 §3.4) so later switches find it
                // by name even when the process is idle. Failure is logged
                // only — the next switch self-heals via identification.
                val adoptEndpoint = endpoint
                Thread {
                    try {
                        fetcher.adoptConversation(adoptEndpoint.sessionName, folderPath, realId)
                    } catch (error: Exception) {
                        android.util.Log.w("RokidTerminal", "adopt failed: ${error.message ?: error.javaClass.simpleName}")
                    }
                }.start()
                return@Thread
            }
        }.start()
    }
```

Notes for the implementer:
- `RemoteFolder`, `RemoteSession` are data classes defined in `ServerSessionFetcher.kt` — verify their property names (`encodedDir`, `sessions`, `id`, `epochMillis`) before compiling.
- The adopt call must NOT log the session id (privacy constraint).
- The current code's `status`-based loop is removed entirely.

- [ ] **Step 2: Build + run all JVM tests**

Run: `cd RokidTerm && ./gradlew :app:testDebugUnitTest` (JAVA_HOME per project CLAUDE.md).
Expected: PASS, no compile errors.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/rokid/terminal/MainActivity.kt
git commit -m "feat(app): new-chat discover converges via folder re-list + adopts window name"
```

---

### Task 8: App — idle sweep runnable + status-bar cosmetic

**Files:**
- Modify: `app/src/main/java/com/rokid/terminal/MainActivity.kt` (constants + runnable + start site)
- Modify: `app/src/main/java/com/rokid/terminal/EndpointProfile.kt` (status-left)
- Test: `app/src/test/java/com/rokid/terminal/EndpointProfileTest.kt` (assertion update)

- [ ] **Step 1: Update the status-bar test (failing)**

In `EndpointProfileTest.kt` find the assertion containing `status-left '[#{session_name}] '` and change it to `status-left '[#{window_name}] '`. Keep every other assertion byte-identical.

- [ ] **Step 2: Run the test — fail**

Run: `cd RokidTerm && ./gradlew :app:testDebugUnitTest --tests "com.rokid.terminal.EndpointProfileTest"`
Expected: FAIL (still asserts session_name).

- [ ] **Step 3: Change `EndpointProfile.remoteCommand` status-left**

In `EndpointProfile.kt` companion `TMUX_STATUS_OPTIONS`, change:

```kotlin
        private val TMUX_STATUS_OPTIONS = listOf(
            "status-left" to "[#{session_name}] ",
```
to:
```kotlin
        private val TMUX_STATUS_OPTIONS = listOf(
            // The per-conversation window name (rokid-<id>) — shows WHICH
            // conversation is attached (design 2026-08-11 §3.5).
            "status-left" to "[#{window_name}] ",
```

- [ ] **Step 4: Run the test — pass**

Run: the same gradle command.
Expected: PASS.

- [ ] **Step 5: Add the sweep runnable to MainActivity**

Near the `sessionSyncRunnable` (around line 144), add:

```kotlin
    /** Idle-conversation sweep (design 2026-08-11 §3.6): ends idle background
     *  conversations every SWEEP_INTERVAL_MS while connected. Single-flight;
     *  runs on its own thread (the sweep takes minutes). */
    @Volatile
    private var sweepInFlight = false
    private val sweepRunnable = object : Runnable {
        override fun run() {
            runSweep()
            mainHandler.postDelayed(this, SWEEP_INTERVAL_MS)
        }
    }
```

Add the method next to `pollSessionSync`:

```kotlin
    private fun runSweep() {
        val fetcher = sessionFetcher ?: return
        val endpoint = activeEndpoint ?: return
        if (sshState != "CONNECTED" || sessionPicker.open || switchInFlight || sweepInFlight) return
        sweepInFlight = true
        Thread {
            try {
                val count = fetcher.sweepIdle(endpoint.sessionName, endpoint.workspace)
                android.util.Log.i("RokidTerminal", "idle sweep: ${count ?: -1} ended")
            } catch (error: Exception) {
                android.util.Log.w("RokidTerminal", "idle sweep failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                sweepInFlight = false
            }
        }.start()
    }
```

Add to the companion constants (next to `SESSION_SYNC_MS = 30_000L`):

```kotlin
        /** Idle-conversation sweep cadence (design 2026-08-11 §3.6). */
        const val SWEEP_INTERVAL_MS = 5 * 60_000L
```

Start and stop it beside the sync watcher: in `onCreate` after `mainHandler.post(sessionSyncRunnable)` (line ~214) add `mainHandler.postDelayed(sweepRunnable, SWEEP_INTERVAL_MS)`; in `onDestroy` after `mainHandler.removeCallbacks(sessionSyncRunnable)` (line ~368) add `mainHandler.removeCallbacks(sweepRunnable)`. These run once per activity lifetime — no double-start guard needed.

- [ ] **Step 6: Build + run all JVM tests**

Run: `cd RokidTerm && ./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rokid/terminal/MainActivity.kt app/src/main/java/com/rokid/terminal/EndpointProfile.kt app/src/test/java/com/rokid/terminal/EndpointProfileTest.kt
git commit -m "feat(app): idle sweep runnable + status bar shows conversation window name"
```

---

### Task 9: Server deploy + smoke-test script

**Files:**
- Create: `server/test/smoke_test.sh` (executable)
- Modify: `RokidTerm/README.md` (install section)

- [ ] **Step 1: Write the smoke-test script**

`server/test/smoke_test.sh` is a thin wrapper that runs the FULL harness against a real tmux server with `/proc` (the deployment host) — it is exactly `helper_test.sh` (symlink or copy), plus a check that the deployed helper matches the repo copy:

```bash
#!/usr/bin/env bash
# Server-side smoke test for rokid-sessions (run AFTER deploying the new
# helper to /home/rokid/bin/rokid-sessions). On the deployment host this
# exercises the /proc fd-scan and Linux jiffy CPU units. Usage:
#   scp server/rokid-sessions <user>@<host>:/home/rokid/bin/rokid-sessions
#   ssh <user>@<host> 'chmod +x ~/bin/rokid-sessions'
#   scp -r server/test <user>@<host>:~/rokidterm-test/
#   ssh <user>@<host> 'bash ~/rokidterm-test/helper_test.sh'
set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
echo "smoke: helper at $ROOT/../rokid-sessions, tmux: $(command -v tmux)"
bash "$ROOT/helper_test.sh"
```

- [ ] **Step 2: Update the README install section**

In `RokidTerm/README.md`, extend the `rokid-sessions` install block to mention: (a) the new verbs (`adopt`, `sweep`), (b) the attach semantics (switching no longer restarts), (c) the idle sweep default (3 h) and how to tune it (`sweep <session> <base> <minutes>`), (d) the smoke-test command from Step 1.

- [ ] **Step 3: Run the full harness locally once more**

Run: `cd RokidTerm/server/test && bash helper_test.sh`
Expected: ALL scenarios PASS (regression gate before handoff).

- [ ] **Step 4: Commit**

```bash
git add server/test/smoke_test.sh RokidTerm/README.md
git commit -m "docs: deploy + smoke-test instructions for concurrent sessions"
```

---

### Task 10: Docs — CLAUDE.md Open/pending

**Files:**
- Modify: `RokidTerm/CLAUDE.md`

- [ ] **Step 1: Update Open/pending**

Replace the "Concurrent sessions (option B)" entry under Open/pending with an implemented note:

```markdown
- **Concurrent sessions (option B)** — implemented 2026-08-11: one tmux
  window per conversation (`rokid-<id>`), switch = attach (never restarts),
  delete ends the window, idle background conversations are swept after
  ~3 h (triple-signal guard, active window never swept), new-chat discover
  adopts the real id. Spec: `docs/superpowers/specs/2026-08-11-concurrent-
  sessions-design.md`. The deployment host needs the UPDATED
  `/home/rokid/bin/rokid-sessions` (server/rokid-sessions) — old helper
  installs respawn on every switch.
```

- [ ] **Step 2: Commit**

```bash
git add RokidTerm/CLAUDE.md
git commit -m "docs: concurrent sessions implemented (attach semantics, idle sweep)"
```

---

## Self-review notes

- Spec §3.1 (tmux model) → Task 2; §3.2 (identification) → Task 1; §3.3 (switch/status/delete/adopt) → Tasks 2-4; §3.6 (sweep) → Task 5; §3.5 app changes (status-left, discover, sweep call site) → Tasks 6-8; §6 testing → Tasks 1-5 harness + Task 9 smoke; §4 behavior contract → Tasks 2, 4, 5; §5 known limitations → documented in code comments, not testable.
- The helper's old single-pane code paths (the `respawn-pane -t "$session"` and `list-panes -t "$session"` patterns) are ALL replaced in Tasks 2-3 — no stale single-window assumptions may remain.
- `test_switch_respawns_dead` kills the fake process via `pkill -f fake-rokid-claude` — the pkill must not match the harness itself (the pattern is distinct from the harness's own cmdline); the assertion (a new fake launch line after the switch) is what matters. If a test host's `respawn-pane` refuses `-c <dir>` with a command, keep the `send-keys` fallback path (already in the helper).
- Harness ordering: the helper is SOURCED (`ROKID_SESSIONS_SOURCE=1 . "$HELPER"`) before the harness defines its own `main()`; scenarios call sourced functions directly; the `"$HELPER" <verb>` subprocess invocations re-read the env exports from `setup()`.

