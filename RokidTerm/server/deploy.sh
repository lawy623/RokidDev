#!/usr/bin/env bash
# One-command deploy + smoke test for the rokid-sessions helper on a NEW
# server (user requirement 2026-08-11). Usage:
#   bash server/deploy.sh <user>@<host> [launcher-path]
#   # e.g. bash server/deploy.sh rokid@1.2.3.4 /home/rokid/bin/rokid-claude
# Prerequisites on the target: ssh/scp access, bash, tmux, python3,
# procps (ps/pgrep), coreutils (stat), and optionally lsof (fallback only).
# The harness (server/test) runs on the target and exercises the REAL /proc
# fd-scan and Linux jiffy CPU units — the same paths the app will use.
set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
TARGET="${1:-}"
LAUNCHER="${2:-/home/rokid/bin/rokid-claude}"
[ -n "$TARGET" ] || { echo "usage: bash server/deploy.sh <user>@<host> [launcher-path]"; exit 1; }
REMOTE_DIR="rokidterm-server"

for tool in ssh scp tmux; do command -v "$tool" >/dev/null || { echo "missing local: $tool"; exit 1; }; done

echo "== deploy helper to $TARGET"
scp "$ROOT/rokid-sessions" "$TARGET:/home/${TARGET%@*}/bin/rokid-sessions" \
  || { echo "FAILED: scp helper"; exit 1; }
ssh "$TARGET" "chmod +x /home/${TARGET%@*}/bin/rokid-sessions" || exit 1

echo "== prerequisites on target"
ssh "$TARGET" 'for t in tmux python3 ps pgrep stat; do command -v '"$t"' >/dev/null || echo "missing: '"$t"'"; done; [ -x '"$LAUNCHER"' ] || echo "note: launcher not found at '"$LAUNCHER"' (override with arg 2)"'

echo "== run smoke harness on target (/proc paths)"
scp -r "$ROOT/test" "$TARGET:/tmp/$REMOTE_DIR" >/dev/null 2>&1 || { echo "FAILED: scp tests"; exit 1; }
ssh "$TARGET" "bash /tmp/$REMOTE_DIR/helper_test.sh && rm -rf /tmp/$REMOTE_DIR" \
  || { echo "FAILED: smoke"; exit 1; }
echo "== deployed + smoke OK"
