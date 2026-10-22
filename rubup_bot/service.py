from __future__ import annotations

import asyncio
import json
import logging
import re
from pathlib import Path
from typing import Any, Protocol

from .config import Settings
from .db import Database
from .images import ImageLibrary
from .models import (
    ActionStatus,
    ActionType,
    BotError,
    ChallengeRequired,
    DuplicateAction,
    ManualActionRequired,
    SessionRequired,
    TikTokComment,
    Video,
)
from .rules import CommentRules, video_decision
from .text import CommentGenerator
from .tiktok import TikTokClient, canonical_video_url

logger = logging.getLogger(__name__)


class EventSink(Protocol):
    async def action_event(self, action_id: int, event: str, detail: str = "") -> None: ...
    async def info(self, text: str) -> None: ...


class NullSink:
    async def action_event(self, action_id: int, event: str, detail: str = "") -> None:
        return None

    async def info(self, text: str) -> None:
        return None


class AutomationService:
    def __init__(self, settings: Settings, db: Database) -> None:
        self.settings = settings
        self.db = db
        self.client = TikTokClient(settings)
        self.images = ImageLibrary(settings.image_dir, settings.image_library_dir, db)
        self.generator = CommentGenerator(
            settings.template_dir,
            settings.ad_disclosure,
            settings.similarity_threshold,
            settings.allow_gift_claims,
        )
        self.rules = CommentRules(
            own_username=settings.tiktok_username,
            rubup_markers=settings.rubup_comment_markers,
            target_similarity_threshold=settings.target_similarity_threshold,
        )
        self.sink: EventSink = NullSink()
        self._wake = asyncio.Event()
        self._operation_lock = asyncio.Lock()
        self._scheduler_task: asyncio.Task[None] | None = None

    async def initialize(self) -> None:
        self.settings.ensure_directories()
        if await self.db.get_setting("auto_enabled") is None:
            await self.db.set_setting("auto_enabled", "1" if self.settings.auto_start else "0")
        if await self.db.get_setting("tags") is None:
            await self.db.set_setting("tags", json.dumps(self.settings.hashtags, ensure_ascii=False))
        await self.scan_images()

    async def is_enabled(self) -> bool:
        return await self.db.get_setting("auto_enabled", "0") == "1"

    async def start_automation(self) -> None:
        await self.db.set_setting("auto_enabled", "1")
        self._wake.set()

    async def pause_automation(self) -> None:
        await self.db.set_setting("auto_enabled", "0")
        self._wake.set()

    async def get_tags(self) -> list[str]:
        raw = await self.db.get_setting("tags")
        if not raw:
            return list(self.settings.hashtags)
        return [str(tag) for tag in json.loads(raw)]

    async def set_tags(self, tags: list[str]) -> list[str]:
        clean: list[str] = []
        for tag in tags:
            tag = tag.strip().lstrip("#").lower()
            if not tag or not re.fullmatch(r"[\w_]+", tag, re.UNICODE):
                raise ValueError(f"Некорректный тег: {tag!r}")
            if tag not in clean:
                clean.append(tag)
        if not clean:
            raise ValueError("Нужен хотя бы один тег")
        await self.db.set_setting("tags", json.dumps(clean, ensure_ascii=False))
        await self.db.set_setting("tag_cursor", "0")
        return clean

    async def scan_images(self):
        stats = await self.images.scan()
        if stats.added:
            await self.db.requeue_waiting_for_images()
        return stats

    def start_scheduler(self) -> None:
        if self._scheduler_task is None or self._scheduler_task.done():
            self._scheduler_task = asyncio.create_task(self.scheduler(), name="rubup-scheduler")

    async def scheduler(self) -> None:
        while True:
            if not await self.is_enabled():
                await self._wake.wait()
                self._wake.clear()
                continue
            try:
                result = await self.run_cycle()
                await self.sink.info(
                    f"Цикл завершён: новых видео {result['new_videos']}, "
                    f"попыток действий {result['attempts']}."
                )
            except (ChallengeRequired, SessionRequired) as exc:
                await self.pause_automation()
                await self.sink.info(f"⏸ Автоматика на паузе: {exc}")
            except BotError as exc:
                await self.sink.info(f"Цикл остановлен: {exc}")
            except Exception as exc:
                logger.exception("Unexpected scheduler failure")
                await self.sink.info(f"Ошибка цикла: {type(exc).__name__}: {exc}")
            try:
                await asyncio.wait_for(
                    self._wake.wait(), timeout=self.settings.scan_interval_minutes * 60
                )
                self._wake.clear()
            except TimeoutError:
                pass

    async def close(self) -> None:
        if self._scheduler_task:
            self._scheduler_task.cancel()
            try:
                await self._scheduler_task
            except asyncio.CancelledError:
                pass
        await self.client.close()

    async def _locked(self) -> None:
        if self._operation_lock.locked():
            raise BotError("Другая операция с браузером уже выполняется")
        await self._operation_lock.acquire()

    async def check_session(self) -> tuple[bool, str]:
        await self._locked()
        try:
            return await self.client.check_session()
        finally:
            self._operation_lock.release()

    async def discover_once(self) -> dict[str, int]:
        await self._locked()
        try:
            await self.client.ensure_authenticated()
            return await self._discover()
        finally:
            self._operation_lock.release()

    async def add_video(self, url: str) -> tuple[Video, bool, str]:
        await self._locked()
        try:
            await self.client.ensure_authenticated()
            video = await self.client.get_video(url)
            allowed = set(await self.get_tags())
            video.matched_tags = tuple(tag for tag in video.matched_tags if tag in allowed)
            decision = video_decision(video, self.settings.min_video_date)
            await self.db.upsert_video(video, eligible=decision.allowed, reason=decision.reason)
            return video, decision.allowed, decision.reason
        finally:
            self._operation_lock.release()

    async def run_cycle(self) -> dict[str, int]:
        await self._locked()
        try:
            await self.scan_images()
            await self.client.ensure_authenticated()
            discovery = await self._discover()
            attempts = await self._retry_pending(self.settings.max_actions_per_cycle)
            if attempts < self.settings.max_actions_per_cycle:
                attempts += await self._process_queue(self.settings.max_actions_per_cycle - attempts)
            return {**discovery, "attempts": attempts}
        finally:
            self._operation_lock.release()

    async def _discover(self) -> dict[str, int]:
        tags = await self.get_tags()
        if not tags:
            return {"new_videos": 0, "eligible": 0, "skipped": 0}
        cursor = int(await self.db.get_setting("tag_cursor", "0") or 0) % len(tags)
        count = min(self.settings.max_tags_per_cycle, len(tags))
        selected = [tags[(cursor + offset) % len(tags)] for offset in range(count)]
        await self.db.set_setting("tag_cursor", str((cursor + count) % len(tags)))
        known_urls = await self.db.all_video_urls()
        stats = {"new_videos": 0, "eligible": 0, "skipped": 0}
        details_budget = self.settings.max_video_details_per_cycle
        allowed = set(tags)
        for tag in selected:
            if details_budget <= 0:
                break
            try:
                urls = await self.client.discover_videos(tag, self.settings.discovery_limit_per_tag)
            except (ChallengeRequired, SessionRequired):
                raise
            except Exception as exc:
                logger.warning("Discovery failed for #%s: %s", tag, exc)
                continue
            for url in urls:
                if details_budget <= 0:
                    break
                canonical = canonical_video_url(url)
                if not canonical or canonical in known_urls:
                    continue
                details_budget -= 1
                try:
                    video = await self.client.get_video(canonical, source_tag=tag)
                    video.matched_tags = tuple(item for item in video.matched_tags if item in allowed)
                    decision = video_decision(video, self.settings.min_video_date)
                    await self.db.upsert_video(video, eligible=decision.allowed, reason=decision.reason)
                    known_urls.add(video.url)
                    stats["new_videos"] += 1
                    stats["eligible" if decision.allowed else "skipped"] += 1
                except (ChallengeRequired, SessionRequired):
                    raise
                except Exception as exc:
                    logger.info("Cannot inspect %s: %s", canonical, exc)
                    stats["skipped"] += 1
        return stats

    def _target_from_action(self, action: dict[str, Any]) -> TikTokComment | None:
        if action["action_type"] != ActionType.REPLY.value:
            return None
        return TikTokComment(
            id=action["target_comment_id"],
            video_id=action["video_id"],
            author=action["target_author"],
            text=action["target_text"],
        )

    async def _retry_pending(self, budget: int) -> int:
        attempts = 0
        if budget <= 0:
            return attempts
        for action in await self.db.pending_actions(budget):
            if not await self.is_enabled():
                break
            video = await self.db.get_video(action["video_id"])
            if not video:
                await self.db.set_action_status(action["id"], ActionStatus.FAILED, "Видео отсутствует в БД")
                continue
            await self._publish_existing(action, video)
            attempts += 1
            await self.client.delay_between_actions()
        return attempts

    async def _process_queue(self, budget: int) -> int:
        attempts = 0
        videos = await self.db.queued_videos(max(10, budget * 4))
        for video in videos:
            if attempts >= budget or not await self.is_enabled():
                break
            try:
                attempts += await self._process_video(video, budget - attempts)
            except (ChallengeRequired, SessionRequired):
                raise
            except Exception as exc:
                logger.exception("Video processing failed: %s", video.url)
                await self.db.set_video_state(video.id, "attention", str(exc))
                await self.sink.info(f"⚠️ {video.url}\n{type(exc).__name__}: {exc}")
        return attempts

    async def _process_video(self, video: Video, budget: int) -> int:
        attempts = 0
        comments = await self.client.get_comments(video.url)
        has_prior_actions = await self.db.video_has_actions(video.id)
        if not has_prior_actions and any(
            self.rules.is_own(comment) and comment.has_image for comment in comments
        ):
            await self.db.set_video_state(video.id, "processed", "Свой комментарий с фото уже есть")
            return attempts

        previous_targets = await self.db.previous_target_texts()
        replies_today = await self.db.count_actions_today(ActionType.REPLY)
        replies_here = 0
        for target in comments:
            if not await self.is_enabled():
                return attempts
            if attempts >= budget or replies_here >= self.settings.max_replies_per_video:
                break
            if replies_today >= self.settings.max_replies_per_day:
                break
            decision = self.rules.reply_decision(target, previous_targets)
            if not decision.allowed:
                continue
            key = self.db.dedupe_key(ActionType.REPLY, video.id, target.id)
            if await self.db.action_exists(key):
                continue
            try:
                forbidden = await self.db.used_texts(ActionType.REPLY)
                forbidden.extend(item.text for item in comments)
                text = self.generator.generate(
                    video, ActionType.REPLY, forbidden, target_author=target.author
                )
                action_id = await self.db.create_action(
                    video, ActionType.REPLY, text, target=target
                )
            except DuplicateAction:
                continue
            except RuntimeError as exc:
                logger.info("Reply generation skipped for %s: %s", video.url, exc)
                break
            action = await self.db.get_action(action_id)
            assert action is not None
            await self._publish_existing(action, video)
            attempts += 1
            replies_today += 1
            replies_here += 1
            previous_targets.append(target.text)
            await self.client.delay_between_actions()
            if attempts >= budget:
                break

        if attempts >= budget or not await self.is_enabled():
            return attempts
        comments_today = await self.db.count_actions_today(ActionType.COMMENT)
        if comments_today >= self.settings.max_comments_per_day:
            return attempts
        image = await self.db.next_available_image()
        if not image:
            await self.db.set_video_state(video.id, "waiting_images", "Нет новых изображений")
            await self.sink.info("🖼 Закончились доступные картинки. Добавьте файлы в папку images.")
            return attempts
        forbidden = await self.db.used_texts(ActionType.COMMENT)
        forbidden.extend(item.text for item in comments)
        text = self.generator.generate(video, ActionType.COMMENT, forbidden)
        try:
            action_id = await self.db.create_action(video, ActionType.COMMENT, text, image=image)
        except DuplicateAction:
            await self.db.set_video_state(video.id, "attention", "Действие уже существует")
            return attempts
        action = await self.db.get_action(action_id)
        assert action is not None
        await self._publish_existing(action, video)
        attempts += 1
        await self.client.delay_between_actions()
        return attempts

    async def _publish_existing(self, action: dict[str, Any], video: Video) -> None:
        action_id = int(action["id"])
        action_type = ActionType(action["action_type"])
        await self.db.set_action_status(action_id, ActionStatus.PUBLISHING)
        image_path = action.get("image_path")
        try:
            result = await self.client.post_comment(
                video.url,
                action["text"],
                image_path=Path(image_path) if image_path else None,
                reply_to=self._target_from_action(action),
            )
        except ManualActionRequired as exc:
            await self.db.set_action_status(action_id, ActionStatus.MANUAL_REVIEW, str(exc))
            if action_type == ActionType.COMMENT:
                await self.db.set_video_state(video.id, "attention", str(exc))
            await self.db.audit("manual_review", f"action={action_id}; {exc}")
            await self.sink.action_event(action_id, "manual", str(exc))
            return
        except ChallengeRequired as exc:
            # post_comment converts post-click challenges to ManualActionRequired.
            # Reaching this handler means no submit click was reported.
            await self.db.set_action_status(action_id, ActionStatus.PENDING, str(exc))
            await self.sink.action_event(action_id, "pending", str(exc))
            raise
        except Exception as exc:
            await self.db.set_action_status(action_id, ActionStatus.FAILED, str(exc))
            if action_type == ActionType.COMMENT:
                await self.db.set_video_state(video.id, "attention", str(exc))
            await self.db.audit("failed", f"action={action_id}; {exc}")
            await self.sink.action_event(action_id, "failed", str(exc))
            return

        # Once TikTok has confirmed the action, a later database/notification
        # failure must never downgrade it to "failed" or trigger a repost.
        if not result.verified:
            detail = result.detail or "Публикация не подтверждена"
            await self.db.set_action_status(action_id, ActionStatus.MANUAL_REVIEW, detail)
            if action_type == ActionType.COMMENT:
                await self.db.set_video_state(video.id, "attention", detail)
            await self.sink.action_event(action_id, "manual", detail)
            return
        await self.db.set_action_status(action_id, ActionStatus.PUBLISHED)
        if action_type == ActionType.COMMENT:
            await self.db.set_video_state(video.id, "processed")
        await self.db.audit("published", f"action={action_id}; video={video.url}")
        await self.sink.action_event(action_id, "published")

    async def resolve_manual(self, action_id: int, published: bool) -> None:
        action = await self.db.get_action(action_id)
        if not action:
            raise BotError("Действие не найдено")
        if action["status"] == ActionStatus.PUBLISHED.value:
            return
        if action["status"] != ActionStatus.MANUAL_REVIEW.value:
            raise BotError("Это действие не ожидает ручной проверки")
        status = ActionStatus.PUBLISHED if published else ActionStatus.FAILED
        await self.db.set_action_status(action_id, status, "" if published else "Не опубликовано по проверке пользователя")
        if action["action_type"] == ActionType.COMMENT.value:
            await self.db.set_video_state(
                action["video_id"], "processed" if published else "attention",
                "" if published else "Ручная публикация не подтверждена",
            )
        await self.db.audit("manual_resolved", f"action={action_id}; published={published}")

    async def retry_failed(self, action_id: int) -> None:
        action = await self.db.get_action(action_id)
        if not action or action["status"] != ActionStatus.FAILED.value:
            raise BotError("Повтор доступен только для отменённого/неопубликованного действия")
        await self.db.set_action_status(action_id, ActionStatus.PENDING, "Ручной повтор запрошен")
        if action["action_type"] == ActionType.COMMENT.value:
            await self.db.set_video_state(action["video_id"], "queued")
        await self.start_automation()

    async def status(self) -> dict[str, Any]:
        result = await self.db.stats()
        result["enabled"] = await self.is_enabled()
        result["comments_today"] = await self.db.count_actions_today(ActionType.COMMENT)
        result["replies_today"] = await self.db.count_actions_today(ActionType.REPLY)
        result["tags"] = len(await self.get_tags())
        return result
