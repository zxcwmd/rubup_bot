package com.rubup.worker

import android.content.Context
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.random.Random

class AutomationEngine(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val runtime: WebViewRuntime,
    private val images: ImageLibrary,
) {
    private val installedMode = settings.useInstalledTikTok
    private val adapter: TikTokOperations = if (installedMode) {
        InstalledTikTokAdapter(context, settings, images)
    } else {
        TikTokAdapter(runtime, settings)
    }
    private val textEngine = TextEngine(context)
    private val links = LinkStore(File(context.filesDir, "links.txt"))
    private val browserLock = ReentrantLock()
    private val manualExecutor = Executors.newSingleThreadExecutor()
    private val wake = Object()
    @Volatile private var running = true
    @Volatile private var stopRequested = false
    private var scheduler: Thread? = null

    var infoListener: ((String) -> Unit)? = null
    var actionListener: ((ActionItem, String, String) -> Unit)? = null

    val linkFile: File get() = File(context.filesDir, "links.txt")

    fun start() {
        if (scheduler?.isAlive == true) return
        scheduler = thread(name = "rubup-scheduler", isDaemon = true) { schedulerLoop() }
    }

    private fun schedulerLoop() {
        while (running) {
            if (!settings.autoEnabled) {
                waitForWake(1000)
                continue
            }
            try {
                val result = browserCall { runCycle() }
                info("Цикл завершён: новых видео ${result.first}, попыток ${result.second}.")
            } catch (error: SessionRequired) {
                settings.autoEnabled = false
                info("⏸ Автоматика на паузе: ${error.message}")
            } catch (error: ChallengeRequired) {
                settings.autoEnabled = false
                info("⏸ Нужна проверка TikTok: ${error.message}")
            } catch (error: AutomationStopped) {
                info("⛔ Цикл остановлен.")
            } catch (error: Exception) {
                info("Ошибка цикла: ${error.javaClass.simpleName}: ${error.message}")
            }
            waitForWake(Defaults.SCAN_INTERVAL_MINUTES * 60_000)
        }
    }

    private fun waitForWake(milliseconds: Long) {
        synchronized(wake) {
            try { wake.wait(milliseconds) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    private fun wake() = synchronized(wake) { wake.notifyAll() }

    fun shutdown() {
        running = false
        stopRequested = true
        wake()
        manualExecutor.shutdownNow()
        scheduler?.interrupt()
        if (!installedMode) runtime.close()
    }

    fun startAuto() {
        stopRequested = false
        settings.autoEnabled = true
        wake()
    }

    fun pauseAuto() {
        settings.autoEnabled = false
        wake()
    }

    fun forceStop() {
        stopRequested = true
        settings.autoEnabled = false
        wake()
        browserLock.lock()
        try { if (!installedMode) runtime.close() } finally { browserLock.unlock() }
    }

    private fun checkNotStopped() {
        if (stopRequested) throw AutomationStopped("Принудительная остановка активна. Нажмите «Авто» для нового запуска.")
    }

    private fun <T> browserCall(block: () -> T): T {
        checkNotStopped()
        browserLock.lock()
        try {
            checkNotStopped()
            return block()
        } finally {
            browserLock.unlock()
        }
    }

    fun submit(task: () -> Unit) {
        manualExecutor.execute {
            try { task() } catch (error: Exception) {
                info("Ошибка: ${error.javaClass.simpleName}: ${error.message}")
            }
        }
    }

    fun startDiscovery() = submit {
        val result = browserCall {
            adapter.ensureAuthenticated()
            discover()
        }
        info("Поиск завершён: новых ${result.first}, подходят ${result.second}.")
    }

    fun addVideo(url: String) = submit {
        val result = browserCall {
            adapter.ensureAuthenticated()
            val video = adapter.getVideo(url)
            val decision = videoDateAllowed(video, settings.tags.toSet())
            db.upsertVideo(video, decision.first, decision.second)
            video to decision
        }
        val (video, decision) = result
        info(if (decision.first) "Видео добавлено: ${video.url}" else "Видео пропущено: ${decision.second}\n${video.url}")
    }

    fun checkSession() = submit {
        browserCall { adapter.ensureAuthenticated() }
        info("✅ Сохранённая TikTok-сессия активна.")
    }

    private fun runCycle(): Pair<Int, Int> {
        checkNotStopped()
        adapter.ensureAuthenticated()
        var attempts = retryLikes(Defaults.MAX_ACTIONS_PER_CYCLE)
        val discovery = discover()
        if (attempts < Defaults.MAX_ACTIONS_PER_CYCLE) {
            attempts += retryPending(Defaults.MAX_ACTIONS_PER_CYCLE - attempts)
        }
        if (attempts < Defaults.MAX_ACTIONS_PER_CYCLE) {
            attempts += processQueue(Defaults.MAX_ACTIONS_PER_CYCLE - attempts)
        }
        return discovery.first to attempts
    }

    private fun discover(): Pair<Int, Int> {
        val tags = settings.tags.ifEmpty { Defaults.TAGS }
        if (tags.isEmpty()) return 0 to 0
        val cursor = (settings.tagCursor % tags.size + tags.size) % tags.size
        val count = minOf(Defaults.MAX_TAGS_PER_CYCLE, tags.size)
        val selected = (0 until count).map { tags[(cursor + it) % tags.size] }
        settings.tagCursor = (cursor + count) % tags.size
        val known = db.allVideoUrls().toMutableSet()
        var newVideos = 0
        var eligible = 0
        var detailsBudget = Defaults.MAX_DETAILS_PER_CYCLE
        for (tag in selected) {
            checkNotStopped()
            if (detailsBudget <= 0) break
            val urls = try { adapter.discover(tag, Defaults.DISCOVERY_LIMIT) } catch (error: Exception) {
                if (error is ChallengeRequired || error is SessionRequired) throw error
                info("Поиск #$tag не удался: ${error.message}")
                continue
            }
            for (url in urls) {
                checkNotStopped()
                if (detailsBudget <= 0) break
                val canonical = canonicalVideoUrl(url) ?: continue
                if (canonical in known) continue
                detailsBudget--
                try {
                    val video = adapter.getVideo(canonical, tag)
                    val decision = videoDateAllowed(video, tags.toSet())
                    db.upsertVideo(video, decision.first, decision.second)
                    known += video.url
                    newVideos++
                    if (decision.first) eligible++
                } catch (error: Exception) {
                    if (error is ChallengeRequired || error is SessionRequired) throw error
                    info("Видео пропущено: ${error.message}")
                }
            }
        }
        return newVideos to eligible
    }

    private fun retryLikes(budget: Int): Int {
        var attempts = 0
        for (action in db.pendingLikes(budget)) {
            if (stopRequested || !settings.autoEnabled) break
            val video = db.getVideo(action.videoId) ?: continue
            likeAndSave(action, video)
            attempts++
            delayBetweenActions()
        }
        return attempts
    }

    private fun retryPending(budget: Int): Int {
        var attempts = 0
        for (action in db.pendingActions(budget)) {
            if (stopRequested || !settings.autoEnabled) break
            val video = db.getVideo(action.videoId) ?: continue
            publishExisting(action, video)
            attempts++
            delayBetweenActions()
        }
        return attempts
    }

    private fun processQueue(budget: Int): Int {
        var attempts = 0
        for (video in db.queuedVideos(maxOf(10, budget * 4))) {
            if (attempts >= budget || stopRequested || !settings.autoEnabled) break
            try {
                attempts += processVideo(video, budget - attempts)
            } catch (error: ChallengeRequired) {
                throw error
            } catch (error: SessionRequired) {
                throw error
            } catch (error: Exception) {
                db.setVideoState(video.id, "attention", error.message.orEmpty())
                info("⚠️ ${video.url}\n${error.javaClass.simpleName}: ${error.message}")
            }
        }
        return attempts
    }

    private fun processVideo(video: Video, budget: Int): Int {
        var attempts = 0
        val comments = adapter.getComments(video.url)
        if (!db.videoHasActions(video.id) && comments.any { isOwn(it, settings.tiktokUsername) && it.hasImage }) {
            db.setVideoState(video.id, "processed", "Свой комментарий с фото уже есть")
            return 0
        }
        val previousTargets = db.previousTargetTexts().toMutableList()
        var repliesToday = db.countActionsToday(ActionType.REPLY)
        var repliesHere = 0
        for (target in comments) {
            if (stopRequested || !settings.autoEnabled || attempts >= budget || repliesHere >= Defaults.MAX_REPLIES_PER_VIDEO) break
            if (repliesToday >= Defaults.MAX_REPLIES_PER_DAY) break
            if (!replyAllowed(target, settings.tiktokUsername, previousTargets)) continue
            if (db.actionExists(db.dedupeKey(ActionType.REPLY, video.id, target.id))) continue
            val forbidden = db.usedTexts(ActionType.REPLY) + comments.map { it.text }
            val text = try { textEngine.generate(video, ActionType.REPLY, forbidden, target.author) } catch (_: Exception) { break }
            val id = try { db.createAction(video, ActionType.REPLY, text, target, null) } catch (_: DuplicateAction) { continue }
            val action = db.getAction(id) ?: continue
            publishExisting(action, video)
            attempts++; repliesToday++; repliesHere++
            previousTargets += target.text
            delayBetweenActions()
            if (attempts >= budget) break
        }
        if (attempts >= budget || stopRequested || !settings.autoEnabled) return attempts
        if (db.countActionsToday(ActionType.COMMENT) >= Defaults.MAX_COMMENTS_PER_DAY) return attempts
        val image = db.nextImage()
        if (image == null) {
            db.setVideoState(video.id, "waiting_images", "Нет новых изображений")
            info("🖼 Закончились картинки. Отправьте новые изображения Telegram-боту.")
            return attempts
        }
        val forbidden = db.usedTexts(ActionType.COMMENT) + comments.map { it.text }
        val text = textEngine.generate(video, ActionType.COMMENT, forbidden)
        val id = try { db.createAction(video, ActionType.COMMENT, text, null, image) } catch (_: DuplicateAction) {
            db.setVideoState(video.id, "attention", "Действие уже существует")
            return attempts
        }
        val action = db.getAction(id) ?: return attempts
        publishExisting(action, video)
        return attempts + 1
    }

    private fun publishExisting(action: ActionItem, video: Video) {
        checkNotStopped()
        if (action.actionType == ActionType.COMMENT && action.imagePath == null) {
            db.setActionStatus(action.id, ActionStatus.MANUAL_REVIEW, "Нет обязательного изображения")
            return
        }
        db.setActionStatus(action.id, ActionStatus.PUBLISHING)
        val image = action.imagePath?.let { ImageAsset(action.imageId ?: 0, it, "", "reserved") }
        try {
            adapter.postComment(action, image, image?.let(images::uriFor), ::checkNotStopped)
        } catch (error: AutomationStopped) {
            db.setActionStatus(action.id, ActionStatus.PENDING, error.message.orEmpty())
            return
        } catch (error: ManualActionRequired) {
            db.setActionStatus(action.id, ActionStatus.MANUAL_REVIEW, error.message.orEmpty())
            if (action.actionType == ActionType.COMMENT) db.setVideoState(video.id, "attention", error.message.orEmpty())
            actionEvent(action.id, "manual", error.message.orEmpty())
            return
        } catch (error: ChallengeRequired) {
            db.setActionStatus(action.id, ActionStatus.PENDING, error.message.orEmpty())
            throw error
        } catch (error: SessionRequired) {
            db.setActionStatus(action.id, ActionStatus.PENDING, error.message.orEmpty())
            throw error
        } catch (error: Exception) {
            db.setActionStatus(action.id, ActionStatus.FAILED, error.message.orEmpty())
            if (action.actionType == ActionType.COMMENT) db.setVideoState(video.id, "attention", error.message.orEmpty())
            actionEvent(action.id, "failed", error.message.orEmpty())
            return
        }
        db.setActionStatus(action.id, ActionStatus.PUBLISHED)
        if (action.actionType == ActionType.COMMENT) db.setVideoState(video.id, "processed")
        actionEvent(action.id, "published")
        if (action.actionType == ActionType.COMMENT) likeAndSave(action, video)
    }

    private fun likeAndSave(action: ActionItem, video: Video) {
        if (stopRequested) return
        val current = db.getAction(action.id) ?: return
        if (current.status != ActionStatus.PUBLISHED || current.linkSavedAt != null) return
        if (current.imageId == null) {
            db.setLikeStatus(current.id, LikeStatus.MANUAL_REVIEW, "Нет обязательного изображения")
            return
        }
        val canonical = canonicalVideoUrl(video.url) ?: return
        if (current.likeStatus != LikeStatus.LIKED) {
            db.setLikeStatus(current.id, LikeStatus.PUBLISHING)
            val liked = try {
                adapter.likeOwnComment(current, ::checkNotStopped)
            } catch (error: AutomationStopped) {
                db.setLikeStatus(current.id, LikeStatus.PENDING, error.message.orEmpty()); return
            } catch (error: ChallengeRequired) {
                db.setLikeStatus(current.id, LikeStatus.PENDING, error.message.orEmpty()); throw error
            } catch (error: SessionRequired) {
                db.setLikeStatus(current.id, LikeStatus.PENDING, error.message.orEmpty()); throw error
            } catch (error: Exception) {
                db.setLikeStatus(current.id, LikeStatus.FAILED, error.message.orEmpty())
                actionEvent(current.id, "like_failed", error.message.orEmpty()); return
            }
            if (!liked) {
                val detail = "Лайк собственного комментария не подтверждён; ссылка не сохранена"
                db.setLikeStatus(current.id, LikeStatus.MANUAL_REVIEW, detail)
                actionEvent(current.id, "like_manual", detail)
                return
            }
            db.setLikeStatus(current.id, LikeStatus.LIKED)
        }
        links.append(canonical)
        db.saveLink(current.id, canonical, linkFile.absolutePath)
        actionEvent(current.id, "completed", canonical)
    }

    fun retryLike(id: Long) = submit {
        val action = db.getAction(id) ?: throw BotFailure("Действие не найдено")
        if (action.actionType != ActionType.COMMENT || action.status != ActionStatus.PUBLISHED) {
            throw BotFailure("Сначала подтвердите опубликованный основной комментарий")
        }
        if (action.linkSavedAt != null) throw BotFailure("Лайк и ссылка уже завершены")
        browserCall {
            db.setLikeStatus(id, LikeStatus.PENDING, "Ручная повторная проверка")
            likeAndSave(action, db.getVideo(action.videoId) ?: throw BotFailure("Видео не найдено"))
        }
    }

    fun resolveManual(id: Long, published: Boolean) {
        val action = db.getAction(id) ?: throw BotFailure("Действие не найдено")
        if (action.status != ActionStatus.MANUAL_REVIEW) throw BotFailure("Действие не ожидает ручной проверки")
        db.setActionStatus(id, if (published) ActionStatus.PUBLISHED else ActionStatus.FAILED,
            if (published) "" else "Публикации нет по проверке пользователя")
        if (action.actionType == ActionType.COMMENT) {
            db.setVideoState(action.videoId, if (published) "processed" else "attention")
        }
        if (published && action.actionType == ActionType.COMMENT) wake()
    }

    fun retryFailed(id: Long) {
        val action = db.getAction(id) ?: throw BotFailure("Действие не найдено")
        if (action.status != ActionStatus.FAILED) throw BotFailure("Повтор доступен только для неопубликованного действия")
        db.setActionStatus(id, ActionStatus.PENDING, "Ручной повтор")
        if (action.actionType == ActionType.COMMENT) db.setVideoState(action.videoId, "queued")
        startAuto()
    }

    private fun delayBetweenActions() {
        val total = (Defaults.ACTION_DELAY_SECONDS + Random.nextLong(Defaults.ACTION_JITTER_SECONDS + 1)) * 1000
        val end = System.currentTimeMillis() + total
        while (System.currentTimeMillis() < end && !stopRequested) Thread.sleep(200)
    }

    private fun actionEvent(id: Long, event: String, detail: String = "") {
        db.getAction(id)?.let { actionListener?.invoke(it, event, detail) }
    }

    private fun info(text: String) {
        infoListener?.invoke(text)
    }

    fun statusText(): String {
        val stats = db.stats()
        return "Режим: ${if (installedMode) "установленное приложение TikTok" else "WebView"}\n" +
            "Автоматика: ${if (settings.autoEnabled) "▶️ включена" else "⏸ на паузе"}\n" +
            "Видео: ${stats["videos"]} (в очереди ${stats["queued"]})\n" +
            "Опубликовано: ${stats["published"]}; ручная проверка: ${stats["manual"]}; ошибки: ${stats["failed"]}\n" +
            "Лайки: ожидают ${stats["pending_likes"]}, ручная проверка ${stats["likes_manual"]}; ссылок: ${stats["links"]}\n" +
            "Сегодня: комментарии ${db.countActionsToday(ActionType.COMMENT)}/${Defaults.MAX_COMMENTS_PER_DAY}, " +
            "ответы ${db.countActionsToday(ActionType.REPLY)}/${Defaults.MAX_REPLIES_PER_DAY}\n" +
            "Картинки: новые ${stats["images_available"]}, зарезервированы ${stats["images_reserved"]}, использованы ${stats["images_used"]}"
    }

    fun historyText(): String {
        val rows = db.history()
        if (rows.isEmpty()) return "История пока пуста."
        return rows.joinToString("\n\n") { action ->
            val kind = if (action.actionType == ActionType.COMMENT) "комментарий" else "ответ"
            val like = action.likeStatus?.dbValue?.let { "\nЛайк: $it; ссылка: ${if (action.linkSavedAt != null) "сохранена" else "не сохранена"}" } ?: ""
            "#${action.id} ${action.status.dbValue} $kind\n${action.videoUrl}\n${action.text.take(100)}$like"
        }.take(3900)
    }
}
