from pathlib import Path

import pytest

from rubup_bot.links import LinkStore


async def test_link_store_appends_canonical_urls_once(tmp_path: Path) -> None:
    path = tmp_path / "nested" / "links.txt"
    store = LinkStore(path)
    url = "https://www.tiktok.com/@author/video/7300000000000000001?foo=bar"
    await store.append(url)
    await store.append(url)
    assert store.read_lines() == ["https://www.tiktok.com/@author/video/7300000000000000001"]


async def test_link_store_rejects_non_canonical_url(tmp_path: Path) -> None:
    store = LinkStore(tmp_path / "links.txt")
    with pytest.raises(ValueError, match="non-canonical"):
        await store.append("https://example.com/not-a-video")
    assert store.read_lines() == []
