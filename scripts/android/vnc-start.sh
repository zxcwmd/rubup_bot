#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

for command in Xvfb x11vnc openbox xdpyinfo; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Missing $command. Run scripts/android/install.sh first."
    exit 1
  fi
done

export DISPLAY=:0
RUNTIME_DIR=/tmp/rubup-bot-vnc
mkdir -p "$RUNTIME_DIR"

if ! xdpyinfo >/dev/null 2>&1; then
  Xvfb :0 -screen 0 1280x800x24 -nolisten tcp >"$RUNTIME_DIR/xvfb.log" 2>&1 &
  echo $! >"$RUNTIME_DIR/xvfb.pid"
  sleep 1
fi
if ! xdpyinfo >/dev/null 2>&1; then
  echo "Xvfb did not start; see $RUNTIME_DIR/xvfb.log"
  exit 1
fi

if [ ! -f "$RUNTIME_DIR/openbox.pid" ] || ! kill -0 "$(cat "$RUNTIME_DIR/openbox.pid")" 2>/dev/null; then
  openbox >"$RUNTIME_DIR/openbox.log" 2>&1 &
  echo $! >"$RUNTIME_DIR/openbox.pid"
fi
if [ ! -f "$RUNTIME_DIR/x11vnc.pid" ] || ! kill -0 "$(cat "$RUNTIME_DIR/x11vnc.pid")" 2>/dev/null; then
  x11vnc -display :0 -localhost -nopw -forever -shared -rfbport 5900 \
    >"$RUNTIME_DIR/x11vnc.log" 2>&1 &
  echo $! >"$RUNTIME_DIR/x11vnc.pid"
  sleep 1
fi
if ! kill -0 "$(cat "$RUNTIME_DIR/x11vnc.pid")" 2>/dev/null; then
  echo "x11vnc did not start; see $RUNTIME_DIR/x11vnc.log"
  exit 1
fi

printf 'VNC is ready only on this phone: 127.0.0.1:5900 (no password, localhost only).\n'
printf 'Connect with AVNC, then run scripts/android/login.sh in Ubuntu proot.\n'
