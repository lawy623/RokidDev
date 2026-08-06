#!/usr/bin/env bash
set -euo pipefail

REMOTE_HOST="${REMOTE_HOST:-rokid-server}"
REMOTE_DIR="${REMOTE_DIR:-/srv/RokidAsrServer}"
KEY="${SSH_KEY:-$HOME/Desktop/<your-key>.pem}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARBALL="$(mktemp /tmp/rokid-asr-server.XXXXXX.tar.gz)"
trap 'rm -f "$TARBALL"' EXIT

tar -C "$SCRIPT_DIR" --exclude='.venv' --exclude='__pycache__' -czf "$TARBALL" .
ssh -i "$KEY" -o IdentitiesOnly=yes "$REMOTE_HOST" "mkdir -p '$REMOTE_DIR'"
scp -i "$KEY" -o IdentitiesOnly=yes "$TARBALL" "$REMOTE_HOST:/tmp/rokid-asr-server.tar.gz"
ssh -i "$KEY" -o IdentitiesOnly=yes "$REMOTE_HOST" \
  "rm -rf '$REMOTE_DIR/app' '$REMOTE_DIR/benchmark' '$REMOTE_DIR/tests' '$REMOTE_DIR/README.md' '$REMOTE_DIR/requirements.txt' '$REMOTE_DIR/deploy.sh'; tar -xzf /tmp/rokid-asr-server.tar.gz -C '$REMOTE_DIR'; rm /tmp/rokid-asr-server.tar.gz"
