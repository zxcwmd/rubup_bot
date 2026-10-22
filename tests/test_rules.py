from datetime import UTC, date, datetime

from rubup_bot.models import TikTokComment, Video
from rubup_bot.rules import CommentRules, video_decision


def test_video_cutoff_is_may_2026(video: Video) -> None:
    assert video_decision(video, date(2026, 5, 1)).allowed
    video.published_at = datetime(2026, 4, 30, 23, 59, tzinfo=UTC)
    decision = video_decision(video, date(2026, 5, 1))
    assert not decision.allowed
    assert "2026-05-01" in decision.reason


def test_video_requires_date_and_tag(video: Video) -> None:
    video.published_at = None
    assert not video_decision(video, date(2026, 5, 1)).allowed
    video.published_at = datetime(2026, 6, 1, tzinfo=UTC)
    video.matched_tags = ()
    assert not video_decision(video, date(2026, 5, 1)).allowed


def test_reply_rules_exclude_own_unrelated_and_similar(video: Video) -> None:
    rules = CommentRules(
        own_username="myaccount",
        rubup_markers=r"rubup|rub\s*up",
        target_similarity_threshold=0.9,
    )
    own = TikTokComment("1", video.id, "@MyAccount", "RubUP прислал подарок")
    unrelated = TikTokComment("2", video.id, "someone", "Просто хороший ролик")
    target = TikTokComment("3", video.id, "worker", "RubUP: отличный момент")
    assert not rules.reply_decision(own, []).allowed
    assert not rules.reply_decision(unrelated, []).allowed
    assert rules.reply_decision(target, []).allowed
    assert not rules.reply_decision(target, ["RubUP: отличный момент!"]).allowed


def test_arbitrary_photo_is_not_enough_for_rubup(video: Video) -> None:
    rules = CommentRules(own_username="me", rubup_markers="rubup")
    comment = TikTokComment("4", video.id, "other", "Согласен!", has_image=True)
    assert not rules.is_rubup_comment(comment)
