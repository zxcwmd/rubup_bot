from __future__ import annotations

import asyncio
import os
from dataclasses import dataclass, field
from pathlib import Path

from .tiktok import canonical_video_url


@dataclass(slots=True)
class LinkStore:
    """Durable one-url-per-line store with process-local append locking."""

    path: Path
    _lock: asyncio.Lock = field(init=False)

    def __post_init__(self) -> None:
        self._lock = asyncio.Lock()

    def _append_sync(self, url: str) -> None:
        canonical = canonical_video_url(url)
        if not canonical:
            raise ValueError(f"Refusing to save a non-canonical TikTok URL: {url}")
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open("a+", encoding="utf-8") as output:
            output.seek(0)
            existing = {
                canonical_video_url(line.strip()) or line.strip()
                for line in output if line.strip()
            }
            if canonical in existing:
                return
            output.seek(0, os.SEEK_END)
            output.write(canonical + "\n")
            output.flush()
            os.fsync(output.fileno())

    async def append(self, url: str) -> None:
        async with self._lock:
            await asyncio.to_thread(self._append_sync, url)

    def read_lines(self) -> list[str]:
        if not self.path.exists():
            return []
        return [line.strip() for line in self.path.read_text(encoding="utf-8").splitlines() if line.strip()]
