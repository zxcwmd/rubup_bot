from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import StrEnum
from pathlib import Path


class ActionType(StrEnum):
    COMMENT = "comment"
    REPLY = "reply"


class ActionStatus(StrEnum):
    PENDING = "pending"
    PUBLISHING = "publishing"
    PUBLISHED = "published"
    MANUAL_REVIEW = "manual_review"
    FAILED = "failed"
    SKIPPED = "skipped"


@dataclass(slots=True)
class Video:
    id: str
    url: str
    author: str = ""
    description: str = ""
    published_at: datetime | None = None
    matched_tags: tuple[str, ...] = field(default_factory=tuple)


@dataclass(slots=True)
class TikTokComment:
    id: str
    video_id: str
    author: str
    text: str
    has_image: bool = False
    is_own: bool = False


@dataclass(slots=True)
class ImageAsset:
    id: int
    path: Path
    sha256: str
    status: str


@dataclass(slots=True)
class PublishResult:
    submitted: bool
    verified: bool
    detail: str = ""


@dataclass(slots=True)
class ImportStats:
    added: int = 0
    duplicates: int = 0
    invalid: int = 0


class BotError(RuntimeError):
    """Base expected error that can be shown without a stack trace."""


class ChallengeRequired(BotError):
    """TikTok presented a challenge. No bypass is attempted."""


class SessionRequired(BotError):
    """The persistent browser profile is not authenticated."""


class ManualActionRequired(BotError):
    """The action may need a person; automatic retry could be unsafe."""

    def __init__(self, message: str, *, may_have_published: bool = False) -> None:
        super().__init__(message)
        self.may_have_published = may_have_published


class DuplicateAction(BotError):
    """A database uniqueness rule prevented a repeated action."""
