from pathlib import Path

import pytest

from rubup_bot.db import Database
from rubup_bot.models import (
    ActionStatus,
    ActionType,
    DuplicateAction,
    LikeStatus,
    TikTokComment,
    Video,
)


async def test_comment_and_reply_uniqueness(db: Database, video: Video) -> None:
    await db.upsert_video(video, eligible=True)
    first = await db.create_action(video, ActionType.COMMENT, "Первый текст Реклама")
    assert first > 0
    assert await db.action_exists("comment:" + video.id)
    with pytest.raises(DuplicateAction):
        await db.create_action(video, ActionType.COMMENT, "Другой текст Реклама")

    target = TikTokComment("comment-1", video.id, "other", "RubUP комментарий")
    await db.create_action(video, ActionType.REPLY, "Ответ Реклама", target=target)
    with pytest.raises(DuplicateAction):
        await db.create_action(video, ActionType.REPLY, "Ещё ответ Реклама", target=target)


async def test_normalized_text_cannot_be_reused_on_another_video(db: Database, video: Video) -> None:
    await db.upsert_video(video, eligible=True)
    await db.create_action(video, ActionType.COMMENT, "Одинаковый текст! Реклама")
    other = Video(
        id="7300000000000000002",
        url="https://www.tiktok.com/@other/video/7300000000000000002",
        published_at=video.published_at,
        matched_tags=("cs2",),
    )
    await db.upsert_video(other, eligible=True)
    with pytest.raises(DuplicateAction):
        await db.create_action(other, ActionType.COMMENT, "одинаковый текст Реклама")


async def test_image_is_reserved_then_used_once(db: Database, video: Video, tmp_path: Path) -> None:
    await db.upsert_video(video, eligible=True)
    first_path = tmp_path / "one.png"
    second_path = tmp_path / "two.png"
    first_path.write_bytes(b"one")
    second_path.write_bytes(b"two")
    await db.add_image(path=first_path, source_path=first_path, sha256="a" * 64)
    await db.add_image(path=second_path, source_path=second_path, sha256="b" * 64)
    image = await db.next_available_image()
    assert image and image.sha256 == "a" * 64
    action_id = await db.create_action(video, ActionType.COMMENT, "С фото Реклама", image=image)
    assert (await db.next_available_image()).sha256 == "b" * 64
    await db.set_action_status(action_id, ActionStatus.PUBLISHED)
    counts = await db.image_counts()
    assert counts == {"available": 1, "reserved": 0, "used": 1}


async def test_link_requires_photo_even_with_like(db: Database, video: Video, tmp_path: Path) -> None:
    await db.upsert_video(video, eligible=True)
    action_id = await db.create_action(video, ActionType.COMMENT, "Без фото Реклама")
    await db.set_action_status(action_id, ActionStatus.PUBLISHED)
    await db.set_like_status(action_id, LikeStatus.LIKED)
    with pytest.raises(RuntimeError, match="Link rejected"):
        await db.save_link(action_id, video.url, tmp_path / "links.txt")


async def test_report_contains_only_published_and_is_not_reused(
    db: Database, video: Video, tmp_path: Path
) -> None:
    await db.upsert_video(video, eligible=True)
    image_path = tmp_path / "report.png"
    image_path.write_bytes(b"report-image")
    await db.add_image(path=image_path, source_path=image_path, sha256="c" * 64)
    image = await db.next_available_image()
    published = await db.create_action(video, ActionType.COMMENT, "Готово Реклама", image=image)
    await db.set_action_status(published, ActionStatus.PUBLISHED)
    target = TikTokComment("target", video.id, "other", "RubUP")
    await db.create_action(video, ActionType.REPLY, "Проверить Реклама", target=target)

    assert await db.create_report() is None  # photo comment has no confirmed like yet
    with pytest.raises(RuntimeError, match="Link rejected"):
        await db.save_link(published, video.url, tmp_path / "links.txt")
    await db.set_like_status(published, LikeStatus.LIKED)
    assert await db.create_report() is None  # liked, but not durably saved yet
    await db.save_link(published, video.url, tmp_path / "links.txt")

    report_id = await db.create_report()
    assert report_id is not None
    assert await db.report_links(report_id) == [video.url]
    assert await db.create_report() == report_id  # pending report is stable
    await db.mark_report_sent(report_id)
    assert await db.create_report() is None
    assert (await db.reports())[0]["action_count"] == 1
