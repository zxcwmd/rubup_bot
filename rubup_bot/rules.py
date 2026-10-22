from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import UTC, date

from .models import TikTokComment, Video
from .text import normalize_text, similarity


@dataclass(frozen=True, slots=True)
class RuleDecision:
    allowed: bool
    reason: str = ""


def video_decision(video: Video, minimum_date: date) -> RuleDecision:
    if video.published_at is None:
        return RuleDecision(False, "Дата публикации не определена")
    published = video.published_at
    if published.tzinfo is None:
        published = published.replace(tzinfo=UTC)
    if published.date() < minimum_date:
        return RuleDecision(False, f"Опубликовано раньше {minimum_date.isoformat()}")
    if not video.matched_tags:
        return RuleDecision(False, "Нет подходящего тега")
    return RuleDecision(True)


class CommentRules:
    def __init__(
        self,
        *,
        own_username: str,
        rubup_markers: str,
        target_similarity_threshold: float = 0.92,
    ) -> None:
        self.own_username = own_username.lower().lstrip("@")
        self.markers = re.compile(rubup_markers, re.IGNORECASE) if rubup_markers else None
        self.target_similarity_threshold = target_similarity_threshold

    def is_own(self, comment: TikTokComment) -> bool:
        author = comment.author.lower().lstrip("@").strip()
        return comment.is_own or bool(self.own_username and author == self.own_username)

    def is_rubup_comment(self, comment: TikTokComment) -> bool:
        # Deliberately conservative: an arbitrary image comment is NOT assumed
        # to belong to RubUP. Configure exact markers from verified examples.
        return bool(self.markers and self.markers.search(comment.text))

    def reply_decision(
        self,
        comment: TikTokComment,
        previous_target_texts: list[str],
    ) -> RuleDecision:
        if self.is_own(comment):
            return RuleDecision(False, "Нельзя отвечать под своим комментарием")
        if not self.is_rubup_comment(comment):
            return RuleDecision(False, "Комментарий не распознан как чужой RubUP")
        normalized = normalize_text(comment.text)
        if not normalized:
            return RuleDecision(False, "Пустой текст комментария")
        for previous in previous_target_texts:
            if similarity(normalized, normalize_text(previous)) >= self.target_similarity_threshold:
                return RuleDecision(False, "Похожий комментарий уже обработан")
        return RuleDecision(True)
