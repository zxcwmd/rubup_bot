#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

if ! command -v apt-get >/dev/null 2>&1; then
  echo "Run this script INSIDE Ubuntu proot, not directly in Termux."
  exit 1
fi
if [ "$(id -u)" -ne 0 ]; then
  echo "Run from 'proot-distro login ubuntu' (root inside proot is enough; Android root is not required)."
  exit 1
fi
case "$(uname -m)" in
  aarch64|arm64|x86_64|amd64) ;;
  *) echo "Unsupported CPU: $(uname -m). Playwright Chromium requires arm64 or x86-64."; exit 1 ;;
esac

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y \
  ca-certificates curl git nano unzip \
  python3 python3-venv python3-pip \
  xvfb x11vnc openbox xauth x11-utils

bash scripts/install.sh
.venv/bin/playwright install-deps chromium

printf '\nUbuntu/Playwright installation finished.\n'
printf 'Next: edit .env, run scripts/android/vnc-start.sh, connect AVNC to 127.0.0.1:5900,\n'
printf 'then run scripts/android/login.sh. After login use scripts/android/start.sh.\n'
