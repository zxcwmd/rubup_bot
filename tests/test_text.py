from pathlib import Path

import pytest

from rubup_bot.models import ActionType, Video
from rubup_bot.text import (
    CommentGenerator,
    normalize_text,
    similarity,
    topic_for,
    with_disclosure,
)

ROOT = Path(__file__).resolve().parents[1]


def test_normalization_and_similarity() -> None:
    assert normalize_text("Ёлка,  CS2!!!") == "елка cs2"
    assert similarity("хороший ролик", "совсем другой текст") < 0.5
    assert similarity(normalize_text("Хороший ролик"), normalize_text("Хороший ролик!")) == 1.0


def test_disclosure_is_idempotent() -> None:
    assert with_disclosure("Хороший момент", "Реклама") == "Хороший момент Реклама"
    assert with_disclosure("Хороший момент. #реклама", "Реклама") == "Хороший момент. #реклама"


def test_generator_avoids_reuse_and_brand(video: Video) -> None:
    generator = CommentGenerator(ROOT / "templates", "Реклама", seed=7)
    first = generator.generate(video, ActionType.COMMENT, [])
    second = generator.generate(video, ActionType.COMMENT, [first])
    assert first != second
    assert "rubup" not in first.lower()
    assert "Реклама" in first and "Реклама" in second
    assert similarity(normalize_text(first), normalize_text(second)) < 0.86


def test_gift_claims_require_explicit_opt_in(tmp_path: Path, video: Video) -> None:
    (tmp_path / "comments.txt").write_text("Спасибо, бот выдал подарок\n", encoding="utf-8")
    (tmp_path / "replies.txt").write_text("Согласен\n", encoding="utf-8")
    generator = CommentGenerator(tmp_path, "Реклама", seed=1)
    with pytest.raises(RuntimeError):
        generator.generate(video, ActionType.COMMENT, [])
    enabled = CommentGenerator(tmp_path, "Реклама", allow_gift_claims=True, seed=1)
    assert "подарок" in enabled.generate(video, ActionType.COMMENT, [])


def test_topic_uses_video_context(video: Video) -> None:
    assert topic_for(video) == "кейсы и апгрейды"
