#!/usr/bin/env bash
# RokidTerm rokid-sessions harness. Requires tmux. Run: bash helper_test.sh [filter]
# Each scenario creates its own tmux session (killed on teardown) and its own
# throwaway project/workspace dirs, so scenarios never interfere.
#
# The helper is SOURCED (ROKID_SESSIONS_SOURCE=1) so scenarios can call its
# internal functions directly; the helper's `main "$@"` is guarded off.
set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
HELPER="$ROOT/../rokid-sessions"
FAKE="$ROOT/fake-rokid-claude"
SESSION="rokid-harness-$$"
PASS=0; FAIL=0; FAILED_CASES=""
FILTER="${1:-}"

# Inline (NOT exported): a subprocess helper invocation must run main(), and
# the guard would otherwise leak into its environment and swallow the verb.
ROKID_SESSIONS_SOURCE=1 . "$HELPER"

setup() {   # per-scenario: fresh tmp dirs + a session named $SESSION
  TEST_TMP="$(mktemp -d)"
  mkdir -p "$TEST_TMP/base/proj" "$TEST_TMP/projects"
  # Canonicalize (macOS /var -> /private/var): the helper resolves paths
  # with pwd -P, so the harness's enc dirs must match exactly.
  BASE="$(cd "$TEST_TMP/base" && pwd -P)"
  PROJECTS="$(cd "$TEST_TMP/projects" && pwd -P)"
  mkdir -p "$PROJECTS/$(enc "$BASE/proj")"
  export ROKID_SESSIONS_PROJECTS_DIR="$PROJECTS"
  export ROKID_SESSIONS_LAUNCHER="$FAKE"
  FAKE_LOG="${TMPDIR:-/tmp}/rokid-fake-launch.log"; : > "$FAKE_LOG"
  tmux kill-session -t "$SESSION" 2>/dev/null || true
}
teardown() {
  tmux kill-session -t "$SESSION" 2>/dev/null || true
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
# status of $SESSION via the helper, extracting field 4 (the conversation id;
# the protocol is pid\t<cwd>\t<id>)
run_helper_status_id() {
  "$HELPER" status "$SESSION" 2>/dev/null | awk -F '\t' '$1=="pid"{print $4}'
}

# --- identification ---------------------------------------------------------

# A fake claude's launch cmdline identifies the conversation (cmdline fallback).
test_identification_cmdline() {
  local dir="$BASE/proj"
  tmux new-session -d -s "$SESSION" -n "rokid-abc123" -c "$dir" \
    "$FAKE" --effort max --dangerously-skip-permissions --resume abc123
  sleep 0.5
  assert_eq "abc123" "$(window_conversation_id "$SESSION" 0)" || return 1
}

# The open transcript fd identifies the CURRENT conversation (beats a stale
# cmdline — the in-session /resume case).
test_identification_fd_beats_cmdline() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  echo '{"type":"user"}' > "$PROJECTS/$enc/newer.jsonl"
  # tmux requires the whole command as ONE quoted string when it carries an
  # env prefix (separate args make tmux misparse the assignment).
  tmux new-session -d -s "$SESSION" -n "rokid-abc123" -c "$dir" \
    "ROKID_FAKE_JSONL=$PROJECTS/$enc/newer.jsonl $FAKE --effort max --dangerously-skip-permissions --resume abc123"
  sleep 0.5
  assert_eq "newer" "$(window_conversation_id "$SESSION" 0)" || return 1
}

# No launch args and no open fd -> unidentified (empty).
test_identification_unidentified() {
  local dir="$BASE/proj"
  tmux new-session -d -s "$SESSION" -n "rokid-abc123" -c "$dir" "$FAKE"
  sleep 0.5
  assert_eq "" "$(window_conversation_id "$SESSION" 0)" || return 1
}

# A window without a claude descendant -> unidentified (empty).
test_identification_no_claude() {
  local dir="$BASE/proj"
  tmux new-session -d -s "$SESSION" -n "rokid-abc123" -c "$dir" "sleep 300"
  assert_eq "" "$(window_conversation_id "$SESSION" 0)" || return 1
}

# --- switch -------------------------------------------------------------

# First switch on a fresh server: session + window created, claude running.
test_switch_creates() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  echo '{"type":"user"}' > "$PROJECTS/$enc/aaa111.jsonl"
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
  echo '{"type":"user"}' > "$PROJECTS/$enc/aaa111.jsonl"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111" >/dev/null || return 1
  sleep 0.5
  local before after
  before="$(wc -l < "$FAKE_LOG")"
  local out
  out="$("$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111")" || return 1
  assert_eq "ok	$enc	aaa111" "$out" || return 1
  sleep 0.5
  after="$(wc -l < "$FAKE_LOG")"
  assert_eq "$before" "$after" || return 1
  assert_eq "1" "$(tmux list-windows -t "$SESSION" -F '#{window_name}' | wc -l | tr -d ' ')" || return 1
}

# A dead conversation's window is respawned in place (one new launch line).
test_switch_respawns_dead() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  echo '{"type":"user"}' > "$PROJECTS/$enc/aaa111.jsonl"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111" >/dev/null || return 1
  sleep 0.5
  # Kill the fake claude process ONLY — the window remains with a dead pane.
  pkill -f "fake-rokid-claude" 2>/dev/null
  sleep 0.5
  [ -n "$(tmux list-windows -t "$SESSION" -F '#{window_name}' | grep 'rokid-aaa111')" ] \
    || { echo "  window was destroyed"; return 1; }
  local before after
  before="$(wc -l < "$FAKE_LOG")"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111" >/dev/null || return 1
  sleep 0.5
  after="$(wc -l < "$FAKE_LOG")"
  [ $(( after - before )) -ge 1 ] || { echo "  no respawn launch"; return 1; }
  assert_eq "aaa111" "$(run_helper_status_id)" || return 1
}

# Second conversation: a SECOND window, first one untouched (still running).
test_switch_two_conversations() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  echo '{"type":"user"}' > "$PROJECTS/$enc/aaa111.jsonl"
  echo '{"type":"user"}' > "$PROJECTS/$enc/bbb222.jsonl"
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

# Two windows sharing ONE name (tmux allows duplicate names) must not blind
# the helper: identification/status/switch all target by INDEX
# (bug 2026-08-13: name-based targets failed with "can't find window").
test_duplicate_window_names() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  echo '{"type":"user"}' > "$PROJECTS/$enc/aaa111.jsonl"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111" >/dev/null || return 1
  sleep 0.5
  # Second window with the SAME name (new-window without -d selects it).
  tmux new-window -t "$SESSION" -n "rokid-aaa111" -c "$dir" \
    "$FAKE" --effort max --dangerously-skip-permissions --resume aaa111
  sleep 0.5
  # status must still report the ACTIVE window's claude (by index).
  assert_eq "aaa111" "$(run_helper_status_id)" || return 1
  # switching to the conversation finds ONE of them and creates NO third
  # window; the alive claude is selected, not restarted.
  local before after
  before="$(wc -l < "$FAKE_LOG")"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111" >/dev/null || return 1
  sleep 0.5
  after="$(wc -l < "$FAKE_LOG")"
  assert_eq "$before" "$after" || return 1
  assert_eq "2" "$(tmux list-windows -t "$SESSION" -F '#{window_name}' | wc -l | tr -d ' ')" || return 1
}

# --- status -------------------------------------------------------------

# status reports the ACTIVE window only: after two conversations, the last
# selected is reported; after selecting the other window, THAT one is.
test_status_active_window_only() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  echo '{"type":"user"}' > "$PROJECTS/$enc/aaa111.jsonl"
  echo '{"type":"user"}' > "$PROJECTS/$enc/bbb222.jsonl"
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

# --- delete -------------------------------------------------------------

# delete ends the conversation's window (its claude dies with it) and the
# JSONL; the other window is untouched.
test_delete_kills_window() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  echo '{"type":"user"}' > "$PROJECTS/$enc/aaa111.jsonl"
  echo '{"type":"user"}' > "$PROJECTS/$enc/bbb222.jsonl"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111" >/dev/null || return 1
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:bbb222" >/dev/null || return 1
  sleep 0.5
  local out
  out="$("$HELPER" delete "$SESSION" "$BASE" "$dir" "aaa111")" || return 1
  assert_eq "ok	$enc	aaa111" "$out" || return 1
  assert_eq "" "$(tmux list-windows -t "$SESSION" -F '#{window_name}' | grep 'rokid-aaa111')" || return 1
  [ ! -f "$PROJECTS/$enc/aaa111.jsonl" ] || { echo "  jsonl not deleted"; return 1; }
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
  [ ! -f "$PROJECTS/$enc/aaa111.jsonl" ] || { echo "  jsonl not deleted"; return 1; }
}

# delete of the ACTIVE window while a client is attached is refused.
test_delete_refuses_active_attached() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  echo '{"type":"user"}' > "$PROJECTS/$enc/aaa111.jsonl"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111" >/dev/null || return 1
  sleep 0.5
  # Simulate the app's attached client: tmux refuses a non-tty attach, so
  # run it inside a pty. TERM must be set (a non-interactive ssh context
  # leaves it unset and tmux refuses: "terminal does not support clear").
  # macOS: `script -q /dev/null CMD`; Linux: `script -qec CMD /dev/null`.
  if [ "$(uname)" = "Darwin" ]; then
    TERM=xterm script -q /dev/null tmux attach-session -t "$SESSION" >/dev/null 2>&1 &
  else
    TERM=xterm script -qec "tmux attach-session -t $SESSION" /dev/null >/dev/null 2>&1 &
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

# Deleting a never-messaged new chat SUCCEEDS: its window IS the whole
# conversation (no jsonl ever existed) — killed window + ok (bug 2026-08-12).
test_delete_never_messaged_new_chat() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "new:cccc333" >/dev/null || return 1
  sleep 0.5
  local out
  out="$("$HELPER" delete "$SESSION" "$BASE" "$dir" "cccc333")" || return 1
  assert_eq "ok	$enc	cccc333" "$out" || return 1
  assert_eq "" "$(tmux list-windows -t "$SESSION" -F '#{window_name}' | grep 'rokid-cccc333')" || return 1
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

# --- sweep --------------------------------------------------------------

# An idle background conversation is swept; the ACTIVE one is never swept.
test_sweep_kills_idle_keeps_active() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  echo '{"type":"user"}' > "$PROJECTS/$enc/aaa111.jsonl"
  echo '{"type":"user"}' > "$PROJECTS/$enc/bbb222.jsonl"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111" >/dev/null || return 1
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:bbb222" >/dev/null || return 1
  sleep 0.5
  # Make both transcripts old (idle); select aaa111 so it is the ACTIVE
  # window (never swept) and bbb222 is the idle background one.
  tmux select-window -t "$SESSION:rokid-aaa111"
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
  echo '{"type":"user"}' > "$PROJECTS/$enc/aaa111.jsonl"
  echo '{"type":"user"}' > "$PROJECTS/$enc/bbb222.jsonl"
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:aaa111" >/dev/null || return 1
  "$HELPER" switch "$SESSION" "$BASE" "$dir" "resume:bbb222" >/dev/null || return 1
  sleep 0.5
  local out
  out="$(SWEEP_SAMPLE_SLEEP=1 "$HELPER" sweep "$SESSION" "$BASE" 1)"
  assert_eq "swept	0" "$out" || return 1
  assert_eq "rokid-bbb222" "$(tmux display-message -p -t "$SESSION" '#{window_name}')" || return 1
}

# A background conversation with a live child (running tool) is NOT swept.
test_sweep_keeps_busy_child() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  echo '{"type":"user"}' > "$PROJECTS/$enc/bbb222.jsonl"
  tmux new-session -d -s "$SESSION" -n "rokid-bbb222" -c "$dir" \
    "ROKID_FAKE_CHILD=1 $FAKE --effort max --dangerously-skip-permissions --resume bbb222"
  sleep 0.5
  touch -t 200001010000 "$PROJECTS/$enc/bbb222.jsonl"
  local out
  out="$(SWEEP_SAMPLE_SLEEP=1 "$HELPER" sweep "$SESSION" "$BASE" 1)"
  assert_eq "swept	0" "$out" || return 1
}

# A background conversation whose claude burns CPU (long thinking) is NOT swept.
test_sweep_keeps_busy_cpu() {
  local dir="$BASE/proj" enc enc_dir
  enc="$(enc "$dir")"
  echo '{"type":"user"}' > "$PROJECTS/$enc/bbb222.jsonl"
  tmux new-session -d -s "$SESSION" -n "rokid-bbb222" -c "$dir" \
    "ROKID_FAKE_CPU=4 $FAKE --effort max --dangerously-skip-permissions --resume bbb222"
  sleep 0.5
  touch -t 200001010000 "$PROJECTS/$enc/bbb222.jsonl"
  local out
  out="$(SWEEP_SAMPLE_SLEEP=1 "$HELPER" sweep "$SESSION" "$BASE" 1)"
  assert_eq "swept	0" "$out" || return 1
}

# --- runner -------------------------------------------------------------

main() {
  run_case test_identification_cmdline
  run_case test_identification_fd_beats_cmdline
  run_case test_identification_unidentified
  run_case test_identification_no_claude
  run_case test_switch_creates
  run_case test_switch_selects_without_restart
  run_case test_switch_respawns_dead
  run_case test_switch_two_conversations
  run_case test_switch_new_and_resume_jsonl
  run_case test_duplicate_window_names
  run_case test_status_active_window_only
  run_case test_status_renames_stale_window
  run_case test_status_none
  run_case test_delete_kills_window
  run_case test_delete_skips_stale_window
  run_case test_delete_refuses_active_attached
  run_case test_delete_never_messaged_new_chat
  run_case test_adopt_renames
  run_case test_sweep_kills_idle_keeps_active
  run_case test_sweep_keeps_recent
  run_case test_sweep_keeps_busy_child
  run_case test_sweep_keeps_busy_cpu
  echo "== $PASS passed, $FAIL failed"
  [ "$FAIL" -eq 0 ]
}
main
