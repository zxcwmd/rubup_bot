#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
# Environment variables take precedence over .env in Settings.load().
export HEADLESS="${HEADLESS:-true}"
exec .venv/bin/python -m rubup_bot run
