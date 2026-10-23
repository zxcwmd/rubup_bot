#!/usr/bin/env bash
set -euo pipefail
RUNTIME_DIR=/tmp/rubup-bot-vnc
for name in x11vnc openbox xvfb; do
  pid_file="$RUNTIME_DIR/$name.pid"
  if [ -f "$pid_file" ]; then
    pid="$(cat "$pid_file")"
    kill "$pid" 2>/dev/null || true
    rm -f "$pid_file"
  fi
done
printf 'Local VNC/display processes stopped. The saved TikTok profile was not deleted.\n'
