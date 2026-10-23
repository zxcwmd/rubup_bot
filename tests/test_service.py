from __future__ import annotations

import asyncio
import shutil
from pathlib import Path

import pytest
from PIL import Image

from rubup_bot.config import Settings
from rubup_bot.db import Database
from rubup_bot.models import ActionType, LikeStatus, PublishResult, TikTokComment
from rubup_bot.service import AutomationService


class BrokenSink:
    async def action_event(self, action_id: int, event: str, detail: str = "") -> None:
        raise RuntimeError("Telegram is unavailable")

    async def info(self, text: str) -> None:
        raise RuntimeError("Telegram is unavailable")


class FakeTikTok:
    def __init__(self, comments: list[TikTokComment], *, like_result: bool = True) -> None:
        self.comments = comments
        self.calls: list[dict] = []
        self.likes: list[str] = []
        self.like_result = like_result
        self.closed = False

    async def get_comments(self, url: str):
        return self.comments

    async def post_comment(
        self, video_url: str, text: str, *, image_path=None, reply_to=None, stop_event=None
    ):
        self.calls.append({
            "video_url": video_url,
            "text": text,
            "image_path": image_path,
            "reply_to": reply_to,
        })
        return PublishResult(submitted=True, verified=True)

    async def like_own_comment(
        self, video_url: str, comment_text: str, *, stop_event=None
    ) -> bool:
        self.likes.append(comment_text)
        return self.like_result

    async def delay_between_actions(self, stop_event=None) -> None:
        return None

    async def close(self) -> None:
        self.closed = True


class BlockingFakeTikTok(FakeTikTok):
    def __init__(self) -> None:
        super().__init__([])
        self.entered = asyncio.Event()
        self.release = asyncio.Event()

    async def post_comment(
        self, video_url: str, text: str, *, image_path=None, reply_to=None, stop_event=None
    ):
        self.entered.set()
        await self.release.wait()
        return await super().post_comment(
            video_url, text, image_path=image_path, reply_to=reply_to, stop_event=stop_event
        )


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
        assert fake.likes == [comment["text"]]
        assert settings.links_file.read_text(encoding="utf-8").splitlines() == [video.url]

        stats = await db.stats()
        assert stats["published"] == 2
        assert stats["images_used"] == 1
        assert stats["links"] == 1
        assert stats["pending_likes"] == 0
        assert stats["unreported"] == 2
        report_id = await db.create_report()
        assert report_id is not None
        # Two payable actions intentionally produce two link lines, even on one video.
        assert await db.report_links(report_id) == [video.url, video.url]
        assert await db.queued_videos(10) == []

        # A notification error must not downgrade a confirmed publication.
        action_history = next(row for row in await db.history() if row["action_type"] == "comment")
        action_row = await db.get_action(action_history["id"])
        service.sink = BrokenSink()
        with pytest.raises(RuntimeError, match="Telegram is unavailable"):
            await service._publish_existing(action_row, video)
        assert (await db.get_action(action_row["id"]))["status"] == "published"
        await service.force_stop()
        assert fake.closed
        assert not await service.is_enabled()
        await ui.close()
    finally:
        await db.close()


async def test_unconfirmed_like_blocks_link_until_retry(tmp_path: Path, monkeypatch, video) -> None:
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
    Image.new("RGB", (20, 20), "blue").save(settings.image_dir / "asset.png")
    db = Database(settings.database_path)
    await db.open()
    service = AutomationService(settings, db)
    try:
        await service.initialize()
        await service.start_automation()
        fake = FakeTikTok([], like_result=False)
        service.client = fake
        await db.upsert_video(video, eligible=True)

        assert await service._process_video(video, 1) == 1
        action = (await db.history())[0]
        assert action["action_type"] == ActionType.COMMENT.value
        assert action["status"] == "published"
        assert action["like_status"] == LikeStatus.MANUAL_REVIEW.value
        assert not action["link_saved_at"]
        assert not settings.links_file.exists()
        assert await db.create_report() is None

        fake.like_result = True
        await service.retry_like(action["id"])
        completed = await db.get_action(action["id"])
        assert completed["like_status"] == LikeStatus.LIKED.value
        assert completed["link_saved_at"]
        assert settings.links_file.read_text(encoding="utf-8").splitlines() == [video.url]
        assert await db.create_report() is not None
    finally:
        await service.close()
        await db.close()


async def test_force_stop_waits_for_call_and_blocks_like(tmp_path: Path, monkeypatch, video) -> None:
    root = Path(__file__).resolve().parents[1]
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", "123:test")
    monkeypatch.setenv("ADMIN_USER_IDS", "1")
    monkeypatch.setenv("ACTION_DELAY_SECONDS", "0")
    monkeypatch.setenv("ACTION_JITTER_SECONDS", "0")
    shutil.copytree(root / "templates", tmp_path / "templates")
    shutil.copy(root / "selectors.json", tmp_path / "selectors.json")
    settings = Settings.load(env_file=tmp_path / ".env.missing")
    settings.ensure_directories()
    Image.new("RGB", (20, 20), "black").save(settings.image_dir / "asset.png")
    db = Database(settings.database_path)
    await db.open()
    service = AutomationService(settings, db)
    try:
        await service.initialize()
        await service.start_automation()
        fake = BlockingFakeTikTok()
        service.client = fake
        await db.upsert_video(video, eligible=True)
        image = await db.next_available_image()
        action_id = await db.create_action(video, ActionType.COMMENT, "Стоп Реклама", image=image)
        action = await db.get_action(action_id)
        assert action is not None

        await service._locked()
        publish_task = asyncio.create_task(service._publish_existing(action, video))
        stop_task: asyncio.Task | None = None
        try:
            await asyncio.wait_for(fake.entered.wait(), timeout=1)
            stop_task = asyncio.create_task(service.force_stop())
            await asyncio.wait_for(service._stop_event.wait(), timeout=1)
            assert not await service.is_enabled()
            assert not fake.closed
            assert not stop_task.done()
        finally:
            fake.release.set()
            await publish_task
            if service._operation_lock.locked() and (stop_task is None or not stop_task.done()):
                service._operation_lock.release()
            if stop_task is not None:
                await stop_task

        assert fake.closed
        assert fake.likes == []
        stopped = await db.get_action(action_id)
        assert stopped["status"] == "published"
        assert stopped["like_status"] == LikeStatus.PENDING.value
        assert not stopped["link_saved_at"]
        assert not settings.links_file.exists()
    finally:
        await service.close()
        await db.close()
