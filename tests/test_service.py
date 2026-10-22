from __future__ import annotations

import shutil
from pathlib import Path

import pytest
from PIL import Image

from rubup_bot.config import Settings
from rubup_bot.db import Database
from rubup_bot.models import PublishResult, TikTokComment
from rubup_bot.service import AutomationService


class BrokenSink:
    async def action_event(self, action_id: int, event: str, detail: str = "") -> None:
        raise RuntimeError("Telegram is unavailable")

    async def info(self, text: str) -> None:
        raise RuntimeError("Telegram is unavailable")


class FakeTikTok:
    def __init__(self, comments: list[TikTokComment]) -> None:
        self.comments = comments
        self.calls: list[dict] = []

    async def get_comments(self, url: str):
        return self.comments

    async def post_comment(self, video_url: str, text: str, *, image_path=None, reply_to=None):
        self.calls.append({
            "video_url": video_url,
            "text": text,
            "image_path": image_path,
            "reply_to": reply_to,
        })
        return PublishResult(submitted=True, verified=True)

    async def delay_between_actions(self) -> None:
        return None

    async def close(self) -> None:
        return None


async def test_full_comment_and_reply_flow(tmp_path: Path, monkeypatch, video) -> None:
    root = Path(__file__).resolve().parents[1]
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", "123:test")
    monkeypatch.setenv("ADMIN_USER_IDS", "1")
    monkeypatch.setenv("TIKTOK_USERNAME", "myaccount")
    monkeypatch.setenv("ACTION_DELAY_SECONDS", "0")
    monkeypatch.setenv("ACTION_JITTER_SECONDS", "0")
    shutil.copytree(root / "templates", tmp_path / "templates")
    shutil.copy(root / "selectors.json", tmp_path / "selectors.json")
    settings = Settings.load(env_file=tmp_path / ".env.missing")
    settings.ensure_directories()
    Image.new("RGB", (20, 20), "purple").save(settings.image_dir / "asset.png")
    db = Database(settings.database_path)
    await db.open()
    try:
        service = AutomationService(settings, db)
        await service.initialize()
        await service.start_automation()
        fake = FakeTikTok([
            TikTokComment("other-rubup", video.id, "worker", "RubUP: удачный момент"),
            TikTokComment("own-rubup", video.id, "myaccount", "RubUP: мой комментарий"),
            TikTokComment("unrelated", video.id, "viewer", "Просто хорошее видео"),
        ])
        service.client = fake
        # Constructing the UI registers all aiogram handlers and middleware.
        from rubup_bot.telegram import TelegramUI

        ui = TelegramUI(settings, db, service)
        await db.upsert_video(video, eligible=True)

        attempts = await service._process_video(video, 5)
        assert attempts == 2
        assert len(fake.calls) == 2
        reply, comment = fake.calls
        assert reply["reply_to"].id == "other-rubup"
        assert reply["image_path"] is None
        assert comment["reply_to"] is None
        assert comment["image_path"] is not None
        assert all("Реклама" in call["text"] for call in fake.calls)

        stats = await db.stats()
        assert stats["published"] == 2
        assert stats["images_used"] == 1
        assert stats["unreported"] == 2
        report_id = await db.create_report()
        assert report_id is not None
        # Two payable actions intentionally produce two link lines, even on one video.
        assert await db.report_links(report_id) == [video.url, video.url]
        assert await db.queued_videos(10) == []

        # A notification error must not downgrade a confirmed publication.
        action_row = next(row for row in await db.history() if row["action_type"] == "comment")
        service.sink = BrokenSink()
        with pytest.raises(RuntimeError, match="Telegram is unavailable"):
            await service._publish_existing(action_row, video)
        assert (await db.get_action(action_row["id"]))["status"] == "published"
        await ui.close()
    finally:
        await db.close()
