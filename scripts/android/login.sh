#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
bash scripts/android/vnc-start.sh
export DISPLAY=:0
.venv/bin/python -m rubup_bot login
printf '\nLogin finished. You may now run scripts/android/vnc-stop.sh and scripts/android/start.sh.\n'
