from __future__ import annotations

import sys
from pathlib import Path

from rubup_bot.__main__ import main


def test_init_works_before_env_configuration(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.chdir(tmp_path)
    monkeypatch.delenv("TELEGRAM_BOT_TOKEN", raising=False)
    monkeypatch.delenv("ADMIN_USER_IDS", raising=False)
    monkeypatch.setattr(sys, "argv", ["rubup-bot", "init"])
    assert main() == 0
    assert (tmp_path / "data" / "rubup.sqlite3").is_file()
    assert (tmp_path / "images").is_dir()


def test_run_requires_telegram_token(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.chdir(tmp_path)
    monkeypatch.delenv("TELEGRAM_BOT_TOKEN", raising=False)
    monkeypatch.setattr(sys, "argv", ["rubup-bot", "run"])
    assert main() == 1
