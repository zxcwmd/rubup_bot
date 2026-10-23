from __future__ import annotations

import asyncio
import json
import logging
import random
import re
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import quote, urlparse

from playwright.async_api import (
    BrowserContext,
    Locator,
    Page,
    Playwright,
    async_playwright,
)

from .config import Settings
from .models import (
    AutomationStopped,
    ChallengeRequired,
    ManualActionRequired,
    PublishResult,
    SessionRequired,
    TikTokComment,
    Video,
)
from .text import normalize_text, stable_comment_id

logger = logging.getLogger(__name__)
VIDEO_PATH_RE = re.compile(r"^/(@[^/]+)/video/(\d+)", re.IGNORECASE)


def canonical_video_url(url: str) -> str | None:
    try:
        parsed = urlparse(url)
    except ValueError:
        return None
    host = (parsed.hostname or "").lower()
    if host not in {"tiktok.com", "www.tiktok.com", "m.tiktok.com"}:
        return None
    match = VIDEO_PATH_RE.match(parsed.path)
    if not match:
        return None
    return f"https://www.tiktok.com/{match.group(1)}/video/{match.group(2)}"


def video_id_from_url(url: str) -> str | None:
    canonical = canonical_video_url(url)
    if not canonical:
        return None
    return VIDEO_PATH_RE.match(urlparse(canonical).path).group(2)  # type: ignore[union-attr]


def _ensure_not_stopped(stop_event: asyncio.Event | None) -> None:
    if stop_event is not None and stop_event.is_set():
        raise AutomationStopped("Остановка получена до нажатия; действие не отправлено")


def _walk_json(value: Any) -> Iterable[dict[str, Any]]:
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from _walk_json(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk_json(child)


def _timestamp(value: Any) -> datetime | None:
    try:
        number = int(value)
        if number > 10_000_000_000:
            number //= 1000
        if number < 1_000_000_000:
            return None
        return datetime.fromtimestamp(number, tz=UTC)
    except (TypeError, ValueError, OSError, OverflowError):
        return None


class TikTokClient:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.selectors: dict[str, list[str]] = json.loads(
            settings.selectors_path.read_text(encoding="utf-8")
        )
        self._playwright: Playwright | None = None
        self._context: BrowserContext | None = None
        self._page: Page | None = None
        self._lock = asyncio.Lock()

    @property
    def page(self) -> Page:
        if self._page is None:
            raise RuntimeError("Browser is not started")
        return self._page

    async def start(self) -> None:
        if self._context is not None:
            return
        self.settings.browser_profile_dir.mkdir(parents=True, exist_ok=True)
        self._playwright = await async_playwright().start()
        try:
            self._context = await self._playwright.chromium.launch_persistent_context(
                user_data_dir=str(self.settings.browser_profile_dir),
                headless=self.settings.headless,
                slow_mo=self.settings.browser_slow_mo_ms,
                viewport={"width": 1440, "height": 1000},
                locale="ru-RU",
            )
            self._context.set_default_timeout(self.settings.navigation_timeout_ms)
            self._page = self._context.pages[0] if self._context.pages else await self._context.new_page()
        except Exception:
            if self._context:
                await self._context.close()
                self._context = None
                self._page = None
            if self._playwright:
                await self._playwright.stop()
                self._playwright = None
            raise

    async def close(self) -> None:
        try:
            if self._context is not None:
                await self._context.close()
        finally:
            self._context = None
            self._page = None
            if self._playwright is not None:
                await self._playwright.stop()
                self._playwright = None

    async def __aenter__(self) -> "TikTokClient":
        await self.start()
        return self

    async def __aexit__(self, *_: object) -> None:
        await self.close()

    async def interactive_login(self) -> bool:
        """Open a normal visible browser; the user completes login/challenges."""
        if self.settings.headless:
            logger.info("Interactive login always uses a visible browser")
        await self.start()
        # A persistent context launched headless would defeat manual login.
        # Settings validation in the CLI requires HEADLESS=false for this command.
        await self.page.goto("https://www.tiktok.com/login", wait_until="domcontentloaded")
        print("\nВойдите в ОДИН аккаунт TikTok в открывшемся окне.")
        print("Капчу при необходимости пройдите вручную. Данные входа в бот не вводятся.")
        await asyncio.to_thread(input, "После успешного входа нажмите Enter здесь... ")
        authenticated, detail = await self.check_session()
        print(detail)
        await self.close()
        return authenticated

    def _all(self, name: str) -> list[str]:
        return self.selectors.get(name, [])

    @staticmethod
    def _css(name: str, values: list[str]) -> str:
        if not values:
            raise RuntimeError(f"No selectors configured for {name}")
        return ", ".join(values)

    async def _first_visible(self, root: Page | Locator, selectors: list[str]) -> Locator | None:
        for selector in selectors:
            try:
                candidate = root.locator(selector).first
                if await candidate.count() and await candidate.is_visible(timeout=700):
                    return candidate
            except Exception:
                continue
        return None

    async def _first_existing(self, root: Page | Locator, selectors: list[str]) -> Locator | None:
        for selector in selectors:
            try:
                candidate = root.locator(selector).first
                if await candidate.count():
                    return candidate
            except Exception:
                continue
        return None

    async def _check_challenge(self) -> None:
        for selector in self._all("challenge"):
            try:
                locator = self.page.locator(selector).first
                if await locator.count() and await locator.is_visible(timeout=400):
                    raise ChallengeRequired(
                        "TikTok показал проверку безопасности. Автоматика остановлена; "
                        "пройдите её вручную в браузерном профиле."
                    )
            except ChallengeRequired:
                raise
            except Exception:
                continue

    async def _goto(self, url: str) -> None:
        await self.page.goto(url, wait_until="domcontentloaded", timeout=self.settings.navigation_timeout_ms)
        await self.page.wait_for_timeout(1200)
        await self._check_challenge()

    async def check_session(self) -> tuple[bool, str]:
        await self.start()
        await self._goto("https://www.tiktok.com/")
        cookies = await self._context.cookies("https://www.tiktok.com/") if self._context else []
        if any(cookie["name"] == "sessionid" and cookie.get("value") for cookie in cookies):
            return True, "Сессия TikTok активна (cookie сессии найдена)."
        profile = await self._first_visible(self.page, self._all("logged_in"))
        if profile:
            return True, "Сессия TikTok активна (элемент профиля найден)."
        return False, "Сессия TikTok не найдена. Выполните python -m rubup_bot login."

    async def ensure_authenticated(self) -> None:
        authenticated, detail = await self.check_session()
        if not authenticated:
            raise SessionRequired(detail)

    async def discover_videos(self, tag: str, limit: int) -> list[str]:
        clean_tag = tag.strip().lstrip("#")
        if not clean_tag:
            return []
        await self.start()
        await self._goto(f"https://www.tiktok.com/tag/{quote(clean_tag, safe='')}")
        found: dict[str, str] = {}
        selector = self._css("video_links", self._all("video_links"))
        stagnant_rounds = 0
        for _ in range(8):
            links = await self.page.eval_on_selector_all(
                selector, "elements => elements.map(element => element.href)"
            )
            previous = len(found)
            for link in links:
                canonical = canonical_video_url(link)
                if canonical:
                    video_id = video_id_from_url(canonical)
                    if video_id:
                        found[video_id] = canonical
                if len(found) >= limit:
                    break
            if len(found) >= limit or stagnant_rounds >= 2:
                break
            stagnant_rounds = stagnant_rounds + 1 if len(found) == previous else 0
            await self.page.mouse.wheel(0, 2200)
            await self.page.wait_for_timeout(1200)
            await self._check_challenge()
        return list(found.values())[:limit]

    async def _video_metadata(self, video_id: str) -> dict[str, Any]:
        scripts = await self.page.eval_on_selector_all(
            "script[type='application/json'], script#__UNIVERSAL_DATA_FOR_REHYDRATION__, script#SIGI_STATE",
            "elements => elements.map(element => element.textContent || '').filter(Boolean)",
        )
        candidates: list[dict[str, Any]] = []
        for raw in scripts:
            try:
                payload = json.loads(raw)
            except (json.JSONDecodeError, TypeError):
                continue
            for item in _walk_json(payload):
                item_id = item.get("id", item.get("aweme_id", item.get("awemeId")))
                if str(item_id) == video_id and any(
                    key in item for key in ("createTime", "create_time", "desc", "author")
                ):
                    candidates.append(item)
        if candidates:
            return max(candidates, key=lambda item: len(item))
        html = await self.page.content()
        for match in re.finditer(re.escape(video_id), html):
            window = html[max(0, match.start() - 5000):match.end() + 15000]
            created = re.search(r'["\\]createTime["\\]\s*:\s*["\\]?(\d{10,13})', window)
            if created:
                return {"id": video_id, "createTime": created.group(1)}
        return {"id": video_id}

    async def get_video(self, url: str, source_tag: str = "") -> Video:
        await self.start()
        if not canonical_video_url(url):
            # Short TikTok links are resolved only by ordinary browser navigation.
            host = (urlparse(url).hostname or "").lower()
            if not host.endswith("tiktok.com"):
                raise ValueError("Only TikTok video URLs are accepted")
        await self._goto(url)
        canonical = canonical_video_url(self.page.url) or canonical_video_url(url)
        if not canonical:
            raise ValueError(f"Could not resolve TikTok video URL: {url}")
        video_id = video_id_from_url(canonical)
        assert video_id is not None
        metadata = await self._video_metadata(video_id)
        description = str(metadata.get("desc") or metadata.get("description") or "")
        if not description:
            for meta_selector in ("meta[property='og:description']", "meta[name='description']"):
                meta = self.page.locator(meta_selector).first
                if await meta.count():
                    description = (await meta.get_attribute("content")) or ""
                    if description:
                        break
        author_value = metadata.get("author", "")
        if isinstance(author_value, dict):
            author = str(author_value.get("uniqueId") or author_value.get("unique_id")
                         or author_value.get("nickname") or "")
        elif isinstance(author_value, str):
            author = author_value
        else:
            author = ""
        if not author:
            author = VIDEO_PATH_RE.match(urlparse(canonical).path).group(1).lstrip("@")  # type: ignore[union-attr]
        published = _timestamp(metadata.get("createTime") or metadata.get("create_time"))
        tags = {match.lower() for match in re.findall(r"#([\w_]+)", description, re.UNICODE)}
        if source_tag:
            tags.add(source_tag.strip().lstrip("#").lower())
        return Video(
            id=video_id,
            url=canonical,
            author=author,
            description=description.strip(),
            published_at=published,
            matched_tags=tuple(sorted(tags)),
        )

    async def get_comments(self, video_url: str, limit: int = 80) -> list[TikTokComment]:
        await self.start()
        canonical = canonical_video_url(video_url)
        if not canonical:
            raise ValueError("A canonical TikTok video URL is required")
        if canonical_video_url(self.page.url) != canonical:
            await self._goto(canonical)
        video_id = video_id_from_url(canonical)
        assert video_id is not None
        for _ in range(2):
            await self.page.mouse.wheel(0, 1800)
            await self.page.wait_for_timeout(700)
        selector = self._css("comment_items", self._all("comment_items"))
        raw_comments = await self.page.eval_on_selector_all(
            selector,
            r"""
            (elements, args) => elements.slice(0, args.limit).map((element) => {
              const pick = (selectors) => {
                for (const selector of selectors) {
                  const found = element.querySelector(selector);
                  if (found && (found.textContent || '').trim()) return found;
                }
                return null;
              };
              const authorNode = pick(args.authors);
              const textNode = pick(args.texts);
              const authorHref = authorNode && authorNode.href ? authorNode.href : '';
              let author = authorNode ? (authorNode.textContent || '').trim() : '';
              if (!author && authorHref) {
                const match = authorHref.match(/\/@([^/?#]+)/);
                author = match ? match[1] : '';
              }
              let externalId = element.getAttribute('data-comment-id') ||
                element.getAttribute('data-cid') || element.getAttribute('data-id') || '';
              if (!externalId) {
                const marker = (element.id || '') + ' ' + (element.getAttribute('data-e2e') || '');
                const match = marker.match(/(?:comment[-_]?)(\d{6,})/i);
                externalId = match ? match[1] : '';
              }
              return {
                externalId,
                author: author.replace(/^@/, ''),
                text: textNode ? (textNode.textContent || '').trim() : '',
                hasImage: args.images.some(selector => !!element.querySelector(selector))
              };
            }).filter(item => item.text)
            """,
            {
                "limit": limit,
                "authors": self._all("comment_author"),
                "texts": self._all("comment_text"),
                "images": self._all("published_comment_image"),
            },
        )
        result: list[TikTokComment] = []
        seen: set[str] = set()
        for raw in raw_comments:
            author = str(raw["author"])
            text = str(raw["text"])
            comment_id = str(raw["externalId"] or stable_comment_id(video_id, author, text))
            if comment_id in seen:
                continue
            seen.add(comment_id)
            result.append(TikTokComment(
                id=comment_id,
                video_id=video_id,
                author=author,
                text=text,
                has_image=bool(raw["hasImage"]),
                is_own=bool(self.settings.tiktok_username
                            and author.lower().lstrip("@") == self.settings.tiktok_username),
            ))
        return result

    async def _find_comment_item(self, target: TikTokComment) -> Locator | None:
        selector = self._css("comment_items", self._all("comment_items"))
        items = self.page.locator(selector)
        count = min(await items.count(), 120)
        for index in range(count):
            item = items.nth(index)
            try:
                body = await item.inner_text(timeout=1000)
                if target.text and target.text in body:
                    if not target.author or target.author.lower() in body.lower():
                        return item
            except Exception:
                continue
        return None

    async def _comment_is_liked(self, item: Locator, button: Locator) -> bool:
        for candidate in (button, button.locator("xpath=..")):
            try:
                if (await candidate.get_attribute("aria-pressed")) == "true":
                    return True
                label = (await candidate.get_attribute("aria-label")) or ""
                if "unlike" in label.lower() or "не нравится" in label.lower():
                    return True
            except Exception:
                continue
        for selector in self._all("comment_liked_state"):
            try:
                if await item.locator(selector).count():
                    return True
            except Exception:
                continue
        return False

    async def like_own_comment(
        self, video_url: str, comment_text: str, *, stop_event: asyncio.Event | None = None
    ) -> bool:
        """Like exactly one verified own comment and confirm the resulting UI state."""
        _ensure_not_stopped(stop_event)
        canonical = canonical_video_url(video_url)
        if not canonical:
            raise ValueError("A canonical TikTok video URL is required")
        if not self.settings.tiktok_username:
            return False
        comments = await self.get_comments(canonical, limit=120)
        expected = normalize_text(comment_text)
        matches = [comment for comment in comments if normalize_text(comment.text) == expected]
        own_matches = [comment for comment in matches if comment.is_own]
        matches = own_matches
        if not matches:
            return False
        item = await self._find_comment_item(matches[-1])
        if not item:
            return False
        button = await self._first_visible(item, self._all("comment_like_button"))
        if not button:
            return False
        if await self._comment_is_liked(item, button):
            return True
        _ensure_not_stopped(stop_event)
        await button.click()
        await self.page.wait_for_timeout(700)
        if await self._comment_is_liked(item, button):
            return True
        # A reload distinguishes a slow UI update from an unconfirmed click.
        await self._goto(canonical)
        comments = await self.get_comments(canonical, limit=120)
        refreshed = [comment for comment in comments if normalize_text(comment.text) == expected]
        refreshed = [comment for comment in refreshed if comment.is_own]
        if not refreshed:
            return False
        item = await self._find_comment_item(refreshed[-1])
        if not item:
            return False
        button = await self._first_visible(item, self._all("comment_like_button"))
        return bool(button and await self._comment_is_liked(item, button))

    async def post_comment(
        self,
        video_url: str,
        text: str,
        *,
        image_path: Path | None = None,
        reply_to: TikTokComment | None = None,
        stop_event: asyncio.Event | None = None,
    ) -> PublishResult:
        """Submit once. An uncertain result is never retried automatically."""
        _ensure_not_stopped(stop_event)
        await self.start()
        canonical = canonical_video_url(video_url)
        if not canonical:
            raise ValueError("A canonical TikTok video URL is required")
        if canonical_video_url(self.page.url) != canonical:
            await self._goto(canonical)
        await self._check_challenge()
        if reply_to:
            item = await self._find_comment_item(reply_to)
            if not item:
                raise ManualActionRequired("Не найден комментарий, на который нужно ответить")
            reply_button = await self._first_visible(item, self._all("comment_reply_button"))
            if not reply_button:
                raise ManualActionRequired("Кнопка ответа не найдена в текущем интерфейсе TikTok")
            _ensure_not_stopped(stop_event)
            await reply_button.click()
        comment_input = await self._first_visible(self.page, self._all("comment_input"))
        if not comment_input:
            raise ManualActionRequired("Поле комментария не найдено в текущем интерфейсе TikTok")
        await comment_input.click()
        if image_path is not None:
            if not image_path.is_file():
                raise ManualActionRequired(f"Файл изображения не найден: {image_path}")
            image_input = await self._first_existing(self.page, self._all("image_input"))
            if not image_input:
                raise ManualActionRequired(
                    "В веб-интерфейсе этого аккаунта не найдена загрузка фото в комментарий"
                )
            _ensure_not_stopped(stop_event)
            await image_input.set_input_files(str(image_path))
            await self.page.wait_for_timeout(1400)
            preview = await self._first_visible(self.page, self._all("image_preview"))
            if not preview:
                raise ManualActionRequired(
                    "Фото не подтверждено предпросмотром; комментарий не отправлен"
                )
        await comment_input.fill(text)
        await self.page.wait_for_timeout(500)
        submit = await self._first_visible(self.page, self._all("submit"))
        if not submit:
            raise ManualActionRequired("Кнопка публикации комментария не найдена")
        try:
            if not await submit.is_enabled():
                await self.page.wait_for_timeout(1800)
            if not await submit.is_enabled():
                raise ManualActionRequired("Кнопка публикации осталась неактивной")
        except AttributeError:
            pass
        _ensure_not_stopped(stop_event)
        try:
            await submit.click()
        except Exception as exc:
            raise ManualActionRequired(
                f"Не удалось подтвердить нажатие кнопки публикации: {exc}",
                may_have_published=True,
            ) from exc
        # From this point retries are unsafe: TikTok may have accepted the action.
        try:
            await self.page.wait_for_timeout(2800)
        except Exception as exc:
            raise ManualActionRequired(
                f"После нажатия связь со страницей прервалась: {exc}",
                may_have_published=True,
            ) from exc
        try:
            await self._check_challenge()
        except ChallengeRequired as exc:
            raise ManualActionRequired(str(exc), may_have_published=True) from exc
        try:
            normalized_expected = normalize_text(text)
            selector = self._css("comment_items", self._all("comment_items"))
            items = self.page.locator(selector)
            for index in range(min(await items.count(), 120)):
                item = items.nth(index)
                body = await item.inner_text(timeout=1000)
                if not normalized_expected or normalized_expected not in normalize_text(body):
                    continue
                if image_path is not None:
                    image = await self._first_existing(item, self._all("published_comment_image"))
                    if not image:
                        continue
                return PublishResult(submitted=True, verified=True)
        except Exception as exc:
            raise ManualActionRequired(
                f"После нажатия не удалось проверить результат: {exc}",
                may_have_published=True,
            ) from exc
        raise ManualActionRequired(
            "Нажатие выполнено, но комментарий не подтверждён в интерфейсе. "
            "Проверьте его вручную, чтобы не создать дубль.",
            may_have_published=True,
        )

    async def delay_between_actions(self, stop_event: asyncio.Event | None = None) -> None:
        low, high = self.settings.action_delay_range
        delay = random.uniform(low, high)
        if stop_event is None:
            await asyncio.sleep(delay)
            return
        try:
            await asyncio.wait_for(stop_event.wait(), timeout=delay)
        except TimeoutError:
            pass
