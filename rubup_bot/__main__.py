from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
from dataclasses import replace
from pathlib import Path

from .config import Settings
from .db import Database
from .images import ImageLibrary
from .service import AutomationService
from .tiktok import TikTokClient


def _logging(settings: Settings) -> None:
    logging.basicConfig(
        level=getattr(logging, settings.log_level, logging.INFO),
        format="%(asctime)s | %(levelname)s | %(name)s | %(message)s",
    )


async def _init(settings: Settings) -> int:
    settings.ensure_directories()
    db = Database(settings.database_path)
    await db.open()
    await db.close()
    print("Папки и база данных готовы.")
    print(f"Изображения складывайте сюда: {settings.image_dir}")
    print("Следующий шаг: настройте .env и выполните python -m rubup_bot login")
    return 0


async def _login(settings: Settings) -> int:
    if not settings.selectors_path.exists():
        print(f"Не найден {settings.selectors_path}", file=sys.stderr)
        return 1
    # Login must always be visible, regardless of the automation setting.
    visible_settings = replace(settings, headless=False)
    client = TikTokClient(visible_settings)
    try:
        authenticated = await client.interactive_login()
        return 0 if authenticated else 2
    finally:
        await client.close()


async def _run(settings: Settings) -> int:
    _logging(settings)
    settings.ensure_directories()
    db = Database(settings.database_path)
    await db.open()
    service = AutomationService(settings, db)
    ui = None
    try:
        await service.initialize()
        from .telegram import TelegramUI

        ui = TelegramUI(settings, db, service)
        print("Telegram-бот запущен. Нажмите Ctrl+C для остановки.")
        await ui.run()
        return 0
    finally:
        if ui:
            await ui.close()
        else:
            await service.close()
        await db.close()


async def _import_images(settings: Settings) -> int:
    settings.ensure_directories()
    db = Database(settings.database_path)
    await db.open()
    try:
        stats = await ImageLibrary(settings.image_dir, settings.image_library_dir, db).scan()
        print(f"Добавлено: {stats.added}; дубликаты: {stats.duplicates}; ошибки файлов: {stats.invalid}")
        return 0 if not stats.invalid else 2
    finally:
        await db.close()


async def _discover(settings: Settings) -> int:
    _logging(settings)
    settings.ensure_directories()
    db = Database(settings.database_path)
    await db.open()
    service = AutomationService(settings, db)
    try:
        await service.initialize()
        result = await service.discover_once()
        print(json.dumps(result, ensure_ascii=False))
        return 0
    finally:
        await service.close()
        await db.close()


async def _doctor(settings: Settings) -> int:
    errors: list[str] = []
    print(f"Python: {sys.version.split()[0]}")
    print(f"Папка проекта: {settings.base_dir}")
    if not settings.telegram_bot_token or "replace_me" in settings.telegram_bot_token:
        errors.append("TELEGRAM_BOT_TOKEN не настроен")
    else:
        print("OK: TELEGRAM_BOT_TOKEN заполнен (значение не выводится)")
    if not settings.admin_user_ids:
        errors.append("ADMIN_USER_IDS не настроен")
    else:
        print(f"OK: администраторов Telegram: {len(settings.admin_user_ids)}")
    if not settings.tiktok_username:
        errors.append("TIKTOK_USERNAME не настроен — защита от ответа самому себе неполная")
    else:
        print(f"OK: один TikTok-аккаунт: @{settings.tiktok_username}")
    settings.ensure_directories()
    for path in (settings.data_dir, settings.image_dir, settings.browser_profile_dir):
        if not path.is_dir():
            errors.append(f"Нет папки {path}")
    for path in (settings.selectors_path, settings.template_dir / "comments.txt", settings.template_dir / "replies.txt"):
        if not path.is_file():
            errors.append(f"Нет файла {path}")
        else:
            print(f"OK: {path.name}")
    try:
        json.loads(settings.selectors_path.read_text(encoding="utf-8"))
    except Exception as exc:
        errors.append(f"selectors.json: {exc}")
    db = Database(settings.database_path)
    await db.open()
    try:
        stats = await ImageLibrary(settings.image_dir, settings.image_library_dir, db).scan()
        counts = await db.image_counts()
        print(f"Картинки: доступно {counts['available']}, использовано {counts['used']}, "
              f"новых при проверке {stats.added}, дубликатов {stats.duplicates}")
        if stats.invalid:
            errors.append(f"Поврежденных/неподдерживаемых изображений: {stats.invalid}")
    finally:
        await db.close()
    try:
        from playwright.async_api import async_playwright

        playwright = await async_playwright().start()
        executable = Path(playwright.chromium.executable_path)
        await playwright.stop()
        if not executable.exists():
            errors.append("Chromium не установлен: выполните playwright install chromium")
        else:
            print(f"OK: Chromium {executable}")
    except Exception as exc:
        errors.append(f"Playwright: {exc}")
    if errors:
        print("\nНужно исправить:")
        for error in errors:
            print(f"- {error}")
        return 1
    print("\nДиагностика пройдена. Сессию TikTok проверьте кнопкой «Мой TikTok».")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="RubUP Telegram-controlled TikTok workflow")
    parser.add_argument(
        "command", nargs="?", default="run",
        choices=("run", "init", "login", "doctor", "import-images", "discover"),
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        settings = Settings.load(require_token=args.command == "run")
        if args.command == "init":
            return asyncio.run(_init(settings))
        if args.command == "login":
            return asyncio.run(_login(settings))
        if args.command == "doctor":
            return asyncio.run(_doctor(settings))
        if args.command == "import-images":
            return asyncio.run(_import_images(settings))
        if args.command == "discover":
            return asyncio.run(_discover(settings))
        return asyncio.run(_run(settings))
    except KeyboardInterrupt:
        print("\nОстановлено пользователем.")
        return 130
    except Exception as exc:
        print(f"Ошибка: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
