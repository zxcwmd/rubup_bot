from __future__ import annotations

import os
import re
from dataclasses import dataclass
from datetime import date
from pathlib import Path

from dotenv import load_dotenv

DEFAULT_TAGS = (
    "upgrader", "cs2", "caser", "rubup", "кейсер", "апгрейдер",
    "тгподарки", "подаркитг", "нфт", "nft", "тг", "телеграм", "telegram",
    "меллстрой", "меллстройнарезки", "mellstroy", "мелстрой", "dota2",
    "twitch", "leva2k", "buster", "лева2к", "бустер", "твич", "gensyxa",
    "генсуха", "нарезка",
)


def _bool(value: str | bool | None, default: bool = False) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return value.strip().lower() in {"1", "true", "yes", "on", "да"}


def _int(value: str | int | None, default: int, minimum: int = 0) -> int:
    result = int(value) if value not in (None, "") else default
    if result < minimum:
        raise ValueError(f"Value must be >= {minimum}, got {result}")
    return result


def _float(value: str | float | None, default: float) -> float:
    result = float(value) if value not in (None, "") else default
    if not 0 <= result <= 1:
        raise ValueError("Similarity thresholds must be between 0 and 1")
    return result


def _path(base: Path, value: str | None, default: str) -> Path:
    path = Path(value or default).expanduser()
    return path if path.is_absolute() else base / path


@dataclass(frozen=True, slots=True)
class Settings:
    base_dir: Path
    telegram_bot_token: str
    admin_user_ids: frozenset[int]
    tiktok_username: str
    headless: bool
    browser_slow_mo_ms: int
    navigation_timeout_ms: int
    auto_start: bool
    scan_interval_minutes: int
    max_tags_per_cycle: int
    discovery_limit_per_tag: int
    max_video_details_per_cycle: int
    max_actions_per_cycle: int
    max_comments_per_day: int
    max_replies_per_day: int
    max_replies_per_video: int
    action_delay_seconds: int
    action_jitter_seconds: int
    min_video_date: date
    ad_disclosure: str
    similarity_threshold: float
    target_similarity_threshold: float
    allow_gift_claims: bool
    rubup_comment_markers: str
    data_dir: Path
    image_dir: Path
    template_dir: Path
    selectors_path: Path
    browser_profile_dir: Path
    links_file: Path
    log_level: str
    hashtags: tuple[str, ...] = DEFAULT_TAGS

    @property
    def database_path(self) -> Path:
        return self.data_dir / "rubup.sqlite3"

    @property
    def image_library_dir(self) -> Path:
        return self.data_dir / "image-library"

    @property
    def action_delay_range(self) -> tuple[int, int]:
        return self.action_delay_seconds, self.action_delay_seconds + self.action_jitter_seconds

    def ensure_directories(self) -> None:
        for directory in (
            self.data_dir,
            self.image_dir,
            self.image_library_dir,
            self.browser_profile_dir,
            self.links_file.parent,
            self.template_dir,
        ):
            directory.mkdir(parents=True, exist_ok=True)

    @classmethod
    def load(cls, env_file: str | Path | None = None, *, require_token: bool = True) -> "Settings":
        base_dir = Path.cwd().resolve()
        load_dotenv(env_file or base_dir / ".env", override=False)
        token = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
        if require_token and (not token or "replace_me" in token):
            raise ValueError("Set TELEGRAM_BOT_TOKEN in .env (get it from @BotFather)")
        admin_raw = os.getenv("ADMIN_USER_IDS", "")
        admins = frozenset(int(item.strip()) for item in admin_raw.split(",") if item.strip())
        if require_token and not admins:
            raise ValueError("Set at least one numeric Telegram ID in ADMIN_USER_IDS")
        disclosure = os.getenv("AD_DISCLOSURE", "Реклама").strip()
        if not disclosure:
            raise ValueError("AD_DISCLOSURE cannot be empty: paid comments must be identifiable")
        min_date = date.fromisoformat(os.getenv("MIN_VIDEO_DATE", "2026-05-01"))
        markers = os.getenv("RUBUP_COMMENT_MARKERS", r"rubup|rub\s*up").strip()
        if markers:
            re.compile(markers, re.IGNORECASE)
        return cls(
            base_dir=base_dir,
            telegram_bot_token=token,
            admin_user_ids=admins,
            tiktok_username=os.getenv("TIKTOK_USERNAME", "").strip().lstrip("@").lower(),
            headless=_bool(os.getenv("HEADLESS"), False),
            browser_slow_mo_ms=_int(os.getenv("BROWSER_SLOW_MO_MS"), 80),
            navigation_timeout_ms=_int(os.getenv("NAVIGATION_TIMEOUT_MS"), 45000, 1000),
            auto_start=_bool(os.getenv("AUTO_START"), False),
            scan_interval_minutes=_int(os.getenv("SCAN_INTERVAL_MINUTES"), 45, 1),
            max_tags_per_cycle=_int(os.getenv("MAX_TAGS_PER_CYCLE"), 4, 1),
            discovery_limit_per_tag=_int(os.getenv("DISCOVERY_LIMIT_PER_TAG"), 8, 1),
            max_video_details_per_cycle=_int(os.getenv("MAX_VIDEO_DETAILS_PER_CYCLE"), 20, 1),
            max_actions_per_cycle=_int(os.getenv("MAX_ACTIONS_PER_CYCLE"), 4, 1),
            max_comments_per_day=_int(os.getenv("MAX_COMMENTS_PER_DAY"), 10),
            max_replies_per_day=_int(os.getenv("MAX_REPLIES_PER_DAY"), 20),
            max_replies_per_video=_int(os.getenv("MAX_REPLIES_PER_VIDEO"), 2),
            action_delay_seconds=_int(os.getenv("ACTION_DELAY_SECONDS"), 20),
            action_jitter_seconds=_int(os.getenv("ACTION_JITTER_SECONDS"), 15),
            min_video_date=min_date,
            ad_disclosure=disclosure,
            similarity_threshold=_float(os.getenv("SIMILARITY_THRESHOLD"), 0.86),
            target_similarity_threshold=_float(os.getenv("TARGET_SIMILARITY_THRESHOLD"), 0.92),
            allow_gift_claims=_bool(os.getenv("ALLOW_GIFT_CLAIMS"), False),
            rubup_comment_markers=markers,
            data_dir=_path(base_dir, os.getenv("DATA_DIR"), "data"),
            image_dir=_path(base_dir, os.getenv("IMAGE_DIR"), "images"),
            template_dir=_path(base_dir, os.getenv("TEMPLATE_DIR"), "templates"),
            selectors_path=_path(base_dir, os.getenv("SELECTORS_PATH"), "selectors.json"),
            browser_profile_dir=_path(base_dir, os.getenv("BROWSER_PROFILE_DIR"), "data/browser-profile"),
            links_file=_path(base_dir, os.getenv("LINKS_FILE"), "data/links.txt"),
            log_level=os.getenv("LOG_LEVEL", "INFO").upper(),
        )
