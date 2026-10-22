#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/playwright install chromium
[ -f .env ] || cp .env.example .env
.venv/bin/python -m rubup_bot init
printf '\nУстановка завершена. Заполните .env, затем выполните scripts/login.sh\n'
