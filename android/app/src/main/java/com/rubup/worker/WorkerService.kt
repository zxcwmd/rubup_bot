package com.rubup.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

class WorkerService : Service() {
    private lateinit var settings: SettingsStore
    private lateinit var db: AppDatabase
    private lateinit var telegram: TelegramClient
    private lateinit var engine: AutomationEngine
    private lateinit var images: ImageLibrary
    private var polling: Thread? = null
    @Volatile private var running = true
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        db = AppDatabase(this)
        telegram = TelegramClient(settings)
        val runtime = WebViewRuntime.get(this)
        images = ImageLibrary(this, db)
        engine = AutomationEngine(this, db, settings, runtime, images)
        engine.infoListener = { text -> sendToAdmins(text) }
        engine.actionListener = { action, event, detail -> sendAction(action, event, detail) }
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rubup:worker").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("RubUP Worker запущен. Управление — через Telegram."))
        engine.start()
        when (intent?.action) {
            ACTION_AUTO -> engine.startAuto()
            ACTION_STOP -> Thread { engine.forceStop() }.start()
        }
        if (polling?.isAlive != true) {
            running = true
            polling = Thread({ pollTelegram() }, "rubup-telegram").apply {
                isDaemon = true
                start()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        polling?.interrupt()
        engine.shutdown()
        db.close()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setColor(Color.rgb(109, 40, 217))
            .build()
    }

    private fun pollTelegram() {
        var offset = 0L
        while (running) {
            if (!settings.isConfigured) {
                sleep(2000)
                continue
            }
            try {
                val updates = telegram.getUpdates(offset)
                for (index in 0 until updates.length()) {
                    val update = updates.getJSONObject(index)
                    offset = maxOf(offset, update.optLong("update_id") + 1)
                    handleUpdate(update)
                }
            } catch (error: Exception) {
                if (running) sleep(5000)
            }
        }
    }

    private fun handleUpdate(update: JSONObject) {
        update.optJSONObject("callback_query")?.let { callback ->
            val from = callback.optJSONObject("from")
            val chat = callback.optJSONObject("message")?.optJSONObject("chat")?.optLong("id") ?: return
            if (from == null || from.optLong("id") !in settings.adminIds) {
                telegram.answerCallback(callback.optString("id"), "Нет доступа")
                return
            }
            val data = callback.optString("data")
            try {
                when {
                    data.startsWith("manual:") -> {
                        val parts = data.split(':')
                        engine.resolveManual(parts[1].toLong(), parts[2] == "yes")
                        telegram.answerCallback(callback.optString("id"), "Сохранено")
                        telegram.sendMessage(chat, "Действие #${parts[1]}: ${if (parts[2] == "yes") "подтверждено" else "отмечено как неопубликованное"}.")
                    }
                    data.startsWith("like:") -> {
                        val id = data.split(':')[1].toLong()
                        telegram.answerCallback(callback.optString("id"), "Проверка запущена")
                        engine.retryLike(id)
                    }
                }
            } catch (error: Exception) {
                telegram.answerCallback(callback.optString("id"), error.message ?: "Ошибка")
            }
            return
        }
        val message = update.optJSONObject("message") ?: return
        val from = message.optJSONObject("from") ?: return
        val chat = message.optJSONObject("chat")?.optLong("id") ?: return
        if (from.optLong("id") !in settings.adminIds) {
            telegram.sendMessage(chat, "Нет доступа. Ваш Telegram ID не указан в настройках RubUP Worker.")
            return
        }
        if (message.has("photo") || message.has("document")) {
            receiveImage(chat, message)
            return
        }
        val text = message.optString("text").trim()
        if (text.isEmpty()) return
        try {
            when (text) {
                "/start", "/help", "ℹ️ Помощь" -> telegram.sendMessage(chat, HELP, keyboard = true)
                "/auto", "▶️ Авто" -> {
                    engine.startAuto(); telegram.sendMessage(chat, "Автоматика включена. Первый цикл начнётся сейчас.", keyboard = true)
                }
                "/pause", "⏸ Пауза" -> {
                    engine.pauseAuto(); telegram.sendMessage(chat, "Автоматика на паузе.")
                }
                "/stop", "⛔ Стоп" -> {
                    telegram.sendMessage(chat, "⛔ Останавливаю: новые действия запрещены, жду текущий вызов…")
                    engine.forceStop()
                    val stoppedDetail = if (settings.useInstalledTikTok) {
                        "Новые обращения к установленному TikTok остановлены; его сессия сохранена."
                    } else {
                        "WebView выгружен."
                    }
                    telegram.sendMessage(chat, "⛔ Остановлено. $stoppedDetail Для запуска нажмите ▶️ Авто.")
                }
                "/status", "📊 Статус" -> telegram.sendMessage(chat, engine.statusText())
                "/discover", "🔎 Найти видео" -> {
                    telegram.sendMessage(chat, "Поиск запущен."); engine.startDiscovery()
                }
                "/images", "🖼 Картинки" -> telegram.sendMessage(chat, engine.statusText())
                "/history", "📝 История" -> telegram.sendMessage(chat, engine.historyText())
                "/links", "🔗 Ссылки" -> sendLinks(chat)
                "/report", "📤 Отправить отчет" -> sendReport(chat)
                "/session", "✅ Мой TikTok" -> {
                    telegram.sendMessage(chat, "Проверяю TikTok-сессию…"); engine.checkSession()
                }
                else -> handleCommand(chat, text)
            }
        } catch (error: Exception) {
            telegram.sendMessage(chat, "Ошибка: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun handleCommand(chat: Long, text: String) {
        when {
            text.startsWith("/add ") -> engine.addVideo(text.substringAfter(' ').trim())
            text.startsWith("/retry_like ") -> engine.retryLike(text.substringAfter(' ').trim().toLong())
            text.startsWith("/retry ") -> engine.retryFailed(text.substringAfter(' ').trim().toLong())
            text.startsWith("/set_tags") -> {
                val tags = text.substringAfter("/set_tags").replace(',', ' ').split(Regex("\\s+"))
                    .map { it.trim().removePrefix("#").lowercase() }.filter(String::isNotBlank).distinct()
                if (tags.isEmpty() || tags.any { !it.matches(Regex("[\\p{L}\\p{N}_]+")) }) {
                    telegram.sendMessage(chat, "Использование: /set_tags cs2 caser нарезка")
                } else {
                    settings.tags = tags; settings.tagCursor = 0
                    telegram.sendMessage(chat, "Сохранено тегов: ${tags.size}")
                }
            }
            text.contains("tiktok.com/") -> {
                val match = Regex("https?://[^\\s]*tiktok\\.com/[^\\s]+").find(text)
                if (match != null) engine.addVideo(match.value.trimEnd(')', ',', '.', ';', '!'))
                else telegram.sendMessage(chat, "Не понял ссылку. Используйте /add ССЫЛКА.")
            }
            else -> telegram.sendMessage(chat, "Не понял команду. Нажмите ℹ️ Помощь.", keyboard = true)
        }
    }

    private fun receiveImage(chat: Long, message: JSONObject) {
        try {
            val photo = message.optJSONArray("photo")
            val document = message.optJSONObject("document")
            val fileId: String
            val extension: String
            if (photo != null && photo.length() > 0) {
                val largest = photo.getJSONObject(photo.length() - 1)
                fileId = largest.getString("file_id")
                extension = "jpg"
            } else if (document != null) {
                fileId = document.getString("file_id")
                extension = document.optString("file_name").substringAfterLast('.', "jpg").lowercase()
            } else {
                return
            }
            val downloaded = telegram.download(fileId)
            val result = images.importImage(ByteArrayInputStream(downloaded.bytes), downloaded.extension.ifBlank { extension })
            val text = when (result.first) {
                "added" -> "Картинка добавлена в одноразовую библиотеку."
                "duplicate" -> "Такая картинка уже есть; повторно она не будет использована."
                else -> "Файл не является поддержанным JPG/PNG/WEBP."
            }
            telegram.sendMessage(chat, text)
        } catch (error: Exception) {
            telegram.sendMessage(chat, "Не удалось сохранить картинку: ${error.message}")
        }
    }

    private fun sendLinks(chat: Long) {
        val file = engine.linkFile
        if (!file.isFile || file.readText().isBlank()) {
            telegram.sendMessage(chat, "Файл ссылок пока пуст. Ссылка появляется только после фото-комментария и подтверждённого лайка.")
        } else {
            telegram.sendDocument(chat, file, "Подтверждённые ссылки RubUP")
        }
    }

    private fun sendReport(chat: Long) {
        val reportId = db.createReport()
        if (reportId == null) {
            telegram.sendMessage(chat, "Нет полностью подтверждённых действий для нового отчёта.")
            return
        }
        val links = db.reportLinks(reportId)
        if (links.isEmpty()) {
            db.markReportSent(reportId)
            telegram.sendMessage(chat, "В отчёте нет ссылок.")
            return
        }
        var current = ""
        val chunks = mutableListOf<String>()
        links.forEach { link ->
            val candidate = if (current.isBlank()) link else "$current\n$link"
            if (candidate.length > 3500 && current.isNotBlank()) {
                chunks += current; current = link
            } else current = candidate
        }
        if (current.isNotBlank()) chunks += current
        chunks.forEach { telegram.sendMessage(chat, it) }
        db.markReportSent(reportId)
    }

    private fun sendAction(action: ActionItem, event: String, detail: String) {
        val kind = if (action.actionType == ActionType.COMMENT) "Комментарий" else "Ответ"
        val (text, inline) = when (event) {
            "published" -> if (action.actionType == ActionType.COMMENT) {
                "✅ Комментарий #${action.id} опубликован\n${action.videoUrl}\nДалее: лайк и сохранение ссылки." to null
            } else {
                "✅ Ответ #${action.id} опубликован\n${action.videoUrl}\nЛайк и запись в links.txt для ответа не выполняются." to null
            }
            "completed" -> "✅ Комментарий #${action.id} завершён: фото и лайк подтверждены, ссылка сохранена\n${action.videoUrl}" to null
            "manual" -> "⚠️ $kind #${action.id}: нужна ручная проверка\n${action.videoUrl}\n$detail" to inlineManual(action.id)
            "like_manual", "like_failed" -> "⚠️ Комментарий #${action.id}: лайк/ссылка не завершены\n${action.videoUrl}\n$detail" to inlineLike(action.id)
            else -> "❌ $kind #${action.id}: $detail\n${action.videoUrl}\n/retry ${action.id}" to null
        }
        sendToAdmins(text, inline)
    }

    private fun inlineManual(id: Long): JSONArray = JSONArray().put(JSONArray()
        .put(JSONObject().put("text", "✅ Опубликовано").put("callback_data", "manual:$id:yes"))
        .put(JSONObject().put("text", "❌ Не опубликовано").put("callback_data", "manual:$id:no")))

    private fun inlineLike(id: Long): JSONArray = JSONArray().put(JSONArray()
        .put(JSONObject().put("text", "🔄 Проверить лайк ещё раз").put("callback_data", "like:$id:retry")))

    private fun sendToAdmins(text: String, inline: JSONArray? = null) {
        settings.adminIds.forEach { id ->
            try { telegram.sendMessage(id, text, inlineKeyboard = inline) } catch (_: Exception) { }
        }
    }

    private fun sleep(milliseconds: Long) {
        try { Thread.sleep(milliseconds) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    companion object {
        const val ACTION_START = "com.rubup.worker.action.START"
        const val ACTION_AUTO = "com.rubup.worker.action.AUTO"
        const val ACTION_STOP = "com.rubup.worker.action.STOP"
        private const val CHANNEL_ID = "rubup-worker"
        private const val NOTIFICATION_ID = 41
        private const val HELP = """RubUP Worker для Android

▶️ Авто — запуск; ⏸ Пауза — пауза; ⛔ Стоп — запретить новые действия и безопасно завершить текущий вызов.
Основной комментарий: фото → лайк своего комментария → data/links.txt.
Отправляйте изображения фотографией или документом.

/add ССЫЛКА — добавить видео
/discover — только поиск
/set_tags тег1 тег2 — заменить теги
/history — история
/links — скачать ссылки
/report — отчёт только из ссылок
/retry_like ID — повторить проверку лайка
/retry ID — повтор только если публикации точно нет

Для installed-режима включите RubUP Worker в Настройки → Спецвозможности и войдите в уже установленный TikTok. WebView доступен как резерв в приложении."""
    }
}
