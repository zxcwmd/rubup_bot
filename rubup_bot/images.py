from __future__ import annotations

import asyncio
import hashlib
import shutil
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image, UnidentifiedImageError

from .db import Database
from .models import ImportStats

SUPPORTED_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}


@dataclass(slots=True)
class ImageLibrary:
    incoming_dir: Path
    managed_dir: Path
    db: Database
    _lock: asyncio.Lock = field(init=False)

    def __post_init__(self) -> None:
        self._lock = asyncio.Lock()

    @staticmethod
    def _hash_and_validate(path: Path) -> str:
        if path.stat().st_size > 20 * 1024 * 1024:
            raise ValueError("image is larger than 20 MiB")
        digest = hashlib.sha256()
        with path.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
        with Image.open(path) as image:
            if image.format not in {"JPEG", "PNG", "WEBP"}:
                raise ValueError(f"unsupported format: {image.format}")
            image.verify()
        return digest.hexdigest()

    async def scan(self) -> ImportStats:
        """Import immutable hash-named copies; source files are never deleted."""
        async with self._lock:
            stats = ImportStats()
            self.incoming_dir.mkdir(parents=True, exist_ok=True)
            self.managed_dir.mkdir(parents=True, exist_ok=True)
            files = await asyncio.to_thread(
                lambda: sorted(
                    path for path in self.incoming_dir.rglob("*")
                    if path.is_file() and path.suffix.lower() in SUPPORTED_EXTENSIONS
                    and not path.name.startswith(".")
                )
            )
            for source in files:
                try:
                    sha256 = await asyncio.to_thread(self._hash_and_validate, source)
                except (OSError, ValueError, UnidentifiedImageError, Image.DecompressionBombError):
                    stats.invalid += 1
                    continue
                if await self.db.image_sha_exists(sha256):
                    stats.duplicates += 1
                    continue
                destination = self.managed_dir / f"{sha256}{source.suffix.lower()}"
                existed = destination.exists()
                if not existed:
                    temporary = destination.with_suffix(destination.suffix + ".tmp")
                    await asyncio.to_thread(shutil.copyfile, source, temporary)
                    temporary.replace(destination)
                added = await self.db.add_image(path=destination, source_path=source, sha256=sha256)
                if added:
                    stats.added += 1
                else:
                    stats.duplicates += 1
                    if not existed:
                        destination.unlink(missing_ok=True)
            return stats
