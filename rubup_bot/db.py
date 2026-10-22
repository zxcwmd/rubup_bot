from __future__ import annotations

import asyncio
import json
from datetime import UTC, datetime, time
from pathlib import Path
from typing import Any

import aiosqlite

from .models import (
    ActionStatus,
    ActionType,
    DuplicateAction,
    ImageAsset,
    TikTokComment,
    Video,
)
from .text import normalize_text
from .timeutils import iso_now, parse_iso

SCHEMA = """
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS videos (
    id TEXT PRIMARY KEY,
    url TEXT NOT NULL UNIQUE,
    author TEXT NOT NULL DEFAULT '',
    description TEXT NOT NULL DEFAULT '',
    published_at TEXT,
    matched_tags TEXT NOT NULL DEFAULT '[]',
    state TEXT NOT NULL DEFAULT 'queued',
    skip_reason TEXT NOT NULL DEFAULT '',
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_videos_queue ON videos(state, first_seen_at);

CREATE TABLE IF NOT EXISTS images (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    path TEXT NOT NULL UNIQUE,
    source_path TEXT NOT NULL,
    sha256 TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL DEFAULT 'available',
    imported_at TEXT NOT NULL,
    used_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_images_available ON images(status, id);

CREATE TABLE IF NOT EXISTS reports (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    status TEXT NOT NULL DEFAULT 'pending',
    created_at TEXT NOT NULL,
    sent_at TEXT
);

CREATE TABLE IF NOT EXISTS actions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    dedupe_key TEXT NOT NULL UNIQUE,
    video_id TEXT NOT NULL REFERENCES videos(id),
    action_type TEXT NOT NULL,
    target_comment_id TEXT,
    target_author TEXT NOT NULL DEFAULT '',
    target_text TEXT NOT NULL DEFAULT '',
    text TEXT NOT NULL,
    normalized_text TEXT NOT NULL,
    image_id INTEGER REFERENCES images(id),
    status TEXT NOT NULL DEFAULT 'pending',
    error TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    published_at TEXT,
    report_id INTEGER REFERENCES reports(id)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_actions_unique_text
    ON actions(normalized_text) WHERE normalized_text <> '';
CREATE INDEX IF NOT EXISTS idx_actions_history ON actions(id DESC);
CREATE INDEX IF NOT EXISTS idx_actions_report ON actions(status, report_id);
CREATE INDEX IF NOT EXISTS idx_actions_daily ON actions(action_type, created_at);

CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event TEXT NOT NULL,
    details TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL
);
"""


def _video_from_row(row: aiosqlite.Row) -> Video:
    return Video(
        id=row["id"],
        url=row["url"],
        author=row["author"],
        description=row["description"],
        published_at=parse_iso(row["published_at"]),
        matched_tags=tuple(json.loads(row["matched_tags"])),
    )


def _image_from_row(row: aiosqlite.Row) -> ImageAsset:
    return ImageAsset(id=row["id"], path=Path(row["path"]), sha256=row["sha256"], status=row["status"])


class Database:
    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self.connection: aiosqlite.Connection | None = None
        self._write_lock = asyncio.Lock()

    @property
    def db(self) -> aiosqlite.Connection:
        if self.connection is None:
            raise RuntimeError("Database is not open")
        return self.connection

    async def open(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = await aiosqlite.connect(self.path)
        self.connection.row_factory = aiosqlite.Row
        await self.connection.executescript(SCHEMA)
        await self.connection.execute("PRAGMA journal_mode=WAL")
        await self.connection.execute("PRAGMA busy_timeout=5000")
        await self.connection.commit()

    async def close(self) -> None:
        if self.connection is not None:
            await self.connection.close()
            self.connection = None

    async def audit(self, event: str, details: str = "") -> None:
        await self.db.execute(
            "INSERT INTO audit_log(event, details, created_at) VALUES (?, ?, ?)",
            (event, details[:4000], iso_now()),
        )
        await self.db.commit()

    async def get_setting(self, key: str, default: str | None = None) -> str | None:
        async with self.db.execute("SELECT value FROM settings WHERE key=?", (key,)) as cursor:
            row = await cursor.fetchone()
        return row["value"] if row else default

    async def set_setting(self, key: str, value: str) -> None:
        async with self._write_lock:
            await self.db.execute(
                "INSERT INTO settings(key, value) VALUES (?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (key, value),
            )
            await self.db.commit()

    async def upsert_video(self, video: Video, *, eligible: bool, reason: str = "") -> None:
        state = "queued" if eligible else "skipped"
        now = iso_now()
        async with self._write_lock:
            await self.db.execute(
                """
                INSERT INTO videos(id, url, author, description, published_at, matched_tags,
                                   state, skip_reason, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    url=excluded.url,
                    author=excluded.author,
                    description=excluded.description,
                    published_at=COALESCE(excluded.published_at, videos.published_at),
                    matched_tags=excluded.matched_tags,
                    last_seen_at=excluded.last_seen_at,
                    state=CASE WHEN videos.state='processed'
                               THEN videos.state ELSE excluded.state END,
                    skip_reason=CASE WHEN videos.state='processed'
                                     THEN videos.skip_reason ELSE excluded.skip_reason END
                """,
                (
                    video.id, video.url, video.author, video.description,
                    video.published_at.isoformat() if video.published_at else None,
                    json.dumps(video.matched_tags, ensure_ascii=False), state, reason, now, now,
                ),
            )
            await self.db.commit()

    async def get_video(self, video_id: str) -> Video | None:
        async with self.db.execute("SELECT * FROM videos WHERE id=?", (video_id,)) as cursor:
            row = await cursor.fetchone()
        return _video_from_row(row) if row else None

    async def queued_videos(self, limit: int) -> list[Video]:
        async with self.db.execute(
            """
            SELECT * FROM videos v
            WHERE v.state='queued'
              AND NOT EXISTS (
                  SELECT 1 FROM actions a
                  WHERE a.video_id=v.id AND a.action_type='comment'
              )
            ORDER BY v.first_seen_at LIMIT ?
            """,
            (limit,),
        ) as cursor:
            rows = await cursor.fetchall()
        return [_video_from_row(row) for row in rows]

    async def set_video_state(self, video_id: str, state: str, reason: str = "") -> None:
        async with self._write_lock:
            await self.db.execute(
                "UPDATE videos SET state=?, skip_reason=?, last_seen_at=? WHERE id=?",
                (state, reason, iso_now(), video_id),
            )
            await self.db.commit()

    @staticmethod
    def dedupe_key(action_type: ActionType, video_id: str, target_comment_id: str | None = None) -> str:
        if action_type == ActionType.COMMENT:
            return f"comment:{video_id}"
        if not target_comment_id:
            raise ValueError("A reply needs a target comment ID")
        return f"reply:{video_id}:{target_comment_id}"

    async def action_exists(self, key: str) -> bool:
        async with self.db.execute("SELECT 1 FROM actions WHERE dedupe_key=?", (key,)) as cursor:
            return await cursor.fetchone() is not None

    async def video_has_actions(self, video_id: str) -> bool:
        async with self.db.execute("SELECT 1 FROM actions WHERE video_id=? LIMIT 1", (video_id,)) as cursor:
            return await cursor.fetchone() is not None

    async def create_action(
        self,
        video: Video,
        action_type: ActionType,
        text: str,
        *,
        image: ImageAsset | None = None,
        target: TikTokComment | None = None,
    ) -> int:
        target_id = target.id if target else None
        key = self.dedupe_key(action_type, video.id, target_id)
        normalized = normalize_text(text)
        now = iso_now()
        async with self._write_lock:
            await self.db.execute("BEGIN IMMEDIATE")
            try:
                if image is not None:
                    cursor = await self.db.execute(
                        "UPDATE images SET status='reserved' WHERE id=? AND status='available'",
                        (image.id,),
                    )
                    if cursor.rowcount != 1:
                        raise DuplicateAction(f"Image {image.id} is not available")
                cursor = await self.db.execute(
                    """
                    INSERT INTO actions(dedupe_key, video_id, action_type, target_comment_id,
                        target_author, target_text, text, normalized_text, image_id,
                        status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?)
                    """,
                    (
                        key, video.id, action_type.value, target_id,
                        target.author if target else "", target.text if target else "",
                        text, normalized, image.id if image else None, now, now,
                    ),
                )
                action_id = int(cursor.lastrowid)
                await self.db.commit()
                return action_id
            except DuplicateAction:
                await self.db.rollback()
                raise
            except aiosqlite.IntegrityError as exc:
                await self.db.rollback()
                raise DuplicateAction(f"Action or text already exists: {exc}") from exc
            except Exception:
                await self.db.rollback()
                raise

    async def set_action_status(self, action_id: int, status: ActionStatus, error: str = "") -> None:
        now = iso_now()
        published_at = now if status == ActionStatus.PUBLISHED else None
        async with self._write_lock:
            await self.db.execute(
                """
                UPDATE actions SET status=?, error=?, updated_at=?,
                    published_at=COALESCE(?, published_at)
                WHERE id=?
                """,
                (status.value, error[:4000], now, published_at, action_id),
            )
            if status == ActionStatus.PUBLISHED:
                await self.db.execute(
                    "UPDATE images SET status='used', used_at=? "
                    "WHERE id=(SELECT image_id FROM actions WHERE id=?)",
                    (now, action_id),
                )
            await self.db.commit()

    async def get_action(self, action_id: int) -> dict[str, Any] | None:
        async with self.db.execute(
            """
            SELECT a.*, v.url AS video_url, i.path AS image_path
            FROM actions a JOIN videos v ON v.id=a.video_id
            LEFT JOIN images i ON i.id=a.image_id
            WHERE a.id=?
            """,
            (action_id,),
        ) as cursor:
            row = await cursor.fetchone()
        return dict(row) if row else None

    async def pending_actions(self, limit: int = 20) -> list[dict[str, Any]]:
        async with self.db.execute(
            """
            SELECT a.*, v.url AS video_url, i.path AS image_path
            FROM actions a JOIN videos v ON v.id=a.video_id
            LEFT JOIN images i ON i.id=a.image_id
            WHERE a.status='pending' ORDER BY a.id LIMIT ?
            """,
            (limit,),
        ) as cursor:
            rows = await cursor.fetchall()
        return [dict(row) for row in rows]

    async def requeue_waiting_for_images(self) -> int:
        async with self._write_lock:
            cursor = await self.db.execute(
                "UPDATE videos SET state='queued', skip_reason='' WHERE state='waiting_images'"
            )
            await self.db.commit()
            return cursor.rowcount

    async def used_texts(self, action_type: ActionType | None = None) -> list[str]:
        if action_type:
            query = "SELECT normalized_text FROM actions WHERE action_type=? AND normalized_text<>''"
            params: tuple[Any, ...] = (action_type.value,)
        else:
            query = "SELECT normalized_text FROM actions WHERE normalized_text<>''"
            params = ()
        async with self.db.execute(query, params) as cursor:
            rows = await cursor.fetchall()
        return [row["normalized_text"] for row in rows]

    async def previous_target_texts(self) -> list[str]:
        async with self.db.execute(
            "SELECT target_text FROM actions WHERE action_type='reply' AND target_text<>''"
        ) as cursor:
            rows = await cursor.fetchall()
        return [row["target_text"] for row in rows]

    async def next_available_image(self) -> ImageAsset | None:
        async with self.db.execute(
            "SELECT * FROM images WHERE status='available' ORDER BY id LIMIT 1"
        ) as cursor:
            row = await cursor.fetchone()
        return _image_from_row(row) if row else None

    async def add_image(self, *, path: Path, source_path: Path, sha256: str) -> bool:
        try:
            async with self._write_lock:
                await self.db.execute(
                    "INSERT INTO images(path, source_path, sha256, imported_at) VALUES (?, ?, ?, ?)",
                    (str(path), str(source_path), sha256, iso_now()),
                )
                await self.db.commit()
            return True
        except aiosqlite.IntegrityError:
            await self.db.rollback()
            return False

    async def image_sha_exists(self, sha256: str) -> bool:
        async with self.db.execute("SELECT 1 FROM images WHERE sha256=?", (sha256,)) as cursor:
            return await cursor.fetchone() is not None

    async def count_actions_today(self, action_type: ActionType) -> int:
        start = datetime.combine(datetime.now(UTC).date(), time.min, tzinfo=UTC).isoformat()
        async with self.db.execute(
            "SELECT COUNT(*) AS n FROM actions WHERE action_type=? AND created_at>=? AND status<>'skipped'",
            (action_type.value, start),
        ) as cursor:
            row = await cursor.fetchone()
        return int(row["n"])

    async def image_counts(self) -> dict[str, int]:
        result = {"available": 0, "reserved": 0, "used": 0}
        async with self.db.execute("SELECT status, COUNT(*) AS n FROM images GROUP BY status") as cursor:
            rows = await cursor.fetchall()
        for row in rows:
            result[row["status"]] = int(row["n"])
        return result

    async def stats(self) -> dict[str, int]:
        queries = {
            "queued": "SELECT COUNT(*) n FROM videos WHERE state='queued'",
            "videos": "SELECT COUNT(*) n FROM videos",
            "published": "SELECT COUNT(*) n FROM actions WHERE status='published'",
            "manual": "SELECT COUNT(*) n FROM actions WHERE status='manual_review'",
            "failed": "SELECT COUNT(*) n FROM actions WHERE status='failed'",
            "unreported": "SELECT COUNT(*) n FROM actions WHERE status='published' AND report_id IS NULL",
        }
        result: dict[str, int] = {}
        for key, query in queries.items():
            async with self.db.execute(query) as cursor:
                row = await cursor.fetchone()
            result[key] = int(row["n"])
        images = await self.image_counts()
        result.update({f"images_{key}": value for key, value in images.items()})
        return result

    async def history(self, limit: int = 10, offset: int = 0) -> list[dict[str, Any]]:
        async with self.db.execute(
            """
            SELECT a.*, v.url AS video_url FROM actions a
            JOIN videos v ON v.id=a.video_id ORDER BY a.id DESC LIMIT ? OFFSET ?
            """,
            (limit, offset),
        ) as cursor:
            rows = await cursor.fetchall()
        return [dict(row) for row in rows]

    async def unreported_action_ids(self) -> list[int]:
        async with self.db.execute(
            "SELECT id FROM actions WHERE status='published' AND report_id IS NULL ORDER BY id"
        ) as cursor:
            rows = await cursor.fetchall()
        return [int(row["id"]) for row in rows]

    async def pending_report(self) -> dict[str, Any] | None:
        async with self.db.execute(
            "SELECT * FROM reports WHERE status='pending' ORDER BY id LIMIT 1"
        ) as cursor:
            row = await cursor.fetchone()
        return dict(row) if row else None

    async def create_report(self) -> int | None:
        async with self._write_lock:
            await self.db.execute("BEGIN IMMEDIATE")
            try:
                existing = await self.db.execute_fetchall(
                    "SELECT id FROM reports WHERE status='pending' ORDER BY id LIMIT 1"
                )
                if existing:
                    await self.db.commit()
                    return int(existing[0]["id"])
                rows = await self.db.execute_fetchall(
                    "SELECT id FROM actions WHERE status='published' AND report_id IS NULL ORDER BY id"
                )
                if not rows:
                    await self.db.commit()
                    return None
                cursor = await self.db.execute(
                    "INSERT INTO reports(status, created_at) VALUES ('pending', ?)", (iso_now(),)
                )
                report_id = int(cursor.lastrowid)
                ids = [int(row["id"]) for row in rows]
                placeholders = ",".join("?" for _ in ids)
                await self.db.execute(
                    f"UPDATE actions SET report_id=?, updated_at=? WHERE id IN ({placeholders}) "
                    "AND status='published' AND report_id IS NULL",
                    (report_id, iso_now(), *ids),
                )
                await self.db.commit()
                return report_id
            except Exception:
                await self.db.rollback()
                raise

    async def report_links(self, report_id: int) -> list[str]:
        async with self.db.execute(
            """
            SELECT v.url FROM actions a JOIN videos v ON v.id=a.video_id
            WHERE a.report_id=? AND a.status='published' ORDER BY a.id
            """,
            (report_id,),
        ) as cursor:
            rows = await cursor.fetchall()
        return [row["url"] for row in rows]

    async def mark_report_sent(self, report_id: int) -> None:
        async with self._write_lock:
            await self.db.execute(
                "UPDATE reports SET status='sent', sent_at=? WHERE id=?", (iso_now(), report_id)
            )
            await self.db.commit()

    async def reports(self, limit: int = 10) -> list[dict[str, Any]]:
        async with self.db.execute(
            """
            SELECT r.*, COUNT(a.id) AS action_count FROM reports r
            LEFT JOIN actions a ON a.report_id=r.id
            GROUP BY r.id ORDER BY r.id DESC LIMIT ?
            """,
            (limit,),
        ) as cursor:
            rows = await cursor.fetchall()
        return [dict(row) for row in rows]

    async def all_video_urls(self) -> set[str]:
        async with self.db.execute("SELECT url FROM videos") as cursor:
            rows = await cursor.fetchall()
        return {row["url"] for row in rows}
