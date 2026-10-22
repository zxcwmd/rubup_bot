from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path

import pytest
import pytest_asyncio

from rubup_bot.db import Database
from rubup_bot.models import Video


@pytest_asyncio.fixture
async def db(tmp_path: Path):
    database = Database(tmp_path / "test.sqlite3")
    await database.open()
    yield database
    await database.close()


@pytest.fixture
def video() -> Video:
    return Video(
        id="7300000000000000001",
        url="https://www.tiktok.com/@author/video/7300000000000000001",
        author="author",
        description="Интересный момент #cs2 #нарезка",
        published_at=datetime(2026, 5, 2, 12, 0, tzinfo=UTC),
        matched_tags=("cs2", "нарезка"),
    )
