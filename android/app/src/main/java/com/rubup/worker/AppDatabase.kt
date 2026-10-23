package com.rubup.worker

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.ZoneOffset

class AppDatabase(context: Context) : SQLiteOpenHelper(context, "rubup-worker.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        val statements = listOf(
            """CREATE TABLE videos(
                id TEXT PRIMARY KEY, url TEXT NOT NULL UNIQUE, author TEXT NOT NULL DEFAULT '',
                description TEXT NOT NULL DEFAULT '', published_at INTEGER, matched_tags TEXT NOT NULL DEFAULT '',
                state TEXT NOT NULL DEFAULT 'queued', skip_reason TEXT NOT NULL DEFAULT '',
                first_seen_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL)""",
            "CREATE INDEX idx_videos_queue ON videos(state, first_seen_at)",
            """CREATE TABLE images(
                id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL UNIQUE,
                sha256 TEXT NOT NULL UNIQUE, status TEXT NOT NULL DEFAULT 'available',
                imported_at INTEGER NOT NULL, used_at INTEGER)""",
            """CREATE TABLE reports(
                id INTEGER PRIMARY KEY AUTOINCREMENT, status TEXT NOT NULL DEFAULT 'pending',
                created_at INTEGER NOT NULL, sent_at INTEGER)""",
            """CREATE TABLE actions(
                id INTEGER PRIMARY KEY AUTOINCREMENT, dedupe_key TEXT NOT NULL UNIQUE,
                video_id TEXT NOT NULL REFERENCES videos(id), action_type TEXT NOT NULL,
                target_comment_id TEXT, target_author TEXT NOT NULL DEFAULT '',
                target_text TEXT NOT NULL DEFAULT '', target_dom_index INTEGER NOT NULL DEFAULT -1,
                text TEXT NOT NULL, normalized_text TEXT NOT NULL UNIQUE,
                image_id INTEGER REFERENCES images(id), status TEXT NOT NULL DEFAULT 'pending',
                error TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                published_at INTEGER, report_id INTEGER)""",
            "CREATE INDEX idx_actions_daily ON actions(action_type, created_at)",
            """CREATE TABLE likes(
                action_id INTEGER PRIMARY KEY REFERENCES actions(id) ON DELETE CASCADE,
                status TEXT NOT NULL DEFAULT 'pending', error TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL, liked_at INTEGER)""",
            """CREATE TABLE saved_links(
                action_id INTEGER PRIMARY KEY REFERENCES actions(id) ON DELETE CASCADE,
                video_id TEXT NOT NULL UNIQUE REFERENCES videos(id), url TEXT NOT NULL,
                file_path TEXT NOT NULL, saved_at INTEGER NOT NULL)""",
        )
        statements.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun upsertVideo(video: Video, eligible: Boolean, reason: String = "") {
        val now = System.currentTimeMillis()
        val state = if (eligible) "queued" else "skipped"
        writableDatabase.execSQL(
            """INSERT INTO videos(id,url,author,description,published_at,matched_tags,state,skip_reason,first_seen_at,last_seen_at)
               VALUES(?,?,?,?,?,?,?,?,?,?)
               ON CONFLICT(id) DO UPDATE SET url=excluded.url,author=excluded.author,
               description=excluded.description,published_at=COALESCE(excluded.published_at,videos.published_at),
               matched_tags=excluded.matched_tags,last_seen_at=excluded.last_seen_at,
               state=CASE WHEN videos.state='processed' THEN videos.state ELSE excluded.state END,
               skip_reason=CASE WHEN videos.state='processed' THEN videos.skip_reason ELSE excluded.skip_reason END""",
            arrayOf(video.id, video.url, video.author, video.description, video.publishedAt,
                video.matchedTags.joinToString(" "), state, reason, now, now),
        )
    }

    @Synchronized
    fun getVideo(id: String): Video? = readableDatabase.rawQuery("SELECT * FROM videos WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) it.toVideo() else null
    }

    @Synchronized
    fun allVideoUrls(): Set<String> = readableDatabase.rawQuery("SELECT url FROM videos", null).use {
        buildSet { while (it.moveToNext()) add(it.string("url")) }
    }

    @Synchronized
    fun queuedVideos(limit: Int): List<Video> = readableDatabase.rawQuery(
        "SELECT * FROM videos WHERE state='queued' ORDER BY first_seen_at LIMIT ?",
        arrayOf(limit.toString()),
    ).use { buildList { while (it.moveToNext()) add(it.toVideo()) } }

    @Synchronized
    fun setVideoState(id: String, state: String, reason: String = "") {
        val values = ContentValues().apply { put("state", state); put("skip_reason", reason) }
        writableDatabase.update("videos", values, "id=?", arrayOf(id))
    }

    @Synchronized
    fun requeueWaitingForImages(): Int {
        val values = ContentValues().apply { put("state", "queued"); put("skip_reason", "") }
        return writableDatabase.update("videos", values, "state='waiting_images'", null)
    }

    @Synchronized
    fun addImage(path: String, hash: String): Boolean {
        val values = ContentValues().apply {
            put("path", path); put("sha256", hash); put("status", "available")
            put("imported_at", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("images", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    @Synchronized
    fun nextImage(): ImageAsset? = readableDatabase.rawQuery(
        "SELECT * FROM images WHERE status='available' ORDER BY id LIMIT 1", null,
    ).use { if (it.moveToFirst()) it.toImage() else null }

    @Synchronized
    fun imageCounts(): Map<String, Int> {
        val result = mutableMapOf("available" to 0, "reserved" to 0, "used" to 0)
        readableDatabase.rawQuery("SELECT status,COUNT(*) n FROM images GROUP BY status", null).use {
            while (it.moveToNext()) result[it.string("status")] = it.int("n")
        }
        return result
    }

    @Synchronized
    fun nextImageId(path: String): Long = readableDatabase.rawQuery(
        "SELECT id FROM images WHERE path=?", arrayOf(path),
    ).use { if (it.moveToFirst()) it.getLong(0) else throw BotFailure("Импортированная картинка не найдена") }

    fun dedupeKey(type: ActionType, videoId: String, targetId: String? = null): String =
        if (type == ActionType.COMMENT) "comment:$videoId" else "reply:$videoId:$targetId"

    @Synchronized
    fun actionExists(key: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM actions WHERE dedupe_key=?", arrayOf(key),
    ).use { it.moveToFirst() }

    @Synchronized
    fun createAction(video: Video, type: ActionType, text: String, target: TikTokComment?, image: ImageAsset?): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (image != null) {
                val reserved = ContentValues().apply { put("status", "reserved") }
                if (db.update("images", reserved, "id=? AND status='available'", arrayOf(image.id.toString())) != 1) {
                    throw DuplicateAction("Картинка уже зарезервирована")
                }
            }
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("dedupe_key", dedupeKey(type, video.id, target?.id))
                put("video_id", video.id); put("action_type", type.dbValue)
                put("target_comment_id", target?.id); put("target_author", target?.author ?: "")
                put("target_text", target?.text ?: ""); put("target_dom_index", target?.domIndex ?: -1)
                put("text", text); put("normalized_text", normalizeText(text))
                put("image_id", image?.id); put("status", ActionStatus.PENDING.dbValue)
                put("created_at", now); put("updated_at", now)
            }
            val id = db.insertOrThrow("actions", null, values)
            if (type == ActionType.COMMENT) {
                db.execSQL("INSERT INTO likes(action_id,status,updated_at) VALUES(?,'pending',?)", arrayOf(id, now))
            }
            db.setTransactionSuccessful()
            return id
        } catch (error: android.database.sqlite.SQLiteConstraintException) {
            throw DuplicateAction("Действие или текст уже существует: ${error.message}")
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun setActionStatus(id: Long, status: ActionStatus, error: String = "") {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("status", status.dbValue); put("error", error.take(4000)); put("updated_at", now)
                if (status == ActionStatus.PUBLISHED) put("published_at", now)
            }
            db.update("actions", values, "id=?", arrayOf(id.toString()))
            if (status == ActionStatus.PUBLISHED) {
                db.execSQL(
                    "INSERT OR IGNORE INTO likes(action_id,status,updated_at) SELECT id,'pending',? FROM actions WHERE id=? AND action_type='comment'",
                    arrayOf(now, id),
                )
                db.execSQL(
                    "UPDATE images SET status='used',used_at=? WHERE id=(SELECT image_id FROM actions WHERE id=?)",
                    arrayOf(now, id),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun getAction(id: Long): ActionItem? = actionQuery("a.id=?", arrayOf(id.toString())).firstOrNull()

    @Synchronized
    fun pendingActions(limit: Int): List<ActionItem> = actionQuery(
        "a.status='pending' ORDER BY a.id LIMIT ?", arrayOf(limit.toString()),
    )

    @Synchronized
    fun pendingLikes(limit: Int): List<ActionItem> = actionQuery(
        """a.action_type='comment' AND a.status='published' AND a.image_id IS NOT NULL
           AND l.status IN ('pending','failed','liked') AND sl.action_id IS NULL
           ORDER BY a.id LIMIT ?""",
        arrayOf(limit.toString()),
    )

    private fun actionQuery(where: String, args: Array<String>): List<ActionItem> {
        val sql = """SELECT a.*,v.url video_url,i.path image_path,l.status like_status,sl.saved_at link_saved_at
            FROM actions a JOIN videos v ON v.id=a.video_id LEFT JOIN images i ON i.id=a.image_id
            LEFT JOIN likes l ON l.action_id=a.id LEFT JOIN saved_links sl ON sl.action_id=a.id
            WHERE $where"""
        return readableDatabase.rawQuery(sql, args).use { buildList { while (it.moveToNext()) add(it.toAction()) } }
    }

    @Synchronized
    fun history(limit: Int = 10): List<ActionItem> = actionQuery("1=1 ORDER BY a.id DESC LIMIT ?", arrayOf(limit.toString()))

    @Synchronized
    fun setLikeStatus(id: Long, status: LikeStatus, error: String = "") {
        val values = ContentValues().apply {
            put("status", status.dbValue); put("error", error.take(4000)); put("updated_at", System.currentTimeMillis())
            if (status == LikeStatus.LIKED) put("liked_at", System.currentTimeMillis())
        }
        if (writableDatabase.update("likes", values, "action_id=?", arrayOf(id.toString())) != 1) {
            throw BotFailure("Не найдена запись лайка для действия $id")
        }
    }

    @Synchronized
    fun saveLink(id: Long, url: String, filePath: String) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.execSQL(
                """INSERT OR IGNORE INTO saved_links(action_id,video_id,url,file_path,saved_at)
                   SELECT a.id,a.video_id,?,?,? FROM actions a
                   WHERE a.id=? AND a.action_type='comment' AND a.status='published' AND a.image_id IS NOT NULL
                   AND EXISTS(SELECT 1 FROM likes l WHERE l.action_id=a.id AND l.status='liked')""",
                arrayOf(url, filePath, now, id),
            )
            val exists = readableDatabase.rawQuery("SELECT 1 FROM saved_links WHERE action_id=?", arrayOf(id.toString())).use { it.moveToFirst() }
            if (!exists) throw BotFailure("Ссылка отклонена: нет фото-комментария или подтверждённого лайка")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun countActionsToday(type: ActionType): Int {
        val start = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        return scalar("SELECT COUNT(*) FROM actions WHERE action_type=? AND created_at>=? AND status<>'skipped'", type.dbValue, start)
    }

    @Synchronized
    fun usedTexts(type: ActionType): List<String> = readableDatabase.rawQuery(
        "SELECT normalized_text FROM actions WHERE action_type=? AND normalized_text<>''", arrayOf(type.dbValue),
    ).use { buildList { while (it.moveToNext()) add(it.getString(0)) } }

    @Synchronized
    fun previousTargetTexts(): List<String> = readableDatabase.rawQuery(
        "SELECT target_text FROM actions WHERE action_type='reply' AND target_text<>''", null,
    ).use { buildList { while (it.moveToNext()) add(it.getString(0)) } }

    @Synchronized
    fun videoHasActions(videoId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM actions WHERE video_id=? LIMIT 1", arrayOf(videoId),
    ).use { it.moveToFirst() }

    @Synchronized
    fun stats(): Map<String, Int> {
        val result = linkedMapOf(
            "videos" to scalar("SELECT COUNT(*) FROM videos"),
            "queued" to scalar("SELECT COUNT(*) FROM videos WHERE state='queued'"),
            "published" to scalar("SELECT COUNT(*) FROM actions WHERE status='published'"),
            "manual" to scalar("SELECT COUNT(*) FROM actions WHERE status='manual_review'"),
            "failed" to scalar("SELECT COUNT(*) FROM actions WHERE status='failed'"),
            "pending_likes" to scalar("SELECT COUNT(*) FROM likes WHERE status IN ('pending','publishing','failed')"),
            "likes_manual" to scalar("SELECT COUNT(*) FROM likes WHERE status='manual_review'"),
            "links" to scalar("SELECT COUNT(*) FROM saved_links"),
        )
        imageCounts().forEach { (key, value) -> result["images_$key"] = value }
        return result
    }

    @Synchronized
    fun createReport(): Long? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val pending = db.rawQuery("SELECT id FROM reports WHERE status='pending' ORDER BY id LIMIT 1", null).use {
                if (it.moveToFirst()) it.getLong(0) else null
            }
            if (pending != null) {
                db.setTransactionSuccessful(); return pending
            }
            val ids = db.rawQuery(
                """SELECT a.id FROM actions a WHERE a.status='published' AND a.report_id IS NULL
                   AND (a.action_type='reply' OR (a.image_id IS NOT NULL
                   AND EXISTS(SELECT 1 FROM likes l WHERE l.action_id=a.id AND l.status='liked')
                   AND EXISTS(SELECT 1 FROM saved_links sl WHERE sl.action_id=a.id))) ORDER BY a.id""",
                null,
            ).use { buildList { while (it.moveToNext()) add(it.getLong(0)) } }
            if (ids.isEmpty()) {
                db.setTransactionSuccessful(); return null
            }
            val reportId = db.insertOrThrow("reports", null, ContentValues().apply {
                put("status", "pending"); put("created_at", System.currentTimeMillis())
            })
            ids.forEach { id ->
                db.execSQL("UPDATE actions SET report_id=? WHERE id=? AND report_id IS NULL", arrayOf(reportId, id))
            }
            db.setTransactionSuccessful()
            return reportId
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun reportLinks(reportId: Long): List<String> = readableDatabase.rawQuery(
        """SELECT v.url FROM actions a JOIN videos v ON v.id=a.video_id
           WHERE a.report_id=? AND a.status='published'
           AND (a.action_type='reply' OR (a.image_id IS NOT NULL
           AND EXISTS(SELECT 1 FROM likes l WHERE l.action_id=a.id AND l.status='liked')
           AND EXISTS(SELECT 1 FROM saved_links sl WHERE sl.action_id=a.id))) ORDER BY a.id""",
        arrayOf(reportId.toString()),
    ).use { buildList { while (it.moveToNext()) add(it.getString(0)) } }

    @Synchronized
    fun markReportSent(reportId: Long) {
        writableDatabase.update("reports", ContentValues().apply {
            put("status", "sent"); put("sent_at", System.currentTimeMillis())
        }, "id=?", arrayOf(reportId.toString()))
    }

    private fun scalar(sql: String, vararg args: Any?): Int = readableDatabase.rawQuery(
        sql, args.map { it.toString() }.toTypedArray(),
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
}

class DuplicateAction(message: String) : BotFailure(message)

private fun Cursor.column(name: String): Int = getColumnIndexOrThrow(name)
private fun Cursor.string(name: String): String = getString(column(name)) ?: ""
private fun Cursor.int(name: String): Int = getInt(column(name))
private fun Cursor.longOrNull(name: String): Long? = if (isNull(column(name))) null else getLong(column(name))

private fun Cursor.toVideo() = Video(
    id = string("id"), url = string("url"), author = string("author"), description = string("description"),
    publishedAt = longOrNull("published_at"),
    matchedTags = string("matched_tags").split(' ').filter(String::isNotBlank).toSet(),
)

private fun Cursor.toImage() = ImageAsset(
    id = getLong(column("id")), path = string("path"), sha256 = string("sha256"), status = string("status"),
)

private fun Cursor.toAction(): ActionItem {
    val type = ActionType.values().first { it.dbValue == string("action_type") }
    return ActionItem(
        id = getLong(column("id")), videoId = string("video_id"), videoUrl = string("video_url"), actionType = type,
        targetCommentId = if (isNull(column("target_comment_id"))) null else string("target_comment_id"),
        targetAuthor = string("target_author"), targetText = string("target_text"),
        targetDomIndex = int("target_dom_index"), text = string("text"), normalizedText = string("normalized_text"),
        imageId = longOrNull("image_id"), imagePath = if (isNull(column("image_path"))) null else string("image_path"),
        status = ActionStatus.values().first { it.dbValue == string("status") },
        likeStatus = if (isNull(column("like_status"))) null else LikeStatus.values().first { it.dbValue == string("like_status") },
        linkSavedAt = longOrNull("link_saved_at"), error = string("error"),
    )
}
