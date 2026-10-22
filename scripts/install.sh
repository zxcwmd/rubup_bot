#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v python3 >/dev/null 2>&1; then
  echo "Python 3.11+ not found. Install it with your system package manager."
  exit 1
fi
if ! python3 -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)'; then
  echo "Python 3.11+ is required. Found: $(python3 --version 2>&1)"
  exit 1
fi
python3 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/playwright install chromium
[ -f .env ] || cp .env.example .env
.venv/bin/python -m rubup_bot init
printf '\nУстановка завершена. Заполните .env, затем выполните scripts/login.sh\n'
