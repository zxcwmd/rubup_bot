import asyncio
from datetime import UTC

import pytest

from rubup_bot.models import AutomationStopped
from rubup_bot.tiktok import (
    _ensure_not_stopped,
    _timestamp,
    canonical_video_url,
    video_id_from_url,
)


def test_canonical_video_url_strips_tracking() -> None:
    url = "https://www.tiktok.com/@some.user/video/7300000000000000001?is_from_webapp=1&sender_device=pc"
    canonical = canonical_video_url(url)
    assert canonical == "https://www.tiktok.com/@some.user/video/7300000000000000001"
    assert video_id_from_url(url) == "7300000000000000001"


def test_canonical_video_url_rejects_lookalike_host() -> None:
    assert canonical_video_url("https://www.tiktok.com.evil.test/@a/video/7300000000000000001") is None
    assert canonical_video_url("https://example.com/@a/video/7300000000000000001") is None


def test_timestamp_supports_seconds_and_milliseconds() -> None:
    seconds = _timestamp(1777684800)
    milliseconds = _timestamp(1777684800000)
    assert seconds == milliseconds
    assert seconds.tzinfo == UTC
    assert _timestamp("bad") is None


def test_stop_guard_blocks_irreversible_click() -> None:
    stop_event = asyncio.Event()
    _ensure_not_stopped(stop_event)
    stop_event.set()
    with pytest.raises(AutomationStopped, match="до нажатия"):
        _ensure_not_stopped(stop_event)
