from __future__ import annotations

import hashlib
import random
import re
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path

from .models import ActionType, Video

_URL_RE = re.compile(r"https?://\S+", re.IGNORECASE)
_SPACE_RE = re.compile(r"\s+")
_WORD_RE = re.compile(r"[a-zа-я0-9]+", re.IGNORECASE)
_BRAND_RE = re.compile(r"\brub\s*up\b", re.IGNORECASE)
_GIFT_RE = re.compile(
    r"(спасибо|спс|благодар).*(подар|скин|мишк|кейс|бот)|"
    r"(выдал|получил|пришл).*(подар|скин|мишк|кейс)",
    re.IGNORECASE,
)

TOPICS: tuple[tuple[tuple[str, ...], str], ...] = (
    (("меллстрой", "мелстрой", "mellstroy", "генсуха", "gensyxa"), "эту нарезку"),
    (("dota2", "дота"), "Dota 2"),
    (("twitch", "твич", "leva2k", "лева2к", "buster", "бустер"), "этот стрим"),
    (("тгподарки", "подаркитг", "telegram", "телеграм", "нфт", "nft"), "подарки в Telegram"),
    (("upgrader", "апгрейдер", "caser", "кейсер", "cs2", "кейс"), "кейсы и апгрейды"),
)


def normalize_text(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).lower().replace("ё", "е")
    value = _URL_RE.sub(" ", value)
    words = _WORD_RE.findall(value)
    return " ".join(words)


def similarity(left: str, right: str) -> float:
    """Hybrid lexical similarity; normalized strings are expected."""
    if not left or not right:
        return 0.0
    if left == right:
        return 1.0
    from difflib import SequenceMatcher

    sequence = SequenceMatcher(None, left, right).ratio()
    left_tokens, right_tokens = set(left.split()), set(right.split())
    union = left_tokens | right_tokens
    jaccard = len(left_tokens & right_tokens) / len(union) if union else 0.0
    return max(sequence, jaccard)


def is_similar(left: str, right: str, threshold: float) -> bool:
    return similarity(normalize_text(left), normalize_text(right)) >= threshold


def topic_for(video: Video) -> str:
    haystack = " ".join((video.description, " ".join(video.matched_tags))).lower()
    for markers, topic in TOPICS:
        if any(marker in haystack for marker in markers):
            return topic
    description = re.sub(r"https?://\S+|#[\w_]+|@[\w_]+", " ", video.description)
    description = _SPACE_RE.sub(" ", description).strip(" .,!?:;-")
    if 4 <= len(description) <= 70:
        return f"тему «{description}»"
    return "этот ролик"


def with_disclosure(text: str, disclosure: str) -> str:
    text = _SPACE_RE.sub(" ", text).strip()
    if re.search(r"(?:#?\bреклама\b|#?\bad\b|\bsponsored\b)", text, re.IGNORECASE):
        return text
    return f"{text} {disclosure}".strip()


@dataclass(slots=True)
class CommentGenerator:
    template_dir: Path
    disclosure: str
    similarity_threshold: float = 0.86
    allow_gift_claims: bool = False
    seed: int | None = None
    _random: random.Random = field(init=False)

    def __post_init__(self) -> None:
        self._random = random.Random(self.seed)

    def _templates(self, action_type: ActionType) -> list[str]:
        name = "comments.txt" if action_type == ActionType.COMMENT else "replies.txt"
        path = self.template_dir / name
        if not path.exists():
            raise FileNotFoundError(f"Template file is missing: {path}")
        templates = [line.strip() for line in path.read_text(encoding="utf-8").splitlines()
                     if line.strip() and not line.lstrip().startswith("#")]
        if self.allow_gift_claims:
            gift_path = self.template_dir / "gifts.txt"
            if action_type == ActionType.COMMENT and gift_path.exists():
                templates.extend(line.strip() for line in gift_path.read_text(encoding="utf-8").splitlines()
                                 if line.strip() and not line.lstrip().startswith("#"))
        return templates

    def generate(
        self,
        video: Video,
        action_type: ActionType,
        forbidden_texts: list[str],
        *,
        target_author: str = "",
    ) -> str:
        templates = self._templates(action_type)
        self._random.shuffle(templates)
        normalized_forbidden = [normalize_text(item) for item in forbidden_texts if item.strip()]
        topic = topic_for(video)
        for template in templates:
            for variant in range(3):
                suffix = ("", ")", " 🙂")[variant]
                text = template.format(topic=topic, author=target_author).strip() + suffix
                if _BRAND_RE.search(text):
                    continue
                if not self.allow_gift_claims and _GIFT_RE.search(text):
                    continue
                text = with_disclosure(text, self.disclosure)
                if not 4 <= len(text) <= 220:
                    continue
                normalized = normalize_text(text)
                if not normalized:
                    continue
                if any(similarity(normalized, old) >= self.similarity_threshold
                       for old in normalized_forbidden):
                    continue
                return text
        raise RuntimeError("No unique on-topic template left. Add new lines to templates/*.txt")


def stable_comment_id(video_id: str, author: str, text: str) -> str:
    raw = f"{video_id}\0{author.lower().lstrip('@')}\0{normalize_text(text)}"
    return "local-" + hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]
