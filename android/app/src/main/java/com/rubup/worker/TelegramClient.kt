package com.rubup.worker

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class DownloadedFile(val bytes: ByteArray, val extension: String)

class TelegramClient(private val settings: SettingsStore) {
    private fun endpoint(method: String): String = "https://api.telegram.org/bot${settings.telegramToken}/$method"

    private fun connect(url: String, readTimeout: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            this.readTimeout = readTimeout
            useCaches = false
        }

    private fun readBytes(connection: HttpURLConnection, limit: Int = 25 * 1024 * 1024): ByteArray {
        val stream = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
            ?: throw BotFailure("Telegram HTTP ${connection.responseCode}: пустой ответ")
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw BotFailure("Ответ Telegram слишком большой")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    fun api(method: String, params: JSONObject = JSONObject(), readTimeout: Int = 60_000): JSONObject {
        val connection = connect(endpoint(method), readTimeout).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            connection.outputStream.use { it.write(params.toString().toByteArray(StandardCharsets.UTF_8)) }
            val response = JSONObject(String(readBytes(connection), StandardCharsets.UTF_8))
            if (!response.optBoolean("ok")) {
                throw BotFailure("Telegram $method: ${response.optString("description", "unknown error")}")
            }
            val result = response.opt("result")
            if (result is JSONObject) result else response
        } finally {
            connection.disconnect()
        }
    }

    fun getUpdates(offset: Long, timeoutSeconds: Int = 25): JSONArray {
        val params = JSONObject()
            .put("offset", offset)
            .put("timeout", timeoutSeconds)
            .put("allowed_updates", JSONArray().put("message").put("callback_query"))
        val response = api("getUpdates", params, (timeoutSeconds + 20) * 1000)
        return response.optJSONArray("result") ?: JSONArray()
    }

    fun sendMessage(
        chatId: Long,
        text: String,
        keyboard: Boolean = false,
        inlineKeyboard: JSONArray? = null,
    ) {
        val params = JSONObject()
            .put("chat_id", chatId)
            .put("text", text.take(3900))
            .put("link_preview_options", JSONObject().put("is_disabled", true))
        if (keyboard) params.put("reply_markup", mainKeyboard())
        if (inlineKeyboard != null) {
            params.put("reply_markup", JSONObject().put("inline_keyboard", inlineKeyboard))
        }
        api("sendMessage", params)
    }

    fun answerCallback(id: String, text: String) {
        api("answerCallbackQuery", JSONObject().put("callback_query_id", id).put("text", text.take(180)))
    }

    fun download(fileId: String): DownloadedFile {
        val response = api("getFile", JSONObject().put("file_id", fileId))
        val file = response.optJSONObject("result") ?: response
        val path = file.optString("file_path")
        if (path.isBlank()) throw BotFailure("Telegram не вернул путь к картинке")
        val connection = connect("https://api.telegram.org/file/bot${settings.telegramToken}/$path", 60_000)
        return try {
            DownloadedFile(readBytes(connection), path.substringAfterLast('.', "jpg").lowercase())
        } finally {
            connection.disconnect()
        }
    }

    fun sendDocument(chatId: Long, file: File, caption: String = "") {
        if (!file.isFile) throw BotFailure("Файл для отправки не найден")
        val boundary = "----RubUP${System.currentTimeMillis()}"
        val connection = connect(endpoint("sendDocument"), 60_000).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            connection.outputStream.use { output ->
                fun field(name: String, value: String) {
                    output.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                field("chat_id", chatId.toString())
                if (caption.isNotBlank()) field("caption", caption.take(1000))
                output.write(
                    "--$boundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"${file.name}\"\r\nContent-Type: text/plain\r\n\r\n".toByteArray(),
                )
                file.inputStream().use { it.copyTo(output) }
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val response = JSONObject(String(readBytes(connection), StandardCharsets.UTF_8))
            if (!response.optBoolean("ok")) throw BotFailure("Telegram sendDocument: ${response.optString("description")}")
        } finally {
            connection.disconnect()
        }
    }

    private fun mainKeyboard(): JSONObject {
        fun button(text: String) = JSONObject().put("text", text)
        fun row(vararg texts: String) = JSONArray().apply { texts.forEach { put(button(it)) } }
        return JSONObject()
            .put("keyboard", JSONArray()
                .put(row("▶️ Авто", "⏸ Пауза", "⛔ Стоп"))
                .put(row("📊 Статус", "🔎 Найти видео", "🖼 Картинки"))
                .put(row("📝 История"))
                .put(row("🔗 Ссылки", "📤 Отправить отчет", "✅ Мой TikTok"))
                .put(row("ℹ️ Помощь")))
            .put("resize_keyboard", true)
    }
}
