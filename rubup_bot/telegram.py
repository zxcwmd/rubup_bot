from __future__ import annotations

import asyncio
import logging
import re
from collections.abc import Awaitable, Callable
from typing import Any

from aiogram import BaseMiddleware, Bot, Dispatcher, F, Router
from aiogram.filters import Command, CommandStart
from aiogram.types import (
    CallbackQuery,
    InlineKeyboardButton,
    InlineKeyboardMarkup,
    KeyboardButton,
    LinkPreviewOptions,
    Message,
    ReplyKeyboardMarkup,
)

from .config import Settings
from .db import Database
from .models import BotError
from .service import AutomationService

logger = logging.getLogger(__name__)

BUTTONS = {
    "start": "▶️ Авто",
    "pause": "⏸ Пауза",
    "status": "📊 Статус",
    "discover": "🔎 Найти видео",
    "images": "🖼 Картинки",
    "history": "📝 История",
    "report": "📤 Отправить отчет",
    "reports": "🗂 Отчеты",
    "session": "✅ Мой TikTok",
    "tags": "🏷 Теги",
    "help": "ℹ️ Помощь",
}

HELP = """RubUP Comment Bot

Автоматизация: ▶️ Авто — запуск, ⏸ Пауза — остановка после текущей операции.
Перед запуском: выполните `python -m rubup_bot login` на компьютере, добавьте JPG/PNG/WEBP в папку images и проверьте ✅ Мой TikTok.

Команды:
/add ССЫЛКА — добавить конкретное видео в очередь
/set_tags тег1 тег2 — заменить список тегов
/history — последние действия
/report — отправить только ссылки на опубликованные действия
/reports — история отчётов
/retry ID — повторить только действие, которое точно НЕ опубликовано

Ответы создаются только под чужими комментариями, распознанными по RUBUP_COMMENT_MARKERS. Произвольный комментарий с фото автоматически RubUP-комментарием не считается.
"""

STATUS_LABELS = {
    "pending": "⏳ ожидает",
    "publishing": "📤 публикуется",
    "published": "✅ опубликовано",
    "manual_review": "⚠️ ручная проверка",
    "failed": "❌ не опубликовано",
    "skipped": "⏭ пропущено",
}


class AdminMiddleware(BaseMiddleware):
    def __init__(self, admin_ids: frozenset[int]) -> None:
        self.admin_ids = admin_ids

    async def __call__(
        self,
        handler: Callable[[Any, dict[str, Any]], Awaitable[Any]],
        event: Any,
        data: dict[str, Any],
    ) -> Any:
        user = getattr(event, "from_user", None)
        if not user or user.id not in self.admin_ids:
            if isinstance(event, CallbackQuery):
                await event.answer("Нет доступа", show_alert=True)
            elif isinstance(event, Message):
                await event.answer("Нет доступа. Ваш Telegram ID не указан в ADMIN_USER_IDS.")
            return None
        return await handler(event, data)


def main_keyboard() -> ReplyKeyboardMarkup:
    return ReplyKeyboardMarkup(
        keyboard=[
            [KeyboardButton(text=BUTTONS["start"]), KeyboardButton(text=BUTTONS["pause"])],
            [KeyboardButton(text=BUTTONS["status"]), KeyboardButton(text=BUTTONS["discover"])],
            [KeyboardButton(text=BUTTONS["images"]), KeyboardButton(text=BUTTONS["history"])],
            [KeyboardButton(text=BUTTONS["report"]), KeyboardButton(text=BUTTONS["reports"])],
            [KeyboardButton(text=BUTTONS["session"]), KeyboardButton(text=BUTTONS["tags"])],
            [KeyboardButton(text=BUTTONS["help"])],
        ],
        resize_keyboard=True,
    )


def manual_keyboard(action_id: int) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[[
        InlineKeyboardButton(text="✅ Опубликовано", callback_data=f"manual:{action_id}:yes"),
        InlineKeyboardButton(text="❌ Не опубликовано", callback_data=f"manual:{action_id}:no"),
    ]])


class TelegramUI:
    def __init__(self, settings: Settings, db: Database, service: AutomationService) -> None:
        self.settings = settings
        self.db = db
        self.service = service
        self.bot = Bot(token=settings.telegram_bot_token)
        self.dp = Dispatcher()
        self.router = Router()
        self.dp.include_router(self.router)
        middleware = AdminMiddleware(settings.admin_user_ids)
        self.router.message.middleware(middleware)
        self.router.callback_query.middleware(middleware)
        self._tasks: set[asyncio.Task[Any]] = set()
        self._register_handlers()

    def _background(self, coroutine: Awaitable[Any]) -> None:
        async def guarded() -> None:
            try:
                await coroutine
            except Exception as exc:
                logger.exception("Telegram-triggered operation failed")
                await self.info(f"Ошибка: {type(exc).__name__}: {exc}")

        task = asyncio.create_task(guarded())
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def info(self, text: str) -> None:
        for admin_id in self.settings.admin_user_ids:
            try:
                await self.bot.send_message(admin_id, text[:4000])
            except Exception as exc:
                logger.warning("Cannot notify admin %s: %s", admin_id, exc)

    async def action_event(self, action_id: int, event: str, detail: str = "") -> None:
        action = await self.db.get_action(action_id)
        if not action:
            return
        kind = "Комментарий" if action["action_type"] == "comment" else "Ответ"
        if event == "published":
            text = f"✅ {kind} #{action_id} опубликован\n{action['video_url']}"
            keyboard = None
        elif event == "manual":
            text = (
                f"⚠️ {kind} #{action_id}: нужна ручная проверка\n"
                f"{action['video_url']}\n\nПричина: {detail}\n\n"
                f"Текст: {action['text']}\n"
            )
            if action.get("image_path"):
                text += f"Картинка: {action['image_path']}\n"
            text += "\nПроверьте видео и выберите результат. Не публикуйте повторно, если комментарий уже есть."
            keyboard = manual_keyboard(action_id)
        elif event == "pending":
            text = f"⏸ {kind} #{action_id} сохранён для безопасного продолжения: {detail}"
            keyboard = None
        else:
            text = (
                f"❌ {kind} #{action_id} не выполнен\n{action['video_url']}\n{detail}\n\n"
                f"Если вы проверили и публикации точно нет: /retry {action_id}"
            )
            keyboard = None
        for admin_id in self.settings.admin_user_ids:
            try:
                await self.bot.send_message(admin_id, text[:4000], reply_markup=keyboard)
            except Exception as exc:
                logger.warning("Cannot send action event: %s", exc)

    async def _status_text(self) -> str:
        status = await self.service.status()
        return (
            f"Автоматика: {'▶️ включена' if status['enabled'] else '⏸ на паузе'}\n"
            f"Видео: {status['videos']} (в очереди {status['queued']})\n"
            f"Опубликовано: {status['published']}; ручная проверка: {status['manual']}; ошибки: {status['failed']}\n"
            f"Сегодня: комментарии {status['comments_today']}/{self.settings.max_comments_per_day}, "
            f"ответы {status['replies_today']}/{self.settings.max_replies_per_day}\n"
            f"Картинки: новые {status['images_available']}, зарезервированы {status['images_reserved']}, "
            f"использованы {status['images_used']}\n"
            f"Не в отчёте: {status['unreported']}; активных тегов: {status['tags']}"
        )

    async def _send_history(self, message: Message) -> None:
        rows = await self.db.history(10)
        if not rows:
            await message.answer("История действий пока пуста.")
            return
        chunks: list[str] = []
        for row in rows:
            kind = "комментарий" if row["action_type"] == "comment" else "ответ"
            text = row["text"]
            if len(text) > 90:
                text = text[:87] + "..."
            chunks.append(
                f"#{row['id']} {STATUS_LABELS.get(row['status'], row['status'])} {kind}\n"
                f"{row['video_url']}\n{text}"
            )
        await message.answer("\n\n".join(chunks)[:4000])

    async def _send_report(self, message: Message) -> None:
        report_id = await self.db.create_report()
        if report_id is None:
            await message.answer("Нет подтверждённых публикаций для нового отчёта.")
            return
        links = await self.db.report_links(report_id)
        if not links:
            await self.db.mark_report_sent(report_id)
            await message.answer("В отчёте нет ссылок.")
            return
        chunks: list[str] = []
        current = ""
        for link in links:
            candidate = f"{current}\n{link}" if current else link
            if len(candidate) > 3500 and current:
                chunks.append(current)
                current = link
            else:
                current = candidate
        if current:
            chunks.append(current)
        # Report messages intentionally contain links only.
        for chunk in chunks:
            await message.answer(
                chunk,
                link_preview_options=LinkPreviewOptions(is_disabled=True),
            )
        await self.db.mark_report_sent(report_id)

    def _register_handlers(self) -> None:
        router = self.router

        @router.message(CommandStart())
        @router.message(Command("help"))
        @router.message(F.text == BUTTONS["help"])
        async def start(message: Message) -> None:
            await message.answer(HELP, reply_markup=main_keyboard())

        @router.message(Command("auto"))
        @router.message(F.text == BUTTONS["start"])
        async def enable(message: Message) -> None:
            await self.service.start_automation()
            await message.answer("Автоматика включена. Первый цикл начнётся сейчас.", reply_markup=main_keyboard())

        @router.message(Command("pause"))
        @router.message(F.text == BUTTONS["pause"])
        async def pause(message: Message) -> None:
            await self.service.pause_automation()
            await message.answer("Автоматика поставлена на паузу после текущей операции.")

        @router.message(Command("status"))
        @router.message(F.text == BUTTONS["status"])
        async def status(message: Message) -> None:
            await message.answer(await self._status_text())

        @router.message(Command("discover"))
        @router.message(F.text == BUTTONS["discover"])
        async def discover(message: Message) -> None:
            await message.answer("Поиск запущен. Это может занять несколько минут.")

            async def operation() -> None:
                result = await self.service.discover_once()
                await self.info(
                    f"Поиск завершён: новых {result['new_videos']}, "
                    f"подходят {result['eligible']}, пропущено {result['skipped']}."
                )

            self._background(operation())

        @router.message(Command("images"))
        @router.message(F.text == BUTTONS["images"])
        async def images(message: Message) -> None:
            await message.answer("Сканирую папку images…")

            async def operation() -> None:
                result = await self.service.scan_images()
                await self.info(
                    f"Картинки: добавлено {result.added}, дубликаты {result.duplicates}, "
                    f"повреждены/не поддерживаются {result.invalid}.\n{await self._status_text()}"
                )

            self._background(operation())

        @router.message(Command("history"))
        @router.message(F.text == BUTTONS["history"])
        async def history(message: Message) -> None:
            await self._send_history(message)

        @router.message(Command("report"))
        @router.message(F.text == BUTTONS["report"])
        async def report(message: Message) -> None:
            await self._send_report(message)

        @router.message(Command("reports"))
        @router.message(F.text == BUTTONS["reports"])
        async def reports(message: Message) -> None:
            rows = await self.db.reports(10)
            if not rows:
                await message.answer("Отчётов ещё не было.")
                return
            lines = [
                f"#{row['id']} — {row['status']}; ссылок {row['action_count']}; "
                f"создан {row['created_at']}; отправлен {row['sent_at'] or '—'}"
                for row in rows
            ]
            await message.answer("\n".join(lines))

        @router.message(Command("session"))
        @router.message(F.text == BUTTONS["session"])
        async def session(message: Message) -> None:
            await message.answer("Проверяю сохранённую сессию TikTok…")

            async def operation() -> None:
                authenticated, detail = await self.service.check_session()
                await self.info(("✅ " if authenticated else "⚠️ ") + detail)

            self._background(operation())

        @router.message(Command("tags"))
        @router.message(F.text == BUTTONS["tags"])
        async def tags(message: Message) -> None:
            values = await self.service.get_tags()
            await message.answer(
                "Активные теги:\n" + " ".join(f"#{tag}" for tag in values) +
                "\n\nЗаменить: /set_tags cs2 caser нарезка"
            )

        @router.message(Command("set_tags"))
        async def set_tags(message: Message) -> None:
            raw = (message.text or "").partition(" ")[2].replace(",", " ").split()
            try:
                values = await self.service.set_tags(raw)
            except ValueError as exc:
                await message.answer(str(exc))
                return
            await message.answer(f"Сохранено тегов: {len(values)}")

        @router.message(Command("retry"))
        async def retry(message: Message) -> None:
            try:
                action_id = int((message.text or "").split()[1])
                await self.service.retry_failed(action_id)
            except (IndexError, ValueError):
                await message.answer("Использование: /retry ID")
                return
            except BotError as exc:
                await message.answer(str(exc))
                return
            await message.answer(
                f"Действие #{action_id} поставлено в очередь. Используйте повтор только если публикации точно нет."
            )

        @router.message(Command("add"))
        async def add_command(message: Message) -> None:
            url = (message.text or "").partition(" ")[2].strip()
            if not url:
                await message.answer("Использование: /add https://www.tiktok.com/@author/video/…")
                return
            await self._add_video(message, url)

        @router.message(F.text.regexp(r"https?://[^\s]*tiktok\.com/[^\s]+"))
        async def add_link(message: Message) -> None:
            match = re.search(r"https?://[^\s]*tiktok\.com/[^\s]+", message.text or "")
            if match:
                await self._add_video(message, match.group(0).rstrip("),.;!"))

        @router.callback_query(F.data.regexp(r"^manual:\d+:(yes|no)$"))
        async def resolve_manual(callback: CallbackQuery) -> None:
            _, raw_id, answer = (callback.data or "").split(":")
            try:
                await self.service.resolve_manual(int(raw_id), answer == "yes")
            except BotError as exc:
                await callback.answer(str(exc), show_alert=True)
                return
            await callback.answer("Сохранено")
            if isinstance(callback.message, Message):
                suffix = "подтверждено" if answer == "yes" else "отмечено как неопубликованное"
                await callback.message.answer(f"Действие #{raw_id}: {suffix}.")

        @router.message()
        async def unknown(message: Message) -> None:
            await message.answer("Не понял команду. Нажмите ℹ️ Помощь.", reply_markup=main_keyboard())

    async def _add_video(self, message: Message, url: str) -> None:
        await message.answer("Проверяю видео и его дату…")

        async def operation() -> None:
            try:
                video, eligible, reason = await self.service.add_video(url)
                if eligible:
                    await self.info(f"Видео добавлено в очередь: {video.url}")
                else:
                    await self.info(f"Видео пропущено: {reason}\n{video.url}")
            except ValueError as exc:
                await self.info(str(exc))

        self._background(operation())

    async def run(self) -> None:
        self.service.sink = self
        self.service.start_scheduler()
        await self.dp.start_polling(self.bot)

    async def close(self) -> None:
        for task in list(self._tasks):
            task.cancel()
        if self._tasks:
            await asyncio.gather(*self._tasks, return_exceptions=True)
        await self.service.close()
        await self.bot.session.close()
